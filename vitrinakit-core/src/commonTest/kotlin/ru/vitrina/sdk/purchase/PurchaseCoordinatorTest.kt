@file:OptIn(VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.purchase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import ru.vitrina.sdk.cache.SubscriberCache
import ru.vitrina.sdk.cache.SubscriberCacheKey
import ru.vitrina.sdk.model.BillingIntervalUnit
import ru.vitrina.sdk.model.Entitlement
import ru.vitrina.sdk.model.EntitlementSource
import ru.vitrina.sdk.model.PaywallProduct
import ru.vitrina.sdk.model.SubscriberEntitlementState
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.model.SubscriptionStatus

@OptIn(VitrinaKitPurchaseAdapterApi::class)
class PurchaseCoordinatorTest {
    @Test
    fun successRequiresAuthoritativeProfileAndNeverExposesProof() = runTest {
        val proof = "provider-proof-secret"
        val api = FakePurchaseApi(
            confirmResult = PurchaseApiResult.Success(
                PurchaseConfirmation(
                    attempt = attempt(status = VitrinaKitPurchaseAttemptStatus.SUCCEEDED),
                    pending = false,
                    profile = profile(hasAccess = true),
                ),
            ),
        )
        val adapter = FakePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(VitrinaKitProviderProof(proof)),
        )
        val coordinator = coordinator(api = api, adapter = adapter)

        val result = coordinator.purchase(scope = trustedScope(), product = product())

        val success = assertIs<VitrinaKitPurchaseResult.Success>(result)
        assertEquals("attempt-1", success.purchaseReference)
        assertTrue(success.profile.hasAccess)
        assertFalse(result.toString().contains(proof))
        assertFalse(api.confirmedProofText().contains(proof))
    }

    @Test
    fun proofWithoutAuthoritativeProfileFailsClosed() = runTest {
        val api = FakePurchaseApi(
            confirmResult = PurchaseApiResult.Success(
                PurchaseConfirmation(
                    attempt = attempt(status = VitrinaKitPurchaseAttemptStatus.SUCCEEDED),
                    pending = false,
                    profile = null,
                ),
            ),
        )
        val coordinator = coordinator(
            api = api,
            adapter = FakePurchaseAdapter(
                presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(VitrinaKitProviderProof("proof")),
            ),
        )

        val result = coordinator.purchase(scope = trustedScope(), product = product())

        assertEquals(
            VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED,
            assertIs<VitrinaKitPurchaseResult.Failure>(result).error.code,
        )
    }

    @Test
    fun pendingStoresOpaqueResumeStateAndCompatibleRetryReusesAttempt() = runTest {
        val api = FakePurchaseApi()
        val adapter = FakePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.Pending(VitrinaKitPurchaseResumeData("resume-secret")),
            resumeResult = VitrinaKitAdapterPurchaseResult.Cancelled,
        )
        val cache = SubscriberCache()
        val coordinator = coordinator(api = api, adapter = adapter, cache = cache)

        val first = coordinator.purchase(scope = trustedScope(), product = product())
        val second = coordinator.purchase(scope = trustedScope(), product = product())

        assertIs<VitrinaKitPurchaseResult.Pending>(first)
        assertIs<VitrinaKitPurchaseResult.Cancelled>(second)
        assertEquals(listOf("start-1", "start-1"), api.startIdempotencyKeys)
        assertEquals(1, adapter.presentCount)
        assertEquals(1, adapter.resumeCount)
        val cached = cache.resume(cacheKey())
        assertEquals("attempt-1", cached?.attemptReference)
        assertFalse(cached.toString().contains("resume-secret"))
        assertFalse(first.toString().contains("resume-secret"))
    }

    @Test
    fun serverPendingRetryQueriesStatusWithoutPresentingOrRetainingProof() = runTest {
        val proof = "provider-proof-secret"
        val api = FakePurchaseApi(
            confirmResult = PurchaseApiResult.Success(
                PurchaseConfirmation(
                    attempt = attempt(status = VitrinaKitPurchaseAttemptStatus.PENDING),
                    pending = true,
                    profile = null,
                ),
            ),
            getResult = PurchaseApiResult.Success(
                attempt(status = VitrinaKitPurchaseAttemptStatus.PENDING),
            ),
        )
        val adapter = FakePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(VitrinaKitProviderProof(proof)),
        )
        val cache = SubscriberCache()
        val coordinator = coordinator(api = api, adapter = adapter, cache = cache)

        assertIs<VitrinaKitPurchaseResult.Pending>(coordinator.purchase(trustedScope(), product()))
        val retried = coordinator.purchase(trustedScope(), product())

        assertIs<VitrinaKitPurchaseResult.Pending>(retried)
        assertEquals(1, api.startIdempotencyKeys.size)
        assertEquals(1, api.getCount)
        assertEquals(1, adapter.presentCount)
        assertEquals(1, api.confirmCount)
        assertFalse(cache.resume(cacheKey()).toString().contains(proof))
    }

    @Test
    fun retryableConfirmationFailurePersistsAttemptAndRecoversWithoutPresentingAgain() = runTest {
        val api = FakePurchaseApi(
            confirmResult = PurchaseApiResult.Failure(
                VitrinaKitPurchaseError(
                    code = VitrinaKitPurchaseErrorCode.NETWORK_ERROR,
                    message = "Network request failed.",
                    retryable = true,
                    supportReference = null,
                ),
            ),
            getResult = PurchaseApiResult.Success(
                attempt(status = VitrinaKitPurchaseAttemptStatus.SUCCEEDED),
            ),
        )
        val adapter = FakePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(VitrinaKitProviderProof("proof")),
        )
        val coordinator = coordinator(api = api, adapter = adapter)

        assertEquals(
            VitrinaKitPurchaseErrorCode.NETWORK_ERROR,
            assertIs<VitrinaKitPurchaseResult.Failure>(
                coordinator.purchase(scope = trustedScope(), product = product()),
            ).error.code,
        )
        assertIs<VitrinaKitPurchaseResult.Success>(
            coordinator.purchase(scope = trustedScope(), product = product()),
        )

        assertEquals(1, api.startIdempotencyKeys.size)
        assertEquals(1, adapter.presentCount)
        assertEquals(1, api.confirmCount)
        assertEquals(1, api.getCount)
    }

    @Test
    fun retryableConfirmationThatDidNotReachServerReobtainsProofForTheSameAttempt() = runTest {
        val api = FakePurchaseApi(
            confirmResult = PurchaseApiResult.Failure(
                VitrinaKitPurchaseError(
                    code = VitrinaKitPurchaseErrorCode.NETWORK_ERROR,
                    message = "Network request failed.",
                    retryable = true,
                    supportReference = null,
                ),
            ),
            getResult = PurchaseApiResult.Success(
                attempt(status = VitrinaKitPurchaseAttemptStatus.PROVIDER_READY),
            ),
        )
        val adapter = FakePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(VitrinaKitProviderProof("proof")),
        )
        val coordinator = coordinator(api = api, adapter = adapter)

        assertIs<VitrinaKitPurchaseResult.Failure>(
            coordinator.purchase(scope = trustedScope(), product = product()),
        )
        api.confirmResult = PurchaseApiResult.Success(
            PurchaseConfirmation(
                attempt = attempt(status = VitrinaKitPurchaseAttemptStatus.SUCCEEDED),
                pending = false,
                profile = profile(hasAccess = true),
            ),
        )

        assertIs<VitrinaKitPurchaseResult.Success>(
            coordinator.purchase(scope = trustedScope(), product = product()),
        )
        assertEquals(listOf("start-1"), api.startIdempotencyKeys)
        assertEquals(2, adapter.presentCount)
        assertEquals(2, api.confirmCount)
        assertEquals(1, api.getCount)
    }

    @Test
    fun adapterFailureMessageCannotExposeAdapterControlledSecrets() = runTest {
        val secret = "provider-proof-secret"
        val coordinator = coordinator(
            api = FakePurchaseApi(),
            adapter = FakePurchaseAdapter(
                presentResult = VitrinaKitAdapterPurchaseResult.Failure(
                    VitrinaKitAdapterError("Adapter failed with $secret"),
                ),
            ),
        )

        val result = assertIs<VitrinaKitPurchaseResult.Failure>(
            coordinator.purchase(scope = trustedScope(), product = product()),
        )

        assertEquals(VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE, result.error.code)
        assertFalse(result.error.message.contains(secret))
    }

    @Test
    fun successfulAndServerPendingConfirmNotifyAdapterThatProofWasAccepted() = runTest {
        val proof = VitrinaKitProviderProof("proof")
        val adapter = FakePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(proof),
        )

        assertIs<VitrinaKitPurchaseResult.Success>(
            coordinator(api = FakePurchaseApi(), adapter = adapter).purchase(trustedScope(), product()),
        )

        val pendingProof = VitrinaKitProviderProof("pending-proof")
        adapter.presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(pendingProof)
        val pendingApi = FakePurchaseApi(
            confirmResult = PurchaseApiResult.Success(
                PurchaseConfirmation(
                    attempt = attempt(status = VitrinaKitPurchaseAttemptStatus.PENDING),
                    pending = true,
                    profile = null,
                ),
            ),
        )
        assertIs<VitrinaKitPurchaseResult.Pending>(
            coordinator(api = pendingApi, adapter = adapter).purchase(trustedScope(), product()),
        )

        assertEquals(listOf(proof, pendingProof), adapter.acceptedProofs)
    }

    @Test
    fun failedConfirmDoesNotNotifyAdapterThatProofWasAccepted() = runTest {
        val proof = VitrinaKitProviderProof("proof")
        val adapter = FakePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(proof),
        )
        val api = FakePurchaseApi(
            confirmResult = PurchaseApiResult.Failure(
                purchaseError(VitrinaKitPurchaseErrorCode.NETWORK_ERROR),
            ),
        )

        assertIs<VitrinaKitPurchaseResult.Failure>(
            coordinator(api = api, adapter = adapter).purchase(trustedScope(), product()),
        )

        assertTrue(adapter.acceptedProofs.isEmpty())
    }

    @Test
    fun adapterFailurePreservesRetryabilityWithoutExposingAdapterMessage() = runTest {
        val coordinator = coordinator(
            api = FakePurchaseApi(),
            adapter = FakePurchaseAdapter(
                presentResult = VitrinaKitAdapterPurchaseResult.Failure(
                    VitrinaKitAdapterError(
                        message = "Provider debug payload must stay private.",
                        retryable = true,
                        supportReference = "GPB--1",
                    ),
                ),
            ),
        )

        val result = assertIs<VitrinaKitPurchaseResult.Failure>(
            coordinator.purchase(scope = trustedScope(), product = product()),
        )

        assertTrue(result.error.retryable)
        assertEquals("GPB--1", result.error.supportReference)
        assertFalse(result.error.message.contains("debug payload"))
    }

    @Test
    fun adapterMismatchFailsBeforePresentation() = runTest {
        val api = FakePurchaseApi(
            startResult = PurchaseApiResult.Success(
                attempt(capability = VitrinaKitPurchaseCapability.RUSTORE),
            ),
        )
        val adapter = FakePurchaseAdapter(capability = VitrinaKitPurchaseCapability.GOOGLE_PLAY)
        val coordinator = coordinator(api = api, adapter = adapter)

        val result = coordinator.purchase(scope = trustedScope(), product = product())

        assertEquals(
            VitrinaKitPurchaseErrorCode.PROVIDER_NOT_SUPPORTED_BY_BUILD,
            assertIs<VitrinaKitPurchaseResult.Failure>(result).error.code,
        )
        assertEquals(0, adapter.presentCount)

        val retried = coordinator.purchase(scope = trustedScope(), product = product())
        assertEquals(
            VitrinaKitPurchaseErrorCode.PROVIDER_NOT_SUPPORTED_BY_BUILD,
            assertIs<VitrinaKitPurchaseResult.Failure>(retried).error.code,
        )
        assertEquals(1, api.startIdempotencyKeys.size)
    }

    @Test
    fun cancellationSendsNoFakeProofAndReleasesGuard() = runTest {
        val api = FakePurchaseApi()
        val adapter = FakePurchaseAdapter(presentResult = VitrinaKitAdapterPurchaseResult.Cancelled)
        val cache = SubscriberCache()
        val coordinator = coordinator(api = api, adapter = adapter, cache = cache)

        assertIs<VitrinaKitPurchaseResult.Cancelled>(coordinator.purchase(trustedScope(), product()))
        assertIs<VitrinaKitPurchaseResult.Cancelled>(coordinator.purchase(trustedScope(), product()))
        assertEquals(0, api.confirmCount)
        assertEquals(2, adapter.presentCount)
        assertEquals(listOf("start-1"), api.startIdempotencyKeys)
        assertEquals(1, api.getCount)
        assertEquals("attempt-1", cache.resume(cacheKey())?.attemptReference)
        assertEquals("start-1", cache.resume(cacheKey())?.startIdempotencyKey)
    }

    @Test
    fun adapterFailureRetainsTheOpenAttemptForSafeSameAttemptRecovery() = runTest {
        val api = FakePurchaseApi()
        val adapter = FakePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.Failure(
                VitrinaKitAdapterError("Presentation failed."),
            ),
        )
        val cache = SubscriberCache()
        val coordinator = coordinator(api = api, adapter = adapter, cache = cache)

        assertIs<VitrinaKitPurchaseResult.Failure>(coordinator.purchase(trustedScope(), product()))
        assertIs<VitrinaKitPurchaseResult.Failure>(coordinator.purchase(trustedScope(), product()))

        assertEquals(listOf("start-1"), api.startIdempotencyKeys)
        assertEquals(1, api.getCount)
        assertEquals(2, adapter.presentCount)
        assertEquals("attempt-1", cache.resume(cacheKey())?.attemptReference)
        assertEquals("start-1", cache.resume(cacheKey())?.startIdempotencyKey)
    }

    @Test
    fun concurrentPurchaseCannotPresentTwiceAndGuardReleasesAfterFailure() = runTest {
        val presentationStarted = CompletableDeferred<Unit>()
        val finishPresentation = CompletableDeferred<Unit>()
        val adapter = FakePurchaseAdapter(
            onPresent = {
                presentationStarted.complete(Unit)
                finishPresentation.await()
                VitrinaKitAdapterPurchaseResult.Failure(
                    VitrinaKitAdapterError("Presentation failed."),
                )
            },
        )
        val coordinator = coordinator(api = FakePurchaseApi(), adapter = adapter)

        val first = async { coordinator.purchase(trustedScope(), product()) }
        presentationStarted.await()
        val concurrent = coordinator.purchase(trustedScope(), product())
        finishPresentation.complete(Unit)
        assertIs<VitrinaKitPurchaseResult.Failure>(first.await())
        assertEquals(
            VitrinaKitPurchaseErrorCode.PURCHASE_IN_PROGRESS,
            assertIs<VitrinaKitPurchaseResult.Failure>(concurrent).error.code,
        )

        adapter.onPresent = { VitrinaKitAdapterPurchaseResult.Cancelled }
        assertIs<VitrinaKitPurchaseResult.Cancelled>(coordinator.purchase(trustedScope(), product()))
        assertEquals(2, adapter.presentCount)
    }

    @Test
    fun coroutineCancellationPropagatesAndReleasesGuard() = runTest {
        val adapter = FakePurchaseAdapter(
            onPresent = { throw CancellationException("cancel test scope") },
        )
        val coordinator = coordinator(api = FakePurchaseApi(), adapter = adapter)

        assertFailsWith<CancellationException> {
            coordinator.purchase(trustedScope(), product())
        }

        adapter.onPresent = { VitrinaKitAdapterPurchaseResult.Cancelled }
        assertIs<VitrinaKitPurchaseResult.Cancelled>(coordinator.purchase(trustedScope(), product()))
    }

    @Test
    fun identityClearDuringPresentationPreventsConfirmationAndLateCacheWrites() = runTest {
        val presentationStarted = CompletableDeferred<Unit>()
        val finishPresentation = CompletableDeferred<Unit>()
        val cache = SubscriberCache()
        val api = FakePurchaseApi()
        val adapter = FakePurchaseAdapter(
            onPresent = {
                presentationStarted.complete(Unit)
                finishPresentation.await()
                VitrinaKitAdapterPurchaseResult.ProofReady(VitrinaKitProviderProof("proof"))
            },
        )
        val coordinator = coordinator(api = api, adapter = adapter, cache = cache)

        val purchase = async { coordinator.purchase(trustedScope(), product()) }
        presentationStarted.await()
        cache.clearAll()
        finishPresentation.complete(Unit)

        assertEquals(
            VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
            assertIs<VitrinaKitPurchaseResult.Failure>(purchase.await()).error.code,
        )
        assertEquals(0, api.confirmCount)
        assertEquals(null, cache.profile(cacheKey()))
        assertEquals(null, cache.resume(cacheKey()))
    }

    @Test
    fun staleCapturedIdentityCannotStartPurchaseOrRestoreAfterGenerationChanges() = runTest {
        val cache = SubscriberCache()
        val api = FakePurchaseApi()
        val adapter = FakePurchaseAdapter()
        val coordinator = coordinator(api = api, adapter = adapter, cache = cache)
        val purchaseGenerationCaptured = CompletableDeferred<Long>()
        val enterPurchase = CompletableDeferred<Unit>()
        val purchase = async {
            val captured = cache.generation
            purchaseGenerationCaptured.complete(captured)
            enterPurchase.await()
            coordinator.purchase(
                scope = trustedScope(),
                placementId = "main",
                product = product(),
                expectedCacheGeneration = captured,
            )
        }
        purchaseGenerationCaptured.await()
        cache.clearAll()
        enterPurchase.complete(Unit)

        assertEquals(
            VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
            assertIs<VitrinaKitPurchaseResult.Failure>(purchase.await()).error.code,
        )
        assertEquals(emptyList(), api.startIdempotencyKeys)
        assertEquals(0, adapter.presentCount)

        val restoreGenerationCaptured = CompletableDeferred<Long>()
        val enterRestore = CompletableDeferred<Unit>()
        val restore = async {
            val captured = cache.generation
            restoreGenerationCaptured.complete(captured)
            enterRestore.await()
            coordinator.restore(scope = trustedScope(), expectedCacheGeneration = captured)
        }
        restoreGenerationCaptured.await()
        cache.clearAll()
        enterRestore.complete(Unit)

        assertEquals(
            VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
            assertIs<VitrinaKitRestoreResult.Failure>(restore.await()).error.code,
        )
        assertEquals(0, adapter.restoreQueryCount)
        assertEquals(0, api.restoreCount)
    }

    @Test
    fun restoreMapsPurchasesNoPurchasesAndOwnershipFailure() = runTest {
        val adapter = FakePurchaseAdapter(
            restoreResult = listOf(
                VitrinaKitRestorablePurchase(
                    providerProductId = "premium.subscription",
                    proof = VitrinaKitProviderProof("restore-proof"),
                ),
            ),
        )
        val api = FakePurchaseApi(
            restoreApiResult = PurchaseApiResult.Success(
                PurchaseRestoreResponse(
                    purchases = listOf(
                        VitrinaKitRestoredPurchase(
                            purchaseReference = "restored-1",
                            status = VitrinaKitPurchaseAttemptStatus.SUCCEEDED,
                            replayed = false,
                        ),
                    ),
                    profile = profile(hasAccess = true),
                ),
            ),
        )
        val restored = coordinator(api = api, adapter = adapter).restore(trustedScope())
        assertEquals(1, assertIs<VitrinaKitRestoreResult.Success>(restored).purchases.size)
        assertFalse(restored.toString().contains("restore-proof"))

        adapter.restoreResult = emptyList()
        api.restoreApiResult = PurchaseApiResult.Success(
            PurchaseRestoreResponse(purchases = emptyList(), profile = profile(hasAccess = true)),
        )
        assertIs<VitrinaKitRestoreResult.NoPurchases>(coordinator(api = api, adapter = adapter).restore(trustedScope()))

        api.restoreApiResult = PurchaseApiResult.Failure(
            purchaseError(VitrinaKitPurchaseErrorCode.PURCHASE_OWNED_BY_DIFFERENT_USER),
        )
        val failure = coordinator(api = api, adapter = adapter).restore(trustedScope())
        assertEquals(
            VitrinaKitPurchaseErrorCode.PURCHASE_OWNED_BY_DIFFERENT_USER,
            assertIs<VitrinaKitRestoreResult.Failure>(failure).error.code,
        )
    }

    @Test
    fun restorePreservesTypedAdapterFailureWithoutExposingProviderText() = runTest {
        val adapter = FakePurchaseAdapter(
            restoreFailure = VitrinaKitPurchaseAdapterException(
                VitrinaKitAdapterError(
                    message = "Provider debug payload.",
                    retryable = true,
                    supportReference = "GPB--1",
                ),
            ),
        )

        val failure = assertIs<VitrinaKitRestoreResult.Failure>(
            coordinator(api = FakePurchaseApi(), adapter = adapter).restore(trustedScope()),
        )

        assertEquals(VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE, failure.error.code)
        assertTrue(failure.error.retryable)
        assertEquals("GPB--1", failure.error.supportReference)
        assertFalse(failure.error.message.contains("debug payload"))
    }

    @Test
    fun successfulRestoreNotifiesAdapterForEveryAcceptedProof() = runTest {
        val firstProof = VitrinaKitProviderProof("first")
        val secondProof = VitrinaKitProviderProof("second")
        val adapter = FakePurchaseAdapter(
            restoreResult = listOf(
                VitrinaKitRestorablePurchase("one", firstProof),
                VitrinaKitRestorablePurchase("two", secondProof),
            ),
        )

        assertIs<VitrinaKitRestoreResult.NoPurchases>(
            coordinator(api = FakePurchaseApi(), adapter = adapter).restore(trustedScope()),
        )

        assertEquals(listOf(firstProof, secondProof), adapter.acceptedProofs)
    }

    @Test
    fun terminalAttemptsUseDistinctIdempotencyKeys() = runTest {
        val api = FakePurchaseApi(
            confirmResult = PurchaseApiResult.Success(
                PurchaseConfirmation(
                    attempt = attempt(status = VitrinaKitPurchaseAttemptStatus.SUCCEEDED),
                    pending = false,
                    profile = profile(hasAccess = true),
                ),
            ),
        )
        val adapter = FakePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(VitrinaKitProviderProof("proof")),
        )
        val keys = ArrayDeque(listOf("start-a", "confirm-a", "start-b", "confirm-b"))
        val coordinator = PurchaseCoordinator(
            adapter = adapter,
            api = api,
            cache = SubscriberCache(),
            idempotencyKey = { keys.removeFirst() },
        )

        coordinator.purchase(trustedScope(), product())
        coordinator.purchase(trustedScope(), product())

        assertNotEquals(api.startIdempotencyKeys[0], api.startIdempotencyKeys[1])
    }
}

@OptIn(VitrinaKitPurchaseAdapterApi::class)
private class FakePurchaseAdapter(
    override val capability: VitrinaKitPurchaseCapability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
    var presentResult: VitrinaKitAdapterPurchaseResult = VitrinaKitAdapterPurchaseResult.Cancelled,
    var resumeResult: VitrinaKitAdapterPurchaseResult = VitrinaKitAdapterPurchaseResult.Cancelled,
    var restoreResult: List<VitrinaKitRestorablePurchase> = emptyList(),
    var restoreFailure: Throwable? = null,
    var onPresent: (suspend () -> VitrinaKitAdapterPurchaseResult)? = null,
) : VitrinaKitPurchaseAdapter {
    var presentCount = 0
    var resumeCount = 0
    var closeCount = 0
    var restoreQueryCount = 0
    val acceptedProofs = mutableListOf<VitrinaKitProviderProof>()

    override suspend fun present(instruction: VitrinaKitPurchaseInstruction): VitrinaKitAdapterPurchaseResult {
        presentCount += 1
        return onPresent?.invoke() ?: presentResult
    }

    override suspend fun queryRestorablePurchases(): List<VitrinaKitRestorablePurchase> {
        restoreQueryCount += 1
        restoreFailure?.let { failure -> throw failure }
        return restoreResult
    }

    override suspend fun resume(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData,
    ): VitrinaKitAdapterPurchaseResult {
        resumeCount += 1
        return resumeResult
    }

    override fun onProofAccepted(proof: VitrinaKitProviderProof) {
        acceptedProofs += proof
    }

    override fun close() {
        closeCount += 1
    }
}

private class FakePurchaseApi(
    var startResult: PurchaseApiResult<PurchaseAttempt> = PurchaseApiResult.Success(attempt()),
    var confirmResult: PurchaseApiResult<PurchaseConfirmation> = PurchaseApiResult.Success(
        PurchaseConfirmation(
            attempt = attempt(status = VitrinaKitPurchaseAttemptStatus.SUCCEEDED),
            pending = false,
            profile = profile(hasAccess = true),
        ),
    ),
    var restoreApiResult: PurchaseApiResult<PurchaseRestoreResponse> = PurchaseApiResult.Success(
        PurchaseRestoreResponse(purchases = emptyList(), profile = profile(hasAccess = false)),
    ),
    var getResult: PurchaseApiResult<PurchaseAttempt> = startResult,
) : PurchaseApi {
    val startIdempotencyKeys = mutableListOf<String>()
    var confirmCount = 0
    var getCount = 0
    var restoreCount = 0
    private var proofSnapshot = ""

    override suspend fun startPurchase(
        scope: SubscriberScope,
        placementId: String,
        productReference: String,
        capability: VitrinaKitPurchaseCapability,
        idempotencyKey: String,
    ): PurchaseApiResult<PurchaseAttempt> {
        startIdempotencyKeys += idempotencyKey
        return startResult
    }

    override suspend fun confirmPurchase(
        scope: SubscriberScope,
        attemptReference: String,
        idempotencyKey: String,
        proof: VitrinaKitProviderProof,
    ): PurchaseApiResult<PurchaseConfirmation> {
        confirmCount += 1
        proofSnapshot = proof.toString()
        return confirmResult
    }

    override suspend fun getPurchase(
        scope: SubscriberScope,
        attemptReference: String,
    ): PurchaseApiResult<PurchaseAttempt> {
        getCount += 1
        return getResult
    }

    override suspend fun refreshProfile(scope: SubscriberScope): PurchaseApiResult<SubscriberState> =
        PurchaseApiResult.Success(profile(hasAccess = true))

    override suspend fun restorePurchases(
        scope: SubscriberScope,
        purchases: List<VitrinaKitRestorablePurchase>,
        capability: VitrinaKitPurchaseCapability,
    ): PurchaseApiResult<PurchaseRestoreResponse> {
        restoreCount += 1
        return restoreApiResult
    }

    fun confirmedProofText(): String = proofSnapshot
}

private fun coordinator(
    api: PurchaseApi,
    adapter: VitrinaKitPurchaseAdapter,
    cache: SubscriberCache = SubscriberCache(),
): PurchaseCoordinator = PurchaseCoordinator(
    adapter = adapter,
    api = api,
    cache = cache,
    idempotencyKey = { "start-1" },
)

private suspend fun PurchaseCoordinator.purchase(
    scope: SubscriberScope,
    product: PaywallProduct,
): VitrinaKitPurchaseResult = purchase(
    scope = scope,
    placementId = "main",
    product = product,
    expectedCacheGeneration = 0,
)

private suspend fun PurchaseCoordinator.restore(scope: SubscriberScope): VitrinaKitRestoreResult =
    restore(scope = scope, expectedCacheGeneration = 0)

private fun trustedScope(): SubscriberScope = SubscriberScope(
    cacheKey = cacheKey(),
    sessionToken = "opaque-session",
)

private fun cacheKey(): SubscriberCacheKey = SubscriberCacheKey(
    environment = "production",
    subscriberReference = "subscriber-1",
)

private fun product(): PaywallProduct = PaywallProduct(
    productId = "product-1",
    productKey = "premium_monthly",
    productName = "Monthly",
    planId = "plan-1",
    planKey = "premium",
    planName = "Premium",
    priceId = "price-1",
    amountMinor = 19900,
    currency = "RUB",
    intervalUnit = BillingIntervalUnit.MONTH,
    intervalCount = 1,
    highlighted = true,
    sortOrder = 10,
    entitlements = listOf(Entitlement(key = "full_access", name = "Full access")),
)

private fun attempt(
    status: VitrinaKitPurchaseAttemptStatus = VitrinaKitPurchaseAttemptStatus.PROVIDER_READY,
    capability: VitrinaKitPurchaseCapability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
): PurchaseAttempt = PurchaseAttempt(
    reference = "attempt-1",
    status = status,
    reason = null,
    expiresAt = "2026-08-06T12:00:00Z",
    capability = capability,
    instruction = VitrinaKitPurchaseInstruction(
        attemptReference = "attempt-1",
        productId = "store-product",
        priceId = "store-offer",
        packageName = "ru.example.app",
        accountBinding = "opaque-binding",
        expiresAt = "2026-08-06T12:00:00Z",
    ),
)

private fun profile(hasAccess: Boolean): SubscriberState = SubscriberState(
    externalUserId = "user-1",
    hasAccess = hasAccess,
    entitlements = listOf(
        SubscriberEntitlementState(
            key = "premium",
            status = if (hasAccess) SubscriptionStatus.ACTIVE else SubscriptionStatus.EXPIRED,
            hasAccess = hasAccess,
            source = EntitlementSource.GOOGLE,
            autoRenewEnabled = hasAccess,
        ),
    ),
)

private fun purchaseError(code: VitrinaKitPurchaseErrorCode): VitrinaKitPurchaseError =
    VitrinaKitPurchaseError(
        code = code,
        message = "Purchase failed.",
        retryable = false,
        supportReference = "request-1",
    )
