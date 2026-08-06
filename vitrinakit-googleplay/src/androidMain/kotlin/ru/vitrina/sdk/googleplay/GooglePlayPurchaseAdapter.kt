@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.googleplay

import android.content.Context
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.vitrina.sdk.purchase.VitrinaKitAdapterError
import ru.vitrina.sdk.purchase.VitrinaKitAdapterPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterException
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseCapability
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseInstruction
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResumeData
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase

/**
 * Google Play Billing adapter for the provider-neutral VitrinaKit purchase boundary.
 *
 * Construct this type in the application composition root and register it as the single purchase
 * adapter. The adapter sends raw purchase tokens only through the redacted core proof wrapper. It
 * never validates, acknowledges, consumes, logs, or exposes a purchase token to feature code.
 *
 */
class GooglePlayPurchaseAdapter internal constructor(
    private val packageName: String,
    private val activityHandleProvider: () -> BillingActivityHandle?,
    private val restoreReferenceResolver: GooglePlayRestoreReferenceResolver,
    private val gatewayFactory: () -> GooglePlayBillingGateway,
    private val emptyCallbackTimeoutMillis: Long = DefaultEmptyCallbackTimeoutMillis,
    private val launchDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val foregroundQueryIntervalMillis: Long = DefaultForegroundQueryIntervalMillis,
    private val clockMillis: () -> Long = { System.nanoTime() / NanosPerMillisecond },
) : VitrinaKitPurchaseAdapter {
    /**
     * Creates a Google Play adapter backed by BillingClient 9.1.0.
     *
     * @param context Android context used only through its application context.
     * @param activityProvider Supplies a resumed activity only at launch time.
     * @param restoreReferenceResolver Explicitly maps Play product IDs for fresh-install restore.
     */
    constructor(
        context: Context,
        activityProvider: GooglePlayActivityProvider,
        restoreReferenceResolver: GooglePlayRestoreReferenceResolver,
    ) : this(
        packageName = context.applicationContext.packageName,
        activityHandleProvider = {
            activityProvider.currentActivity()?.let(::BillingActivityHandle)
        },
        restoreReferenceResolver = restoreReferenceResolver,
        gatewayFactory = { RealGooglePlayBillingGateway(context = context.applicationContext) },
    )

    /** Google Play capability registered with the provider-neutral core. */
    override val capability: VitrinaKitPurchaseCapability = VitrinaKitPurchaseCapability.GOOGLE_PLAY

    private val presentationMutex = Mutex()
    private val recoveryStateLock = Any()
    private val proofFingerprints = WeakHashMap<ru.vitrina.sdk.purchase.VitrinaKitProviderProof, String>()
    private val acceptedProofFingerprints = mutableSetOf<String>()
    private var lastForegroundQuery: ForegroundQuery? = null
    private var activeWaiter: ActivePurchaseWaiter? = null
    private var billingResources: BillingResources? = createResources()

    /** Presents a fresh server instruction or recovers an already visible matching purchase. */
    override suspend fun present(
        instruction: VitrinaKitPurchaseInstruction,
    ): VitrinaKitAdapterPurchaseResult = presentationMutex.withLock {
        validateInstruction(instruction)?.let { failure -> return@withLock failure }
        recoverForInstruction(instruction)?.let { recovered -> return@withLock recovered }

        val productResult = resources().connection.queryProductDetails(productId = instruction.productId)
        if (productResult.responseCode != BillingResponseCode.OK) {
            return@withLock failure(
                responseCode = productResult.responseCode,
                message = "Google Play product details are unavailable.",
            )
        }
        val product = productResult.values.singleOrNull { details ->
            details.productId == instruction.productId
        } ?: return@withLock failure(
            responseCode = BillingResponseCode.ITEM_UNAVAILABLE,
            message = "The Google Play product does not match the purchase instruction.",
        )
        val basePlanId = instruction.priceId
            ?: return@withLock failure(
                responseCode = BillingResponseCode.DEVELOPER_ERROR,
                message = "The Google Play base plan is missing.",
            )
        val offer = selectBasePlanOffer(product = product, basePlanId = basePlanId)
            ?: return@withLock failure(
                responseCode = BillingResponseCode.DEVELOPER_ERROR,
                message = "The Google Play base plan is unavailable or ambiguous.",
            )

        val waiter = ActivePurchaseWaiter(
            productId = instruction.productId,
            result = CompletableDeferred(),
            emptyOkCallback = CompletableDeferred(),
        )
        synchronized(recoveryStateLock) {
            activeWaiter = waiter
        }
        try {
            val launchCode = launchBillingFlow(
                request = BillingFlowRequest(
                    productId = instruction.productId,
                    offerToken = offer.offerToken,
                    obfuscatedAccountId = instruction.accountBinding,
                    obfuscatedProfileId = instruction.accountBinding,
                ),
            ) ?: return@withLock failure(
                responseCode = BillingResponseCode.SERVICE_UNAVAILABLE,
                message = "No foreground activity is available for Google Play Billing.",
            )

            if (launchCode != BillingResponseCode.OK) {
                if (launchCode == BillingResponseCode.ITEM_ALREADY_OWNED) {
                    recoverForInstruction(instruction)?.let { recovered -> return@withLock recovered }
                }
                return@withLock mapResponse(
                    responseCode = launchCode,
                    instruction = instruction,
                )
            }

            val update = awaitPurchaseUpdate(waiter)
            if (update == null) {
                return@withLock failure(
                    responseCode = BillingResponseCode.ERROR,
                    message = "Google Play did not return a matching purchase in time.",
                )
            }
            mapUpdate(update = update, instruction = instruction)
        } finally {
            clearWaiter(waiter)
        }
    }

    /** Queries purchased subscriptions and applies the explicit restore reference resolver. */
    override suspend fun queryRestorablePurchases(): List<VitrinaKitRestorablePurchase> {
        val query = resources().connection.queryPurchases()
        if (query.responseCode != BillingResponseCode.OK) {
            throw VitrinaKitPurchaseAdapterException(
                error = adapterError(
                    responseCode = query.responseCode,
                    message = "Google Play purchase recovery is unavailable.",
                ),
            )
        }
        val purchases = deduplicateQueried(
            queried = query.values,
            includeStates = setOf(BillingPurchaseState.PURCHASED),
        )
        return purchases.mapNotNull { purchase ->
            val productId = purchase.productIds.singleOrNull() ?: return@mapNotNull null
            when (val resolution = restoreReferenceResolver.resolve(productId)) {
                is GooglePlayRestoreResolution.Mapped -> purchase.toRestorablePurchase(
                    mapping = resolution,
                    proof = proofFor(purchase),
                )
                GooglePlayRestoreResolution.Skip -> null
            }
        }
    }

    /** Queries Play for an interrupted attempt without relaunching billing UI. */
    override suspend fun resume(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData,
    ): VitrinaKitAdapterPurchaseResult {
        validateInstruction(instruction)?.let { failure -> return failure }
        if (resumeData.value != instruction.attemptReference) {
            return failure(
                responseCode = BillingResponseCode.DEVELOPER_ERROR,
                message = "The Google Play resume state does not match the purchase attempt.",
            )
        }
        return recoverForInstruction(instruction) ?: failure(
            responseCode = BillingResponseCode.ITEM_UNAVAILABLE,
            message = "The Google Play purchase is not visible for recovery.",
        )
    }

    /** Queries Play for one core-tracked attempt without launching billing UI. */
    override suspend fun recover(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData?,
    ): VitrinaKitAdapterPurchaseResult? {
        validateInstruction(instruction)?.let { failure -> return failure }
        if (resumeData != null && resumeData.value != instruction.attemptReference) {
            return failure(
                responseCode = BillingResponseCode.DEVELOPER_ERROR,
                message = "The Google Play resume state does not match the purchase attempt.",
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

    /** Suppresses a token fingerprint after core reports server acceptance of its proof. */
    override fun onProofAccepted(proof: ru.vitrina.sdk.purchase.VitrinaKitProviderProof) {
        synchronized(recoveryStateLock) {
            val fingerprint = proofFingerprints.remove(proof) ?: return
            acceptedProofFingerprints += fingerprint
            proofFingerprints.entries.removeAll { entry -> entry.value == fingerprint }
        }
    }

    /** Disconnects current BillingClient resources; the next operation initializes a fresh client. */
    override fun close() {
        val (waiter, resources) = synchronized(recoveryStateLock) {
            proofFingerprints.clear()
            acceptedProofFingerprints.clear()
            lastForegroundQuery = null
            val waiter = activeWaiter.also { activeWaiter = null }
            val resources = billingResources.also { billingResources = null }
            waiter to resources
        }
        waiter?.result?.complete(
            BillingPurchaseUpdate(
                responseCode = BillingResponseCode.SERVICE_DISCONNECTED,
                purchases = emptyList(),
            ),
        )
        resources?.gateway?.setPurchaseUpdateListener(null)
        resources?.connection?.close()
    }

    private suspend fun recoverForInstruction(
        instruction: VitrinaKitPurchaseInstruction,
        completeActiveWaiter: Boolean = false,
    ): VitrinaKitAdapterPurchaseResult? {
        val query = resources().connection.queryPurchases()
        if (query.responseCode != BillingResponseCode.OK) {
            return failure(
                responseCode = query.responseCode,
                message = "Google Play purchase recovery is unavailable.",
            )
        }
        val waiter = if (completeActiveWaiter) {
            synchronized(recoveryStateLock) { activeWaiter }
        } else {
            null
        }
        val waiterMatches = waiter?.let { current ->
            query.values
                .filter { purchase -> current.productId in purchase.productIds && !isAccepted(purchase) }
                .distinctBy { purchase -> purchase.purchaseToken }
        }.orEmpty()
        if (waiter != null && waiterMatches.isNotEmpty()) {
            waiter.result.complete(
                BillingPurchaseUpdate(
                    responseCode = BillingResponseCode.OK,
                    purchases = waiterMatches,
                ),
            )
            return null
        }
        val matching = deduplicateQueried(
            queried = query.values,
            includeStates = setOf(BillingPurchaseState.PURCHASED, BillingPurchaseState.PENDING),
        ).filter { purchase -> instruction.productId in purchase.productIds }
        val purchased = matching.singleOrNull { purchase ->
            purchase.state == BillingPurchaseState.PURCHASED
        }
        if (purchased != null) {
            return VitrinaKitAdapterPurchaseResult.ProofReady(proof = proofFor(purchased))
        }
        if (matching.any { purchase -> purchase.state == BillingPurchaseState.PENDING }) {
            return pending(instruction)
        }
        return null
    }

    private fun deduplicateQueried(
        queried: List<BillingPurchase>,
        includeStates: Set<BillingPurchaseState>,
    ): List<BillingPurchase> = synchronized(recoveryStateLock) {
        queried
            .filter { purchase -> purchase.state in includeStates }
            .filterNot(::isAccepted)
            .distinctBy { purchase -> purchase.purchaseToken }
    }

    private fun handlePurchaseUpdate(update: BillingPurchaseUpdate) {
        val waiter = synchronized(recoveryStateLock) { activeWaiter } ?: return
        if (waiter.result.isCompleted) {
            return
        }
        if (update.responseCode != BillingResponseCode.OK) {
            waiter.result.complete(update)
            return
        }
        if (update.purchases.isEmpty()) {
            waiter.emptyOkCallback.complete(Unit)
            return
        }
        val matching = update.purchases
            .filter { purchase -> waiter.productId in purchase.productIds && !isAccepted(purchase) }
            .distinctBy { purchase -> purchase.purchaseToken }
        if (matching.isNotEmpty()) {
            waiter.result.complete(
                BillingPurchaseUpdate(
                    responseCode = BillingResponseCode.OK,
                    purchases = matching,
                ),
            )
        }
    }

    private fun mapUpdate(
        update: BillingPurchaseUpdate,
        instruction: VitrinaKitPurchaseInstruction,
    ): VitrinaKitAdapterPurchaseResult {
        if (update.responseCode != BillingResponseCode.OK) {
            return mapResponse(responseCode = update.responseCode, instruction = instruction)
        }
        val matching = update.purchases
            .filter { purchase -> instruction.productId in purchase.productIds && !isAccepted(purchase) }
            .distinctBy { purchase -> purchase.purchaseToken }
        val purchased = matching.singleOrNull { purchase ->
            purchase.state == BillingPurchaseState.PURCHASED
        }
        if (purchased != null) {
            return VitrinaKitAdapterPurchaseResult.ProofReady(proof = proofFor(purchased))
        }
        if (matching.any { purchase -> purchase.state == BillingPurchaseState.PENDING }) {
            return pending(instruction)
        }
        return failure(
            responseCode = BillingResponseCode.ERROR,
            message = "Google Play returned no completed or pending purchase.",
        )
    }

    private fun mapResponse(
        responseCode: BillingResponseCode,
        instruction: VitrinaKitPurchaseInstruction,
    ): VitrinaKitAdapterPurchaseResult = when (responseCode) {
        BillingResponseCode.USER_CANCELED -> VitrinaKitAdapterPurchaseResult.Cancelled
        BillingResponseCode.OK -> pending(instruction)
        else -> failure(
            responseCode = responseCode,
            message = "Google Play Billing could not complete the purchase.",
        )
    }

    private fun validateInstruction(
        instruction: VitrinaKitPurchaseInstruction,
    ): VitrinaKitAdapterPurchaseResult.Failure? {
        if (instruction.packageName != packageName) {
            return failure(
                responseCode = BillingResponseCode.DEVELOPER_ERROR,
                message = "The Google Play package does not match this application.",
            )
        }
        if (
            instruction.productId.isBlank() ||
            instruction.priceId.isNullOrBlank() ||
            instruction.accountBinding.isBlank()
        ) {
            return failure(
                responseCode = BillingResponseCode.DEVELOPER_ERROR,
                message = "The Google Play purchase instruction is incomplete.",
            )
        }
        return null
    }

    private fun selectBasePlanOffer(product: BillingProduct, basePlanId: String): BillingOffer? =
        product.offers.singleOrNull { offer ->
            offer.basePlanId == basePlanId && offer.offerId == null
        }

    private fun pending(instruction: VitrinaKitPurchaseInstruction): VitrinaKitAdapterPurchaseResult.Pending =
        VitrinaKitAdapterPurchaseResult.Pending(
            resumeData = VitrinaKitPurchaseResumeData(instruction.attemptReference),
        )

    private fun failure(
        responseCode: BillingResponseCode,
        message: String,
    ): VitrinaKitAdapterPurchaseResult.Failure = VitrinaKitAdapterPurchaseResult.Failure(
        error = adapterError(responseCode = responseCode, message = message),
    )

    private fun adapterError(
        responseCode: BillingResponseCode,
        message: String,
    ): VitrinaKitAdapterError = VitrinaKitAdapterError(
            message = message,
            retryable = responseCode.isRetryable(),
            supportReference = "GPB-${responseCode.providerCode}",
    )

    private fun clearWaiter(waiter: ActivePurchaseWaiter) {
        synchronized(recoveryStateLock) {
            if (activeWaiter === waiter) {
                activeWaiter = null
            }
        }
        waiter.result.cancel()
        waiter.emptyOkCallback.cancel()
    }

    private suspend fun awaitPurchaseUpdate(waiter: ActivePurchaseWaiter): BillingPurchaseUpdate? =
        when (
            val firstSignal = select<PurchaseWaitSignal> {
                waiter.result.onAwait { update -> PurchaseWaitSignal.Update(update) }
                waiter.emptyOkCallback.onAwait { PurchaseWaitSignal.EmptyOk }
            }
        ) {
            is PurchaseWaitSignal.Update -> firstSignal.update
            PurchaseWaitSignal.EmptyOk -> withTimeoutOrNull(emptyCallbackTimeoutMillis) {
                waiter.result.await()
            }
        }

    private fun proofFor(purchase: BillingPurchase): ru.vitrina.sdk.purchase.VitrinaKitProviderProof {
        val proof = purchase.toProof()
        synchronized(recoveryStateLock) {
            proofFingerprints[proof] = tokenFingerprint(purchase.purchaseToken)
        }
        return proof
    }

    private fun isAccepted(purchase: BillingPurchase): Boolean = synchronized(recoveryStateLock) {
        tokenFingerprint(purchase.purchaseToken) in acceptedProofFingerprints
    }

    private suspend fun launchBillingFlow(request: BillingFlowRequest): BillingResponseCode? =
        withContext(launchDispatcher) {
            val activity = activityHandleProvider() ?: return@withContext null
            resources().connection.launch(activity = activity, request = request)
        }

    private fun resources(): BillingResources = synchronized(recoveryStateLock) {
        billingResources ?: createResources().also { created -> billingResources = created }
    }

    private fun createResources(): BillingResources {
        val gateway = gatewayFactory()
        gateway.setPurchaseUpdateListener(::handlePurchaseUpdate)
        return BillingResources(
            gateway = gateway,
            connection = BillingClientConnection(gateway = gateway),
        )
    }

    private companion object {
        const val DefaultEmptyCallbackTimeoutMillis = 30_000L
        const val DefaultForegroundQueryIntervalMillis = 30_000L
    }
}

private data class ActivePurchaseWaiter(
    val productId: String,
    val result: CompletableDeferred<BillingPurchaseUpdate>,
    val emptyOkCallback: CompletableDeferred<Unit>,
)

private sealed interface PurchaseWaitSignal {
    data class Update(val update: BillingPurchaseUpdate) : PurchaseWaitSignal

    data object EmptyOk : PurchaseWaitSignal
}

private data class BillingResources(
    val gateway: GooglePlayBillingGateway,
    val connection: BillingClientConnection,
)

private data class ForegroundQuery(
    val attemptReference: String,
    val timestampMillis: Long,
)

private fun BillingResponseCode.isRetryable(): Boolean = when (this) {
    BillingResponseCode.SERVICE_TIMEOUT,
    BillingResponseCode.SERVICE_DISCONNECTED,
    BillingResponseCode.SERVICE_UNAVAILABLE,
    BillingResponseCode.ERROR,
    BillingResponseCode.NETWORK_ERROR,
    BillingResponseCode.UNKNOWN,
    -> true

    else -> false
}

private fun tokenFingerprint(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.encodeToByteArray())
    .joinToString(separator = "") { byte ->
        (byte.toInt() and UnsignedByteMask).toString(radix = HexRadix).padStart(2, '0')
    }

private const val UnsignedByteMask = 0xff
private const val HexRadix = 16
private const val NanosPerMillisecond = 1_000_000L
