@file:OptIn(VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.purchase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlin.random.Random
import ru.vitrina.sdk.cache.SubscriberCache
import ru.vitrina.sdk.model.PaywallProduct
import ru.vitrina.sdk.model.VitrinaKitProfile

internal interface PurchaseApi {
    suspend fun startPurchase(
        scope: SubscriberScope,
        placementId: String,
        productReference: String,
        capability: VitrinaKitPurchaseCapability,
        idempotencyKey: String,
    ): PurchaseApiResult<PurchaseAttempt>

    suspend fun confirmPurchase(
        scope: SubscriberScope,
        attemptReference: String,
        idempotencyKey: String,
        proof: VitrinaKitProviderProof,
    ): PurchaseApiResult<PurchaseConfirmation>

    suspend fun getPurchase(
        scope: SubscriberScope,
        attemptReference: String,
    ): PurchaseApiResult<PurchaseAttempt>

    suspend fun restorePurchases(
        scope: SubscriberScope,
        purchases: List<VitrinaKitRestorablePurchase>,
        capability: VitrinaKitPurchaseCapability,
    ): PurchaseApiResult<PurchaseRestoreResponse>

    suspend fun refreshProfile(scope: SubscriberScope): PurchaseApiResult<VitrinaKitProfile>
}

internal data class PendingPurchase(
    val attemptReference: String,
    val placementId: String,
    val productReference: String,
    val capability: VitrinaKitPurchaseCapability,
    val startIdempotencyKey: String,
    val confirmationIdempotencyKey: String,
    val expiresAt: String,
    val resumeData: VitrinaKitPurchaseResumeData?,
) {
    override fun toString(): String =
        "PendingPurchase(attemptReference=$attemptReference, placementId=$placementId, " +
            "productReference=$productReference, capability=$capability, startIdempotencyKey=<redacted>, " +
            "confirmationIdempotencyKey=<redacted>, expiresAt=$expiresAt, resumeData=<redacted>)"
}

@OptIn(VitrinaKitPurchaseAdapterApi::class)
internal class PurchaseCoordinator(
    private val adapter: VitrinaKitPurchaseAdapter,
    private val api: PurchaseApi,
    private val cache: SubscriberCache,
    private val idempotencyKey: () -> String = ::newIdempotencyKey,
) {
    private val purchaseGuard = Mutex()

    suspend fun purchase(
        scope: SubscriberScope,
        placementId: String,
        product: PaywallProduct,
    ): VitrinaKitPurchaseResult {
        if (!purchaseGuard.tryLock()) {
            return failure(
                code = VitrinaKitPurchaseErrorCode.PURCHASE_IN_PROGRESS,
                message = "A purchase is already in progress.",
            )
        }
        val cacheGeneration = cache.generation
        try {
            return runCatching {
                purchaseGuarded(
                    scope = scope,
                    placementId = placementId,
                    product = product,
                    cacheGeneration = cacheGeneration,
                )
            }
                .fold(
                    onSuccess = { result -> result },
                    onFailure = { throwable ->
                        if (throwable is CancellationException) {
                            throw throwable
                        }
                        failure(
                            code = VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE,
                            message = "The purchase adapter could not complete presentation.",
                        )
                    },
                )
        } finally {
            purchaseGuard.unlock()
        }
    }

    suspend fun restore(scope: SubscriberScope): VitrinaKitRestoreResult {
        if (!purchaseGuard.tryLock()) {
            return VitrinaKitRestoreResult.Failure(
                error = error(
                    code = VitrinaKitPurchaseErrorCode.PURCHASE_IN_PROGRESS,
                    message = "A purchase is already in progress.",
                ),
            )
        }
        val cacheGeneration = cache.generation
        try {
            return runCatching { restoreGuarded(scope = scope, cacheGeneration = cacheGeneration) }
                .fold(
                    onSuccess = { result -> result },
                    onFailure = { throwable ->
                        if (throwable is CancellationException) {
                            throw throwable
                        }
                        VitrinaKitRestoreResult.Failure(
                            error = error(
                                code = VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE,
                                message = "The purchase adapter could not query restorable purchases.",
                            ),
                        )
                    },
                )
        } finally {
            purchaseGuard.unlock()
        }
    }

    fun clear(scope: SubscriberScope) {
        cache.clear(key = scope.cacheKey)
        adapter.close()
    }

    fun close() {
        cache.clearAll()
        adapter.close()
    }

    private suspend fun purchaseGuarded(
        scope: SubscriberScope,
        placementId: String,
        product: PaywallProduct,
        cacheGeneration: Long,
    ): VitrinaKitPurchaseResult {
        if (placementId.isBlank()) {
            return failure(
                code = VitrinaKitPurchaseErrorCode.INVALID_REQUEST,
                message = "The product must come from a VitrinaKit paywall.",
            )
        }
        val cached = cache.resume(key = scope.cacheKey)
        val compatible = cached?.takeIf { pending ->
            pending.placementId == placementId &&
                pending.productReference == product.productKey &&
                pending.capability == adapter.capability
        }
        val startKey = compatible?.startIdempotencyKey ?: idempotencyKey()
        val resumeData = compatible?.resumeData
        if (compatible != null && resumeData == null) {
            return recoverServerPending(
                scope = scope,
                pending = compatible,
                cacheGeneration = cacheGeneration,
            )
        }
        val started = api.startPurchase(
            scope = scope,
            placementId = placementId,
            productReference = product.productKey,
            capability = adapter.capability,
            idempotencyKey = startKey,
        )
        val attempt = when (started) {
            is PurchaseApiResult.Success -> started.value
            is PurchaseApiResult.Failure -> return VitrinaKitPurchaseResult.Failure(started.error)
        }
        if (cache.generation != cacheGeneration) {
            return invalidatedIdentityFailure()
        }
        if (attempt.capability != adapter.capability) {
            cache.clearResumeIfCurrent(key = scope.cacheKey, generation = cacheGeneration)
            return failure(
                code = VitrinaKitPurchaseErrorCode.PROVIDER_NOT_SUPPORTED_BY_BUILD,
                message = "The build does not contain the required purchase adapter.",
            )
        }
        val adapterResult = if (
            compatible != null &&
            resumeData != null &&
            compatible.attemptReference == attempt.reference
        ) {
            adapter.resume(instruction = attempt.instruction, resumeData = resumeData)
        } else {
            adapter.present(instruction = attempt.instruction)
        }
        if (cache.generation != cacheGeneration) {
            return invalidatedIdentityFailure()
        }
        return mapAdapterResult(
            scope = scope,
            placementId = placementId,
            productReference = product.productKey,
            attempt = attempt,
            startKey = startKey,
            confirmationKey = compatible?.confirmationIdempotencyKey,
            adapterResult = adapterResult,
            cacheGeneration = cacheGeneration,
        )
    }

    private suspend fun mapAdapterResult(
        scope: SubscriberScope,
        placementId: String,
        productReference: String,
        attempt: PurchaseAttempt,
        startKey: String,
        confirmationKey: String?,
        adapterResult: VitrinaKitAdapterPurchaseResult,
        cacheGeneration: Long,
    ): VitrinaKitPurchaseResult = when (adapterResult) {
        is VitrinaKitAdapterPurchaseResult.ProofReady -> confirm(
            scope = scope,
            placementId = placementId,
            productReference = productReference,
            attempt = attempt,
            startKey = startKey,
            confirmationKey = confirmationKey ?: idempotencyKey(),
            proof = adapterResult.proof,
            cacheGeneration = cacheGeneration,
        )

        is VitrinaKitAdapterPurchaseResult.Pending -> {
            cache.storeResumeIfCurrent(
                key = scope.cacheKey,
                resume = PendingPurchase(
                    attemptReference = attempt.reference,
                    placementId = placementId,
                    productReference = productReference,
                    capability = attempt.capability,
                    startIdempotencyKey = startKey,
                    confirmationIdempotencyKey = confirmationKey ?: idempotencyKey(),
                    expiresAt = attempt.expiresAt,
                    resumeData = adapterResult.resumeData,
                ),
                generation = cacheGeneration,
            )
            VitrinaKitPurchaseResult.Pending(
                attemptReference = attempt.reference,
                profile = cache.profile(key = scope.cacheKey),
            )
        }

        VitrinaKitAdapterPurchaseResult.Cancelled -> {
            cache.clearResumeIfCurrent(key = scope.cacheKey, generation = cacheGeneration)
            VitrinaKitPurchaseResult.Cancelled
        }

        is VitrinaKitAdapterPurchaseResult.Failure -> {
            cache.clearResumeIfCurrent(key = scope.cacheKey, generation = cacheGeneration)
            failure(
                code = VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE,
                message = "The purchase adapter could not complete presentation.",
            )
        }
    }

    private suspend fun confirm(
        scope: SubscriberScope,
        placementId: String,
        productReference: String,
        attempt: PurchaseAttempt,
        startKey: String,
        confirmationKey: String,
        proof: VitrinaKitProviderProof,
        cacheGeneration: Long,
    ): VitrinaKitPurchaseResult {
        val confirmed = api.confirmPurchase(
            scope = scope,
            attemptReference = attempt.reference,
            idempotencyKey = confirmationKey,
            proof = proof,
        )
        if (cache.generation != cacheGeneration) {
            return invalidatedIdentityFailure()
        }
        return when (confirmed) {
            is PurchaseApiResult.Failure -> {
                if (confirmed.error.code == VitrinaKitPurchaseErrorCode.NETWORK_ERROR ||
                    confirmed.error.code == VitrinaKitPurchaseErrorCode.PROVIDER_VALIDATION_UNAVAILABLE
                ) {
                    cache.storeResumeIfCurrent(
                        key = scope.cacheKey,
                        resume = PendingPurchase(
                            attemptReference = attempt.reference,
                            placementId = placementId,
                            productReference = productReference,
                            capability = attempt.capability,
                            startIdempotencyKey = startKey,
                            confirmationIdempotencyKey = confirmationKey,
                            expiresAt = attempt.expiresAt,
                            resumeData = null,
                        ),
                        generation = cacheGeneration,
                    )
                } else {
                    cache.clearResumeIfCurrent(key = scope.cacheKey, generation = cacheGeneration)
                }
                VitrinaKitPurchaseResult.Failure(confirmed.error)
            }

            is PurchaseApiResult.Success -> {
                val outcome = confirmed.value
                if (outcome.pending) {
                    cache.storeResumeIfCurrent(
                        key = scope.cacheKey,
                        resume = PendingPurchase(
                            attemptReference = outcome.attempt.reference,
                            placementId = placementId,
                            productReference = productReference,
                            capability = outcome.attempt.capability,
                            startIdempotencyKey = startKey,
                            confirmationIdempotencyKey = confirmationKey,
                            expiresAt = outcome.attempt.expiresAt,
                            resumeData = null,
                        ),
                        generation = cacheGeneration,
                    )
                    VitrinaKitPurchaseResult.Pending(
                        attemptReference = outcome.attempt.reference,
                        profile = outcome.profile ?: cache.profile(key = scope.cacheKey),
                    )
                } else {
                    val profile = outcome.profile ?: return failure(
                        code = VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED,
                        message = "The server did not return an authoritative subscriber profile.",
                    )
                    cache.replaceProfileIfCurrent(
                        key = scope.cacheKey,
                        profile = profile,
                        generation = cacheGeneration,
                    )
                    cache.clearResumeIfCurrent(key = scope.cacheKey, generation = cacheGeneration)
                    VitrinaKitPurchaseResult.Success(
                        purchaseReference = outcome.attempt.reference,
                        profile = profile,
                    )
                }
            }
        }
    }

    private suspend fun recoverServerPending(
        scope: SubscriberScope,
        pending: PendingPurchase,
        cacheGeneration: Long,
    ): VitrinaKitPurchaseResult {
        val status = api.getPurchase(
            scope = scope,
            attemptReference = pending.attemptReference,
        )
        val attempt = when (status) {
            is PurchaseApiResult.Failure -> return VitrinaKitPurchaseResult.Failure(status.error)
            is PurchaseApiResult.Success -> status.value
        }
        if (cache.generation != cacheGeneration) {
            return invalidatedIdentityFailure()
        }
        return when (attempt.status) {
            VitrinaKitPurchaseAttemptStatus.SUCCEEDED,
            VitrinaKitPurchaseAttemptStatus.DUPLICATE_COVERAGE,
            -> recoverSuccessfulProfile(
                scope = scope,
                attempt = attempt,
                cacheGeneration = cacheGeneration,
            )

            VitrinaKitPurchaseAttemptStatus.CANCELLED -> {
                cache.clearResumeIfCurrent(key = scope.cacheKey, generation = cacheGeneration)
                VitrinaKitPurchaseResult.Cancelled
            }

            VitrinaKitPurchaseAttemptStatus.FAILED -> {
                cache.clearResumeIfCurrent(key = scope.cacheKey, generation = cacheGeneration)
                failure(
                    code = if (attempt.reason == PurchaseAttemptExpiredReason) {
                        VitrinaKitPurchaseErrorCode.PURCHASE_ATTEMPT_EXPIRED
                    } else {
                        VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED
                    },
                    message = "The purchase attempt failed.",
                )
            }

            VitrinaKitPurchaseAttemptStatus.PROVIDER_READY,
            VitrinaKitPurchaseAttemptStatus.PRESENTED,
            -> recoverProviderProof(
                scope = scope,
                pending = pending,
                attempt = attempt,
                cacheGeneration = cacheGeneration,
            )

            VitrinaKitPurchaseAttemptStatus.CREATED -> recoverCreatedAttempt(
                scope = scope,
                pending = pending,
                cacheGeneration = cacheGeneration,
            )

            VitrinaKitPurchaseAttemptStatus.PROOF_RECEIVED,
            VitrinaKitPurchaseAttemptStatus.VALIDATING,
            VitrinaKitPurchaseAttemptStatus.PENDING,
            -> VitrinaKitPurchaseResult.Pending(
                attemptReference = attempt.reference,
                profile = cache.profile(key = scope.cacheKey),
            )
        }
    }

    private suspend fun recoverCreatedAttempt(
        scope: SubscriberScope,
        pending: PendingPurchase,
        cacheGeneration: Long,
    ): VitrinaKitPurchaseResult {
        val restarted = api.startPurchase(
            scope = scope,
            placementId = pending.placementId,
            productReference = pending.productReference,
            capability = pending.capability,
            idempotencyKey = pending.startIdempotencyKey,
        )
        val attempt = when (restarted) {
            is PurchaseApiResult.Failure -> return VitrinaKitPurchaseResult.Failure(restarted.error)
            is PurchaseApiResult.Success -> restarted.value
        }
        if (cache.generation != cacheGeneration) {
            return invalidatedIdentityFailure()
        }
        return recoverProviderProof(
            scope = scope,
            pending = pending,
            attempt = attempt,
            cacheGeneration = cacheGeneration,
        )
    }

    private suspend fun recoverProviderProof(
        scope: SubscriberScope,
        pending: PendingPurchase,
        attempt: PurchaseAttempt,
        cacheGeneration: Long,
    ): VitrinaKitPurchaseResult {
        if (attempt.capability != adapter.capability) {
            cache.clearResumeIfCurrent(key = scope.cacheKey, generation = cacheGeneration)
            return failure(
                code = VitrinaKitPurchaseErrorCode.PROVIDER_NOT_SUPPORTED_BY_BUILD,
                message = "The build does not contain the required purchase adapter.",
            )
        }
        val adapterResult = adapter.present(instruction = attempt.instruction)
        if (cache.generation != cacheGeneration) {
            return invalidatedIdentityFailure()
        }
        return mapAdapterResult(
            scope = scope,
            placementId = pending.placementId,
            productReference = pending.productReference,
            attempt = attempt,
            startKey = pending.startIdempotencyKey,
            confirmationKey = pending.confirmationIdempotencyKey,
            adapterResult = adapterResult,
            cacheGeneration = cacheGeneration,
        )
    }

    private suspend fun recoverSuccessfulProfile(
        scope: SubscriberScope,
        attempt: PurchaseAttempt,
        cacheGeneration: Long,
    ): VitrinaKitPurchaseResult = when (val profile = api.refreshProfile(scope = scope)) {
        is PurchaseApiResult.Failure -> VitrinaKitPurchaseResult.Failure(profile.error)
        is PurchaseApiResult.Success -> {
            if (cache.generation != cacheGeneration) {
                return invalidatedIdentityFailure()
            }
            cache.replaceProfileIfCurrent(
                key = scope.cacheKey,
                profile = profile.value,
                generation = cacheGeneration,
            )
            cache.clearResumeIfCurrent(key = scope.cacheKey, generation = cacheGeneration)
            VitrinaKitPurchaseResult.Success(
                purchaseReference = attempt.reference,
                profile = profile.value,
            )
        }
    }

    private suspend fun restoreGuarded(
        scope: SubscriberScope,
        cacheGeneration: Long,
    ): VitrinaKitRestoreResult {
        val purchases = adapter.queryRestorablePurchases()
        if (cache.generation != cacheGeneration) {
            return invalidatedIdentityRestoreFailure()
        }
        val restored = api.restorePurchases(
            scope = scope,
            purchases = purchases,
            capability = adapter.capability,
        )
        return when (restored) {
            is PurchaseApiResult.Failure -> VitrinaKitRestoreResult.Failure(restored.error)
            is PurchaseApiResult.Success -> {
                if (cache.generation != cacheGeneration) {
                    return invalidatedIdentityRestoreFailure()
                }
                cache.replaceProfileIfCurrent(
                    key = scope.cacheKey,
                    profile = restored.value.profile,
                    generation = cacheGeneration,
                )
                if (restored.value.purchases.isEmpty()) {
                    VitrinaKitRestoreResult.NoPurchases(profile = restored.value.profile)
                } else {
                    VitrinaKitRestoreResult.Success(
                        profile = restored.value.profile,
                        purchases = restored.value.purchases,
                    )
                }
            }
        }
    }
}

private fun invalidatedIdentityFailure(): VitrinaKitPurchaseResult.Failure = failure(
    code = VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
    message = "Subscriber identity changed while the purchase was in progress.",
)

private fun invalidatedIdentityRestoreFailure(): VitrinaKitRestoreResult.Failure =
    VitrinaKitRestoreResult.Failure(
        error = error(
            code = VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
            message = "Subscriber identity changed while restore was in progress.",
        ),
    )

private fun failure(code: VitrinaKitPurchaseErrorCode, message: String): VitrinaKitPurchaseResult.Failure =
    VitrinaKitPurchaseResult.Failure(error = error(code = code, message = message))

private fun error(code: VitrinaKitPurchaseErrorCode, message: String): VitrinaKitPurchaseError =
    VitrinaKitPurchaseError(
        code = code,
        message = message,
        retryable = false,
        supportReference = null,
    )

private fun newIdempotencyKey(): String = buildString {
    repeat(IdempotencyRandomBytes) {
        append(Random.nextInt(from = 0, until = HexRadix).toString(radix = HexRadix))
    }
}

private const val IdempotencyRandomBytes = 32
private const val HexRadix = 16
private const val PurchaseAttemptExpiredReason = "purchase_attempt_expired"
