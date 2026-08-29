@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.rustore

import android.content.Context
import android.content.Intent
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.vitrina.sdk.purchase.VitrinaKitAdapterError
import ru.vitrina.sdk.purchase.VitrinaKitAdapterPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitProviderProof
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterException
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseCapability
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseInstruction
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResumeData
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase

/**
 * RuStore Pay adapter for the provider-neutral VitrinaKit purchase boundary.
 *
 * Construct this type in the application composition root and register it as the single purchase
 * adapter. The adapter uses one-step Pay SDK presentation and sends only the raw purchase ID through
 * the redacted core proof wrapper. It never confirms, cancels, acknowledges, refunds, revokes, logs,
 * or exposes a provider purchase ID to feature code.
 *
 * The host application must configure RuStore's `console_app_id_value` and
 * `sdk_pay_scheme_value` manifest metadata, a matching deep-link intent filter, and `singleTop` on
 * the receiving activity. Forward both the initial and each new payment-return intent through
 * [handlePaymentIntent].
 */
class RuStorePurchaseAdapter internal constructor(
    private val packageName: String,
    private val activityHandleProvider: () -> RuStoreActivityHandle?,
    private val gatewayFactory: () -> RuStorePayGateway,
    private val launchDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val foregroundQueryIntervalMillis: Long = DefaultForegroundQueryIntervalMillis,
    private val clockMillis: () -> Long = { System.nanoTime() / NanosPerMillisecond },
) : VitrinaKitPurchaseAdapter {
    /**
     * Creates an adapter backed by RuStore Pay SDK 11.0.0.
     *
     * @param context Android context used only to bind instructions to this application package.
     * @param activityProvider Supplies a resumed activity only at presentation time.
     */
    constructor(
        context: Context,
        activityProvider: RuStoreActivityProvider,
    ) : this(
        packageName = context.applicationContext.packageName,
        activityHandleProvider = {
            activityProvider.currentActivity()?.let(::RuStoreActivityHandle)
        },
        gatewayFactory = ::RealRuStorePayGateway,
    )

    /** RuStore capability registered with the provider-neutral core. */
    override val capability: VitrinaKitPurchaseCapability = VitrinaKitPurchaseCapability.RUSTORE

    private val presentationMutex = Mutex()
    private val recoveryStateLock = Any()
    private val proofFingerprints = WeakHashMap<VitrinaKitProviderProof, String>()
    private val acceptedProofFingerprints = mutableSetOf<String>()
    private var lastForegroundQuery: ForegroundQuery? = null
    private var activeWaiter: ActivePurchaseWaiter? = null
    private var ownershipGeneration: Long = 0L
    private var payResources: RuStorePayGateway? = null

    /** Presents a fresh server instruction or recovers an already visible matching purchase. */
    override suspend fun present(
        instruction: VitrinaKitPurchaseInstruction,
    ): VitrinaKitAdapterPurchaseResult = presentationMutex.withLock {
        validateInstruction(instruction)?.let { failure -> return@withLock failure }
        val waiter = synchronized(recoveryStateLock) {
            ownershipGeneration += 1L
            ActivePurchaseWaiter(
                attemptReference = instruction.attemptReference,
                productId = instruction.productId,
                generation = ownershipGeneration,
                result = CompletableDeferred(),
            ).also { created -> activeWaiter = created }
        }
        try {
            recoverForInstruction(
                instruction = instruction,
                completeActiveWaiter = true,
            )?.let { recovered -> return@withLock recovered }
            waiter.completedOutcomeOrNull()?.let { outcome ->
                return@withLock mapOutcome(outcome)
            }
            if (!ownsActiveWaiter(waiter)) {
                return@withLock pending(instruction)
            }

            val productGateway = try {
                resourcesFor(waiter)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: RuStoreGatewayException) {
                return@withLock providerFailure(
                    kind = exception.kind,
                    message = "RuStore product resolution is unavailable.",
                )
            }
            if (productGateway == null) {
                waiter.completedOutcomeOrNull()?.let { outcome ->
                    return@withLock mapOutcome(outcome)
                }
                return@withLock pending(instruction)
            }
            val products = try {
                productGateway.getProducts(instruction.productId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: RuStoreGatewayException) {
                waiter.completedOutcomeOrNull()?.let { outcome ->
                    return@withLock mapOutcome(outcome)
                }
                return@withLock providerFailure(
                    kind = exception.kind,
                    message = "RuStore product resolution is unavailable.",
                )
            }
            waiter.completedOutcomeOrNull()?.let { outcome ->
                return@withLock mapOutcome(outcome)
            }
            if (!ownsActiveWaiter(waiter)) {
                return@withLock pending(instruction)
            }
            val product = products.singleOrNull { candidate ->
                candidate.productId == instruction.productId
            } ?: return@withLock providerFailure(
                kind = RuStoreFailureKind.CONFIGURATION,
                message = "The RuStore product does not match the purchase instruction.",
            )

            val launched = try {
                launchPurchase(
                    waiter = waiter,
                    request = RuStorePurchaseRequest(
                        productId = product.productId,
                        appUserId = instruction.accountBinding,
                        purchaseType = RuStorePurchaseType.ONE_STEP,
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: RuStoreGatewayException) {
                return@withLock providerFailure(
                    kind = exception.kind,
                    message = "RuStore Pay could not start the purchase.",
                )
            } catch (_: Throwable) {
                return@withLock providerFailure(
                    kind = RuStoreFailureKind.UNKNOWN,
                    message = "RuStore Pay could not start the purchase.",
                )
            }
            waiter.completedOutcomeOrNull()?.let { outcome ->
                return@withLock mapOutcome(outcome)
            }
            if (!launched) {
                return@withLock providerFailure(
                    kind = RuStoreFailureKind.UNAVAILABLE,
                    message = "No foreground activity is available for RuStore Pay.",
                )
            }
            mapOutcome(waiter.result.await())
        } finally {
            clearWaiter(waiter)
        }
    }

    /** Queries every purchase visible to the current RuStore session for restore. */
    override suspend fun queryRestorablePurchases(): List<VitrinaKitRestorablePurchase> {
        val purchases = try {
            resources().getPurchases()
        } catch (exception: RuStoreGatewayException) {
            throw VitrinaKitPurchaseAdapterException(
                error = adapterError(
                    kind = exception.kind,
                    message = "RuStore purchase recovery is unavailable.",
                ),
            )
        }
        return deduplicateQueried(
            purchases = purchases,
            includeStates = setOf(RuStorePurchaseState.PURCHASED),
        ).map { purchase -> purchase.toRestorablePurchase(proof = proofFor(purchase)) }
    }

    /** Queries RuStore for an interrupted attempt without presenting payment UI. */
    override suspend fun resume(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData,
    ): VitrinaKitAdapterPurchaseResult {
        validateInstruction(instruction)?.let { failure -> return failure }
        if (resumeData.value != instruction.attemptReference) {
            return providerFailure(
                kind = RuStoreFailureKind.CONFIGURATION,
                message = "The RuStore resume state does not match the purchase attempt.",
            )
        }
        return recoverForInstruction(instruction) ?: pending(instruction)
    }

    /** Queries RuStore for one core-tracked attempt without presenting payment UI. */
    override suspend fun recover(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData?,
    ): VitrinaKitAdapterPurchaseResult? {
        validateInstruction(instruction)?.let { failure -> return failure }
        if (resumeData != null && resumeData.value != instruction.attemptReference) {
            return providerFailure(
                kind = RuStoreFailureKind.CONFIGURATION,
                message = "The RuStore resume state does not match the purchase attempt.",
            )
        }
        val now = clockMillis()
        val throttled = synchronized(recoveryStateLock) {
            lastForegroundQuery?.let { query ->
                query.attemptReference == instruction.attemptReference &&
                    now - query.timestampMillis < foregroundQueryIntervalMillis
            } ?: false
        }
        if (throttled) {
            return null
        }
        val recovered = recoverForInstruction(
            instruction = instruction,
            completeActiveWaiter = true,
        )
        if (recovered !is VitrinaKitAdapterPurchaseResult.Failure) {
            synchronized(recoveryStateLock) {
                lastForegroundQuery = ForegroundQuery(
                    attemptReference = instruction.attemptReference,
                    timestampMillis = now,
                )
            }
        }
        return recovered
    }

    /** Marks a purchase-ID fingerprint accepted only after authoritative server confirmation. */
    override fun onProofAccepted(proof: VitrinaKitProviderProof) {
        synchronized(recoveryStateLock) {
            val fingerprint = proofFingerprints.remove(proof) ?: return
            acceptedProofFingerprints += fingerprint
            proofFingerprints.entries.removeAll { entry -> entry.value == fingerprint }
        }
    }

    /**
     * Immediately forwards an initial or new payment-return [intent] to RuStore Pay SDK.
     *
     * The adapter and its gateway do not retain the supplied intent. Provider failures are exposed
     * only as a redacted [VitrinaKitPurchaseAdapterException].
     */
    fun handlePaymentIntent(intent: Intent?) {
        try {
            resources().proceedIntent(intent)
        } catch (exception: RuStoreGatewayException) {
            throw VitrinaKitPurchaseAdapterException(
                error = adapterError(
                    kind = exception.kind,
                    message = "RuStore payment return handling is unavailable.",
                ),
            )
        } catch (_: Throwable) {
            throw VitrinaKitPurchaseAdapterException(
                error = adapterError(
                    kind = RuStoreFailureKind.UNKNOWN,
                    message = "RuStore payment return handling is unavailable.",
                ),
            )
        }
    }

    /** Cancels adapter work and clears subscriber-scoped recovery state for lazy reuse. */
    override fun close() {
        val (waiter, resources) = synchronized(recoveryStateLock) {
            ownershipGeneration += 1L
            proofFingerprints.clear()
            acceptedProofFingerprints.clear()
            lastForegroundQuery = null
            val waiter = activeWaiter.also { activeWaiter = null }
            val resources = payResources.also { payResources = null }
            waiter to resources
        }
        waiter?.result?.complete(RuStorePurchaseOutcome.Failure(RuStoreFailureKind.UNAVAILABLE))
        waiter?.operation?.cancel()
        resources?.close()
    }

    private suspend fun recoverForInstruction(
        instruction: VitrinaKitPurchaseInstruction,
        completeActiveWaiter: Boolean = false,
    ): VitrinaKitAdapterPurchaseResult? {
        val claim = synchronized(recoveryStateLock) {
            RecoveryClaim(
                generation = ownershipGeneration,
                waiter = if (completeActiveWaiter) {
                    activeWaiter?.takeIf { waiter ->
                        waiter.attemptReference == instruction.attemptReference &&
                            waiter.productId == instruction.productId
                    }
                } else {
                    null
                },
            )
        }
        val gateway = try {
            synchronized(recoveryStateLock) {
                if (ownershipGeneration != claim.generation) {
                    return null
                }
                resources()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuStoreGatewayException) {
            if (!isOwnershipGenerationCurrent(claim.generation)) {
                return null
            }
            return providerFailure(
                kind = exception.kind,
                message = "RuStore purchase recovery is unavailable.",
            )
        }
        val purchases = try {
            gateway.getPurchases()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuStoreGatewayException) {
            if (!isOwnershipGenerationCurrent(claim.generation)) {
                return null
            }
            return providerFailure(
                kind = exception.kind,
                message = "RuStore purchase recovery is unavailable.",
            )
        }
        if (!isOwnershipGenerationCurrent(claim.generation)) {
            return null
        }
        val waiterMatches = claim.waiter?.let { waiter ->
            purchases
                .filter { purchase -> waiter.productId == purchase.productId && !isAccepted(purchase) }
                .distinctBy { purchase -> purchase.purchaseId }
        }.orEmpty()
        if (claim.waiter != null && waiterMatches.isNotEmpty()) {
            when (
                deliverRecoveredToPresentation(
                    waiter = claim.waiter,
                    purchases = waiterMatches,
                    generation = claim.generation,
                )
            ) {
                RecoveryDelivery.OWNED_BY_PRESENTATION,
                RecoveryDelivery.STALE,
                -> return null
                RecoveryDelivery.RELEASED -> Unit
            }
        }
        val matching = deduplicateQueried(
            purchases = purchases,
            includeStates = setOf(RuStorePurchaseState.PURCHASED),
        ).filter { purchase -> purchase.productId == instruction.productId }
        val purchased = matching.singleOrNull { purchase -> purchase.state == RuStorePurchaseState.PURCHASED }
        if (purchased != null) {
            return synchronized(recoveryStateLock) {
                if (ownershipGeneration == claim.generation) {
                    VitrinaKitAdapterPurchaseResult.ProofReady(proof = proofFor(purchased))
                } else {
                    null
                }
            }
        }
        return null
    }

    private suspend fun deliverRecoveredToPresentation(
        waiter: ActivePurchaseWaiter,
        purchases: List<RuStorePurchase>,
        generation: Long,
    ): RecoveryDelivery {
        val outcome = purchases.toOutcome(waiter.productId) ?: return RecoveryDelivery.RELEASED
        val completion = synchronized(recoveryStateLock) {
            if (ownershipGeneration != generation) {
                return@synchronized RecoveryCompletion.STALE
            }
            if (waiter.result.complete(outcome)) {
                RecoveryCompletion.DELIVERED
            } else {
                RecoveryCompletion.ALREADY_COMPLETED
            }
        }
        when (completion) {
            RecoveryCompletion.DELIVERED -> return RecoveryDelivery.OWNED_BY_PRESENTATION
            RecoveryCompletion.STALE -> return RecoveryDelivery.STALE
            RecoveryCompletion.ALREADY_COMPLETED -> Unit
        }
        val completed = runCatching { waiter.result.await() }.getOrElse {
            currentCoroutineContext().ensureActive()
            return if (isOwnershipGenerationCurrent(generation)) {
                RecoveryDelivery.RELEASED
            } else {
                RecoveryDelivery.STALE
            }
        }
        return if (!isOwnershipGenerationCurrent(generation)) {
            RecoveryDelivery.STALE
        } else if (completed.owns(outcome)) {
            RecoveryDelivery.OWNED_BY_PRESENTATION
        } else {
            RecoveryDelivery.RELEASED
        }
    }

    private fun handlePurchaseOutcome(waiter: ActivePurchaseWaiter, outcome: RuStorePurchaseOutcome) {
        val normalized = when (outcome) {
            is RuStorePurchaseOutcome.Success -> if (outcome.productId == waiter.productId) {
                outcome
            } else {
                RuStorePurchaseOutcome.Failure(RuStoreFailureKind.CONFIGURATION)
            }
            else -> outcome
        }
        synchronized(recoveryStateLock) {
            if (
                ownershipGeneration == waiter.generation &&
                activeWaiter === waiter &&
                !waiter.result.isCompleted
            ) {
                waiter.result.complete(normalized)
            }
        }
    }

    private fun mapOutcome(outcome: RuStorePurchaseOutcome): VitrinaKitAdapterPurchaseResult =
        when (outcome) {
            is RuStorePurchaseOutcome.Success -> {
                val purchase = RuStorePurchase(
                    purchaseId = outcome.purchaseId,
                    productId = outcome.productId,
                    state = RuStorePurchaseState.PURCHASED,
                )
                VitrinaKitAdapterPurchaseResult.ProofReady(proof = proofFor(purchase))
            }
            RuStorePurchaseOutcome.Cancelled -> VitrinaKitAdapterPurchaseResult.Cancelled
            is RuStorePurchaseOutcome.Failure -> providerFailure(
                kind = outcome.kind,
                message = "RuStore Pay could not complete the purchase.",
            )
        }

    private suspend fun launchPurchase(
        waiter: ActivePurchaseWaiter,
        request: RuStorePurchaseRequest,
    ): Boolean = withContext(launchDispatcher) {
        activityHandleProvider() ?: return@withContext false
        synchronized(recoveryStateLock) {
            if (
                ownershipGeneration != waiter.generation ||
                activeWaiter !== waiter ||
                waiter.result.isCompleted
            ) {
                return@synchronized false
            }
            val operation = resources().purchase(request) { outcome ->
                handlePurchaseOutcome(waiter = waiter, outcome = outcome)
            }
            if (
                ownershipGeneration == waiter.generation &&
                activeWaiter === waiter &&
                !waiter.result.isCompleted
            ) {
                waiter.operation = operation
            } else {
                operation.cancel()
            }
            true
        }
    }

    private fun ownsActiveWaiter(waiter: ActivePurchaseWaiter): Boolean =
        synchronized(recoveryStateLock) {
            ownershipGeneration == waiter.generation && activeWaiter === waiter
        }

    private fun resourcesFor(waiter: ActivePurchaseWaiter): RuStorePayGateway? =
        synchronized(recoveryStateLock) {
            if (ownershipGeneration == waiter.generation && activeWaiter === waiter) {
                resources()
            } else {
                null
            }
        }

    private fun isOwnershipGenerationCurrent(generation: Long): Boolean =
        synchronized(recoveryStateLock) { ownershipGeneration == generation }

    private fun deduplicateQueried(
        purchases: List<RuStorePurchase>,
        includeStates: Set<RuStorePurchaseState>,
    ): List<RuStorePurchase> = synchronized(recoveryStateLock) {
        purchases
            .filter { purchase -> purchase.state in includeStates }
            .filterNot(::isAccepted)
            .distinctBy { purchase -> purchase.purchaseId }
    }

    private fun proofFor(purchase: RuStorePurchase): VitrinaKitProviderProof {
        val proof = purchase.toProof()
        synchronized(recoveryStateLock) {
            proofFingerprints[proof] = proofFingerprint(purchase.purchaseId)
        }
        return proof
    }

    private fun isAccepted(purchase: RuStorePurchase): Boolean = synchronized(recoveryStateLock) {
        proofFingerprint(purchase.purchaseId) in acceptedProofFingerprints
    }

    private fun validateInstruction(
        instruction: VitrinaKitPurchaseInstruction,
    ): VitrinaKitAdapterPurchaseResult.Failure? {
        if (instruction.packageName != packageName) {
            return providerFailure(
                kind = RuStoreFailureKind.CONFIGURATION,
                message = "The RuStore package does not match this application.",
            )
        }
        if (instruction.productId.isBlank() || instruction.accountBinding.isBlank()) {
            return providerFailure(
                kind = RuStoreFailureKind.CONFIGURATION,
                message = "The RuStore purchase instruction is incomplete.",
            )
        }
        return null
    }

    private fun pending(instruction: VitrinaKitPurchaseInstruction): VitrinaKitAdapterPurchaseResult.Pending =
        VitrinaKitAdapterPurchaseResult.Pending(
            resumeData = VitrinaKitPurchaseResumeData(instruction.attemptReference),
        )

    private fun providerFailure(
        kind: RuStoreFailureKind,
        message: String,
    ): VitrinaKitAdapterPurchaseResult.Failure = VitrinaKitAdapterPurchaseResult.Failure(
        error = adapterError(kind = kind, message = message),
    )

    private fun adapterError(kind: RuStoreFailureKind, message: String): VitrinaKitAdapterError =
        VitrinaKitAdapterError(
            message = message,
            retryable = kind.isRetryable(),
            supportReference = "RSP-${kind.name}",
        )

    private fun clearWaiter(waiter: ActivePurchaseWaiter) {
        synchronized(recoveryStateLock) {
            if (activeWaiter === waiter) {
                activeWaiter = null
            }
        }
        waiter.operation?.cancel()
        waiter.result.cancel()
    }

    private fun resources(): RuStorePayGateway = synchronized(recoveryStateLock) {
        payResources ?: createResources().also { created -> payResources = created }
    }

    private fun createResources(): RuStorePayGateway = try {
        gatewayFactory()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RuStoreGatewayException) {
        throw exception
    } catch (exception: Throwable) {
        throw exception.toSafeRuStoreGatewayException()
    }

    private companion object {
        const val DefaultForegroundQueryIntervalMillis = 30_000L
    }
}

private data class ActivePurchaseWaiter(
    val attemptReference: String,
    val productId: String,
    val generation: Long,
    val result: CompletableDeferred<RuStorePurchaseOutcome>,
    var operation: RuStorePurchaseOperation? = null,
)

private data class RecoveryClaim(
    val generation: Long,
    val waiter: ActivePurchaseWaiter?,
)

private enum class RecoveryCompletion {
    DELIVERED,
    ALREADY_COMPLETED,
    STALE,
}

private enum class RecoveryDelivery {
    OWNED_BY_PRESENTATION,
    RELEASED,
    STALE,
}

private data class ForegroundQuery(
    val attemptReference: String,
    val timestampMillis: Long,
)

private suspend fun ActivePurchaseWaiter.completedOutcomeOrNull(): RuStorePurchaseOutcome? {
    if (!result.isCompleted) {
        return null
    }
    return runCatching { result.await() }.getOrElse {
        currentCoroutineContext().ensureActive()
        null
    }
}

private fun List<RuStorePurchase>.toOutcome(productId: String): RuStorePurchaseOutcome? {
    val purchased = singleOrNull { purchase ->
        purchase.productId == productId && purchase.state == RuStorePurchaseState.PURCHASED
    }
    if (purchased != null) {
        return RuStorePurchaseOutcome.Success(
            purchaseId = purchased.purchaseId,
            productId = purchased.productId,
        )
    }
    return null
}

private fun RuStorePurchaseOutcome.owns(recovered: RuStorePurchaseOutcome): Boolean = when (this) {
    is RuStorePurchaseOutcome.Success -> when (recovered) {
        is RuStorePurchaseOutcome.Success -> purchaseId == recovered.purchaseId
        else -> false
    }
    RuStorePurchaseOutcome.Cancelled,
    is RuStorePurchaseOutcome.Failure,
    -> false
}

private fun RuStoreFailureKind.isRetryable(): Boolean = when (this) {
    RuStoreFailureKind.NETWORK,
    RuStoreFailureKind.UNAVAILABLE,
    RuStoreFailureKind.UNKNOWN,
    -> true
    RuStoreFailureKind.CONFIGURATION -> false
}

private fun proofFingerprint(proof: String): String = MessageDigest.getInstance("SHA-256")
    .digest(proof.encodeToByteArray())
    .joinToString(separator = "") { byte ->
        (byte.toInt() and UnsignedByteMask).toString(radix = HexRadix).padStart(2, '0')
    }

private const val UnsignedByteMask = 0xff
private const val HexRadix = 16
private const val NanosPerMillisecond = 1_000_000L
