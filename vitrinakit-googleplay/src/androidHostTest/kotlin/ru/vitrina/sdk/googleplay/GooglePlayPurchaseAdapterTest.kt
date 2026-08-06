@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class,
)

package ru.vitrina.sdk.googleplay

import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import ru.vitrina.sdk.purchase.VitrinaKitAdapterPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseInstruction
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResumeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GooglePlayPurchaseAdapterTest {
    @Test
    fun backgroundCallerLooksUpActivityAndLaunchesOnConfiguredMainContext() = runTest {
        val mainExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "billing-main-test")
        }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        val activityLookupThreads = Channel<String>(capacity = Channel.UNLIMITED)
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        val adapter = GooglePlayPurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = {
                activityLookupThreads.trySend(Thread.currentThread().name)
                BillingActivityHandle(ActivityMarker)
            },
            restoreReferenceResolver = resolver(),
            gatewayFactory = { gateway },
            launchDispatcher = mainDispatcher,
        )

        try {
            val cancelled = async(Dispatchers.Default) { adapter.present(instruction()) }
            assertTrue(activityLookupThreads.receive().startsWith("billing-main-test"))
            assertTrue(gateway.launchThreads.receive().startsWith("billing-main-test"))
            cancelled.cancelAndJoin()

            val completed = async(Dispatchers.Default) { adapter.present(instruction()) }
            assertTrue(activityLookupThreads.receive().startsWith("billing-main-test"))
            assertTrue(gateway.launchThreads.receive().startsWith("billing-main-test"))
            gateway.emit(purchased(TOKEN_ONE))

            assertEquals(
                TOKEN_ONE,
                proofValue(assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(completed.await())),
            )
            assertEquals(2, gateway.launches.size)
        } finally {
            adapter.close()
            mainDispatcher.close()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun cancellationBeforeMainLaunchClearsWaiterWithoutLaunchingUi() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        val launchDispatcher = HoldingDispatcher()
        val adapter = GooglePlayPurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = { BillingActivityHandle(ActivityMarker) },
            restoreReferenceResolver = resolver(),
            gatewayFactory = { gateway },
            launchDispatcher = launchDispatcher,
        )
        val purchase = async(Dispatchers.Default) { adapter.present(instruction()) }
        launchDispatcher.awaitDispatch()

        purchase.cancel()
        launchDispatcher.runAll()
        purchase.join()

        assertFalse(hasActiveWaiter(adapter))
        assertTrue(gateway.launches.isEmpty())
    }

    @Test
    fun purchaseQueriesFreshDetailsAndSelectsOnlyBasePlanOffer() = runTest {
        val gateway = FakeBillingGateway(
            products = listOf(
                BillingProduct(
                    productId = PRODUCT_ID,
                    offers = listOf(
                        BillingOffer(BASE_PLAN_ID, "intro-offer", "promo-token"),
                        BillingOffer(BASE_PLAN_ID, null, "base-plan-token"),
                    ),
                ),
            ),
        )
        val adapter = adapter(gateway)

        val first = async { adapter.present(instruction()) }
        runCurrent()
        gateway.emit(purchased(TOKEN_ONE))
        assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(first.await())

        val second = async { adapter.present(instruction()) }
        runCurrent()
        gateway.emit(purchased(TOKEN_TWO))
        assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(second.await())

        assertEquals(listOf(PRODUCT_ID, PRODUCT_ID), gateway.productQueries)
        assertEquals(listOf("base-plan-token", "base-plan-token"), gateway.launches.map { it.offerToken })
        assertEquals(listOf(ACCOUNT_BINDING, ACCOUNT_BINDING), gateway.launches.map { it.obfuscatedAccountId })
        assertEquals(listOf(ACCOUNT_BINDING, ACCOUNT_BINDING), gateway.launches.map { it.obfuscatedProfileId })
        assertFalse(gateway.launches.first().toString().contains(ACCOUNT_BINDING))
        assertFalse(purchasedPurchase(TOKEN_ONE).toString().contains(TOKEN_ONE))
    }

    @Test
    fun promotionalOnlyOrAmbiguousBasePlanIsRejectedWithoutLaunch() = runTest {
        val promoOnly = FakeBillingGateway(
            products = listOf(
                BillingProduct(PRODUCT_ID, listOf(BillingOffer(BASE_PLAN_ID, "intro", "promo"))),
            ),
        )
        val ambiguous = FakeBillingGateway(
            products = listOf(
                BillingProduct(
                    PRODUCT_ID,
                    listOf(
                        BillingOffer(BASE_PLAN_ID, null, "one"),
                        BillingOffer(BASE_PLAN_ID, null, "two"),
                    ),
                ),
            ),
        )

        val promoFailure = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(
            adapter(promoOnly).present(instruction()),
        )
        val ambiguousFailure = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(
            adapter(ambiguous).present(instruction()),
        )

        assertFalse(promoFailure.error.retryable)
        assertFalse(ambiguousFailure.error.retryable)
        assertTrue(promoOnly.launches.isEmpty())
        assertTrue(ambiguous.launches.isEmpty())
    }

    @Test
    fun packageAndProductMustMatchExactly() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))

        val packageFailure = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(
            adapter(gateway).present(instruction(packageName = "example.wrong")),
        )
        val productFailure = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(
            adapter(gateway).present(instruction(productId = "subscription.WRONG")),
        )

        assertFalse(packageFailure.error.retryable)
        assertFalse(productFailure.error.retryable)
        assertTrue(gateway.launches.isEmpty())
    }

    @Test
    fun launchAndCallbackCodesMapWithoutProviderPayloads() = runTest {
        val cancelledLaunch = FakeBillingGateway(
            products = listOf(baseProduct()),
            launchCode = BillingResponseCode.USER_CANCELED,
        )
        assertIs<VitrinaKitAdapterPurchaseResult.Cancelled>(
            adapter(cancelledLaunch).present(instruction()),
        )

        val disconnected = FakeBillingGateway(
            products = listOf(baseProduct()),
            launchCode = BillingResponseCode.SERVICE_DISCONNECTED,
        )
        val disconnectedFailure = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(
            adapter(disconnected).present(instruction()),
        )
        assertTrue(disconnectedFailure.error.retryable)
        assertFalse(disconnectedFailure.toString().contains("debug"))

        val callbackGateway = FakeBillingGateway(products = listOf(baseProduct()))
        val callbackAdapter = adapter(callbackGateway)
        val pending = async { callbackAdapter.present(instruction()) }
        runCurrent()
        callbackGateway.emit(
            BillingPurchaseUpdate(
                responseCode = BillingResponseCode.OK,
                purchases = listOf(pending(TOKEN_ONE)),
            ),
        )
        val pendingResult = assertIs<VitrinaKitAdapterPurchaseResult.Pending>(pending.await())
        assertFalse(pendingResult.resumeData.value.contains(TOKEN_ONE))
        assertEquals(ATTEMPT_REFERENCE, pendingResult.resumeData.value)
    }

    @Test
    fun itemAlreadyOwnedRecoversQueriedProofInsteadOfFailingLaunch() = runTest {
        lateinit var gateway: FakeBillingGateway
        gateway = FakeBillingGateway(
            products = listOf(baseProduct()),
            launchCode = BillingResponseCode.ITEM_ALREADY_OWNED,
            afterLaunch = {
                gateway.purchases = listOf(purchasedPurchase(TOKEN_ONE))
            },
        )

        val recovered = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(
            adapter(gateway).present(instruction()),
        )

        assertEquals(TOKEN_ONE, proofValue(recovered))
        assertEquals(1, gateway.launches.size)
    }

    @Test
    fun callbackCancellationAndDisconnectMapExactly() = runTest {
        val cancelledGateway = FakeBillingGateway(products = listOf(baseProduct()))
        val cancelledPurchase = async { adapter(cancelledGateway).present(instruction()) }
        runCurrent()
        cancelledGateway.emit(
            BillingPurchaseUpdate(BillingResponseCode.USER_CANCELED, emptyList()),
        )
        assertIs<VitrinaKitAdapterPurchaseResult.Cancelled>(cancelledPurchase.await())

        val disconnectedGateway = FakeBillingGateway(products = listOf(baseProduct()))
        val disconnectedPurchase = async { adapter(disconnectedGateway).present(instruction()) }
        runCurrent()
        disconnectedGateway.emit(
            BillingPurchaseUpdate(BillingResponseCode.SERVICE_DISCONNECTED, emptyList()),
        )
        val failure = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(disconnectedPurchase.await())
        assertTrue(failure.error.retryable)
        assertEquals("GPB--1", failure.error.supportReference)
    }

    @Test
    fun okCallbackWithoutMatchingPurchaseFailsInsteadOfSuspending() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        val purchase = async { adapter(gateway).present(instruction()) }
        runCurrent()

        gateway.emit(BillingPurchaseUpdate(BillingResponseCode.OK, emptyList()))

        advanceTimeBy(49L)
        assertFalse(purchase.isCompleted)
        advanceTimeBy(1L)
        runCurrent()
        val result = withTimeout(100L) { purchase.await() }
        assertTrue(assertIs<VitrinaKitAdapterPurchaseResult.Failure>(result).error.retryable)
    }

    @Test
    fun normalUiWaitDoesNotTimeoutWithoutAnEmptyOkCallback() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        val purchase = async { adapter(gateway).present(instruction()) }
        runCurrent()

        advanceTimeBy(51L)
        runCurrent()

        assertFalse(purchase.isCompleted)
        purchase.cancelAndJoin()
    }

    @Test
    fun lateNonmatchingOkCallbackCannotCompleteNewWaiter() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        val adapter = adapter(gateway)
        val cancelled = async { adapter.present(instruction()) }
        runCurrent()
        cancelled.cancelAndJoin()

        val next = async { adapter.present(instruction()) }
        runCurrent()
        gateway.emit(
            BillingPurchaseUpdate(
                responseCode = BillingResponseCode.OK,
                purchases = listOf(purchasedPurchase("late-token", "different.product")),
            ),
        )
        runCurrent()
        assertFalse(next.isCompleted)

        gateway.emit(purchased(TOKEN_ONE))
        assertEquals(
            TOKEN_ONE,
            proofValue(assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(next.await())),
        )
    }

    @Test
    fun duplicateAndLateCallbacksCannotCompleteAnotherPurchase() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        val adapter = adapter(gateway)
        val cancelled = async { adapter.present(instruction()) }
        runCurrent()
        cancelled.cancelAndJoin()
        gateway.emit(purchased("late-token"))

        val next = async { adapter.present(instruction()) }
        runCurrent()
        gateway.emit(purchased(TOKEN_ONE))
        gateway.emit(purchased(TOKEN_ONE))
        val result = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(next.await())

        assertEquals(TOKEN_ONE, proofValue(result))
        assertFalse(result.toString().contains(TOKEN_ONE))
        assertEquals(2, gateway.launches.size)
    }

    @Test
    fun foregroundQueryResolvesActiveWaiterAndCallbackDuplicateProducesOneProof() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        val adapter = adapter(gateway)
        val purchase = async { adapter.present(instruction()) }
        runCurrent()
        gateway.purchases = listOf(purchasedPurchase(TOKEN_ONE))

        assertEquals(null, adapter.recover(instruction = instruction(), resumeData = null))
        gateway.emit(purchased(TOKEN_ONE))
        val result = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(purchase.await())

        assertEquals(TOKEN_ONE, proofValue(result))
        adapter.onProofAccepted(result.proof)
        assertTrue(adapter.queryRestorablePurchases().isEmpty())
    }

    @Test
    fun callbackCompletedActiveWaiterStillOwnsForegroundQueryProof() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        val adapter = adapter(gateway)
        val purchase = async { adapter.present(instruction()) }
        runCurrent()
        gateway.purchases = listOf(purchasedPurchase(TOKEN_ONE))
        gateway.emit(purchased(TOKEN_ONE))

        assertTrue(hasActiveWaiter(adapter))
        assertEquals(null, adapter.recover(instruction = instruction(), resumeData = null))
        val result = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(purchase.await())

        assertEquals(TOKEN_ONE, proofValue(result))
        assertFalse(hasActiveWaiter(adapter))
    }

    @Test
    fun foregroundRecoveryReturnsAttemptBoundProofWithoutRawPurchaseCache() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        gateway.purchases = listOf(purchasedPurchase(TOKEN_ONE))
        val adapter = adapter(gateway)

        val recovered = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(
            adapter.recover(instruction = instruction(), resumeData = null),
        )
        adapter.onProofAccepted(recovered.proof)

        assertEquals(TOKEN_ONE, proofValue(recovered))
        assertTrue(adapter.queryRestorablePurchases().isEmpty())
    }

    @Test
    fun foregroundRecoveryRetriesFailedQueryAndThrottlesOnlyTheSamePendingAttempt() = runTest {
        var nowMillis = 1_000L
        val gateway = FakeBillingGateway(
            products = listOf(baseProduct()),
            purchaseQueryCodes = ArrayDeque(
                listOf(
                    BillingResponseCode.ERROR,
                    BillingResponseCode.OK,
                    BillingResponseCode.OK,
                ),
            ),
        )
        gateway.purchases = listOf(pendingPurchase(TOKEN_ONE))
        val adapter = GooglePlayPurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = { BillingActivityHandle(ActivityMarker) },
            restoreReferenceResolver = resolver(),
            gatewayFactory = { gateway },
            foregroundQueryIntervalMillis = 5_000L,
            clockMillis = { nowMillis },
            launchDispatcher = Dispatchers.Unconfined,
        )

        assertIs<VitrinaKitAdapterPurchaseResult.Failure>(
            adapter.recover(instruction = instruction(), resumeData = null),
        )
        assertIs<VitrinaKitAdapterPurchaseResult.Pending>(
            adapter.recover(instruction = instruction(), resumeData = null),
        )
        assertEquals(null, adapter.recover(instruction = instruction(), resumeData = null))
        assertEquals(2, gateway.purchaseQueryCount)

        assertIs<VitrinaKitAdapterPurchaseResult.Pending>(
            adapter.recover(
                instruction = instruction(attemptReference = "new-pending-attempt"),
                resumeData = null,
            ),
        )
        assertEquals(3, gateway.purchaseQueryCount)

        nowMillis += 5_000L
        assertIs<VitrinaKitAdapterPurchaseResult.Pending>(
            adapter.recover(instruction = instruction(), resumeData = null),
        )
        assertEquals(4, gateway.purchaseQueryCount)
    }

    @Test
    fun providerTokenCanRetryUntilServerAcceptanceThenIsSuppressed() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        gateway.purchases = listOf(purchasedPurchase(TOKEN_ONE))
        val adapter = adapter(gateway)

        val first = adapter.queryRestorablePurchases().single()
        val retry = adapter.queryRestorablePurchases().single()
        adapter.onProofAccepted(first.proof)
        val afterAcceptance = adapter.queryRestorablePurchases()

        assertEquals(TOKEN_ONE, proofValue(first))
        assertEquals(TOKEN_ONE, proofValue(retry))
        assertTrue(afterAcceptance.isEmpty())
    }

    @Test
    fun acceptanceSuppressesForegroundRecoveryWithoutCachingRawPurchase() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        val adapter = adapter(gateway)
        val purchase = async { adapter.present(instruction()) }
        runCurrent()
        gateway.emit(purchased(TOKEN_ONE))
        val proof = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(purchase.await()).proof

        gateway.purchases = listOf(purchasedPurchase(TOKEN_ONE))
        assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(
            adapter.recover(instruction = instruction(), resumeData = null),
        )
        adapter.onProofAccepted(proof)

        assertTrue(adapter.queryRestorablePurchases().isEmpty())
    }

    @Test
    fun presentRecoversExistingPurchaseBeforeLaunchingNewUi() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        gateway.purchases = listOf(purchasedPurchase(TOKEN_ONE))
        val adapter = adapter(gateway)

        val result = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(
            adapter.present(instruction()),
        )

        assertEquals(TOKEN_ONE, proofValue(result))
        assertTrue(gateway.productQueries.isEmpty())
        assertTrue(gateway.launches.isEmpty())
    }

    @Test
    fun restoreUsesExplicitResolverAndSkipsUnmappedProducts() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        gateway.purchases = listOf(
            purchasedPurchase(TOKEN_ONE, PRODUCT_ID),
            purchasedPurchase(TOKEN_TWO, "unmapped.product"),
        )
        val adapter = adapter(gateway)

        val restored = adapter.queryRestorablePurchases()

        assertEquals(1, restored.size)
        assertEquals(PLACEMENT_ID, restored.single().placementId)
        assertEquals(PRODUCT_REFERENCE, restored.single().productReference)
        assertEquals(TOKEN_ONE, proofValue(restored.single()))
    }

    @Test
    fun resumeQueriesPlayWithoutRelaunchAndNeverStoresTokenInResumeData() = runTest {
        val gateway = FakeBillingGateway(products = listOf(baseProduct()))
        val adapter = adapter(gateway)
        gateway.purchases = listOf(pendingPurchase(TOKEN_ONE))

        val pending = assertIs<VitrinaKitAdapterPurchaseResult.Pending>(
            adapter.resume(instruction(), VitrinaKitPurchaseResumeData(ATTEMPT_REFERENCE)),
        )
        assertEquals(ATTEMPT_REFERENCE, pending.resumeData.value)
        assertFalse(pending.resumeData.value.contains(TOKEN_ONE))
        assertTrue(gateway.launches.isEmpty())

        gateway.purchases = listOf(purchasedPurchase(TOKEN_ONE))
        val proof = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(
            adapter.resume(instruction(), pending.resumeData),
        )
        assertEquals(TOKEN_ONE, proofValue(proof))
        assertTrue(gateway.launches.isEmpty())
    }

    @Test
    fun connectionUsesBoundedBackoffAndCloseDisconnects() = runTest {
        val gateway = FakeBillingGateway(
            products = listOf(baseProduct()),
            connectCodes = ArrayDeque(
                listOf(
                    BillingResponseCode.SERVICE_DISCONNECTED,
                    BillingResponseCode.SERVICE_UNAVAILABLE,
                    BillingResponseCode.OK,
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val connection = BillingClientConnection(
            gateway = gateway,
            retryDelaysMillis = listOf(100L, 200L),
            delayOperation = { value -> delays += value },
        )

        assertEquals(BillingResponseCode.OK, connection.connect())
        assertEquals(listOf(100L, 200L), delays)
        assertEquals(3, gateway.connectCount)

        connection.close()
        assertEquals(1, gateway.closeCount)
    }

    @Test
    fun disconnectedQueryReconnectsAndRetriesOnce() = runTest {
        val gateway = FakeBillingGateway(
            products = listOf(baseProduct()),
            purchaseQueryCodes = ArrayDeque(
                listOf(BillingResponseCode.SERVICE_DISCONNECTED, BillingResponseCode.OK),
            ),
        )
        gateway.purchases = listOf(purchasedPurchase(TOKEN_ONE))
        val connection = BillingClientConnection(gateway = gateway)

        val result = connection.queryPurchases()

        assertEquals(BillingResponseCode.OK, result.responseCode)
        assertEquals(1, result.values.size)
        assertEquals(2, gateway.purchaseQueryCount)
        assertEquals(2, gateway.connectCount)
    }

    @Test
    fun adapterLazilyCreatesFreshBillingClientAfterClose() = runTest {
        val firstGateway = FakeBillingGateway(products = listOf(baseProduct()))
        val secondGateway = FakeBillingGateway(products = listOf(baseProduct()))
        val gateways = ArrayDeque(listOf(firstGateway, secondGateway))
        val adapter = GooglePlayPurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = { BillingActivityHandle(ActivityMarker) },
            restoreReferenceResolver = resolver(),
            gatewayFactory = { gateways.removeFirst() },
            launchDispatcher = Dispatchers.Unconfined,
        )

        adapter.close()
        val purchase = async { adapter.present(instruction()) }
        runCurrent()
        secondGateway.emit(purchased(TOKEN_ONE))

        assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(purchase.await())
        assertEquals(1, firstGateway.closeCount)
        assertEquals(1, secondGateway.launches.size)
    }

    @Test
    fun cancelledConnectPropagatesCancellation() = runTest {
        val gateway = FakeBillingGateway(
            products = listOf(baseProduct()),
            connectCodes = ArrayDeque(listOf(BillingResponseCode.SERVICE_DISCONNECTED)),
        )
        val connection = BillingClientConnection(
            gateway = gateway,
            retryDelaysMillis = listOf(100L, 200L),
        )

        val connecting = async { connection.connect() }
        runCurrent()
        connecting.cancelAndJoin()
        advanceUntilIdle()

        assertTrue(connecting.isCancelled)
        assertEquals(1, gateway.connectCount)
    }

    private fun adapter(gateway: FakeBillingGateway): GooglePlayPurchaseAdapter =
        GooglePlayPurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = { BillingActivityHandle(ActivityMarker) },
            restoreReferenceResolver = resolver(),
            gatewayFactory = { gateway },
            emptyCallbackTimeoutMillis = 50L,
            launchDispatcher = Dispatchers.Unconfined,
        )

    private fun resolver(): GooglePlayRestoreReferenceResolver =
        GooglePlayRestoreReferenceResolver { productId ->
            if (productId == PRODUCT_ID) {
                GooglePlayRestoreResolution.Mapped(
                    placementId = PLACEMENT_ID,
                    productReference = PRODUCT_REFERENCE,
                )
            } else {
                GooglePlayRestoreResolution.Skip
            }
        }

    private fun instruction(
        productId: String = PRODUCT_ID,
        packageName: String = PACKAGE_NAME,
        attemptReference: String = ATTEMPT_REFERENCE,
    ): VitrinaKitPurchaseInstruction = VitrinaKitPurchaseInstruction(
        attemptReference = attemptReference,
        productId = productId,
        priceId = BASE_PLAN_ID,
        packageName = packageName,
        accountBinding = ACCOUNT_BINDING,
        expiresAt = "2026-08-06T12:00:00Z",
    )

    private fun baseProduct(): BillingProduct = BillingProduct(
        productId = PRODUCT_ID,
        offers = listOf(BillingOffer(BASE_PLAN_ID, null, "base-plan-token")),
    )

    private fun purchased(token: String): BillingPurchaseUpdate = BillingPurchaseUpdate(
        responseCode = BillingResponseCode.OK,
        purchases = listOf(purchasedPurchase(token)),
    )

    private fun purchasedPurchase(
        token: String,
        productId: String = PRODUCT_ID,
    ): BillingPurchase = BillingPurchase(
        productIds = listOf(productId),
        purchaseToken = token,
        state = BillingPurchaseState.PURCHASED,
    )

    private fun pendingPurchase(token: String): BillingPurchase = BillingPurchase(
        productIds = listOf(PRODUCT_ID),
        purchaseToken = token,
        state = BillingPurchaseState.PENDING,
    )

    private fun pending(token: String): BillingPurchase = pendingPurchase(token)

    private fun proofValue(result: VitrinaKitAdapterPurchaseResult.ProofReady): String =
        proofValue(result.proof)

    private fun proofValue(purchase: ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase): String =
        proofValue(purchase.proof)

    private fun proofValue(proof: ru.vitrina.sdk.purchase.VitrinaKitProviderProof): String {
        val field = proof.javaClass.getDeclaredField("value")
        field.isAccessible = true
        return field.get(proof) as String
    }

    private fun hasActiveWaiter(adapter: GooglePlayPurchaseAdapter): Boolean {
        val lockField = adapter.javaClass.getDeclaredField("recoveryStateLock")
        lockField.isAccessible = true
        val lock = lockField.get(adapter) ?: error("Recovery state lock is missing.")
        val waiterField = adapter.javaClass.getDeclaredField("activeWaiter")
        waiterField.isAccessible = true
        return synchronized(lock) { waiterField.get(adapter) != null }
    }

    private companion object {
        const val PACKAGE_NAME = "ru.example.application"
        const val PRODUCT_ID = "premium.subscription"
        const val BASE_PLAN_ID = "monthly"
        const val ACCOUNT_BINDING = "opaque-account-binding"
        const val ATTEMPT_REFERENCE = "attempt-reference"
        const val PLACEMENT_ID = "main"
        const val PRODUCT_REFERENCE = "premium-monthly"
        const val TOKEN_ONE = "purchase-token-one"
        const val TOKEN_TWO = "purchase-token-two"
    }
}

private val ActivityMarker = Any()

private class HoldingDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val dispatches = Channel<Unit>(capacity = Channel.UNLIMITED)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks += block
        dispatches.trySend(Unit)
    }

    suspend fun awaitDispatch() {
        dispatches.receive()
    }

    fun runAll() {
        while (true) {
            val task = tasks.poll() ?: return
            task.run()
        }
    }
}

private class FakeBillingGateway(
    private val products: List<BillingProduct>,
    private val launchCode: BillingResponseCode = BillingResponseCode.OK,
    private val connectCodes: ArrayDeque<BillingResponseCode> = ArrayDeque(listOf(BillingResponseCode.OK)),
    private val purchaseQueryCodes: ArrayDeque<BillingResponseCode> =
        ArrayDeque(listOf(BillingResponseCode.OK)),
    private val afterLaunch: () -> Unit = {},
) : GooglePlayBillingGateway {
    var purchases: List<BillingPurchase> = emptyList()
    val productQueries = mutableListOf<String>()
    val launches = mutableListOf<BillingFlowRequest>()
    var connectCount = 0
    var purchaseQueryCount = 0
    var closeCount = 0
    val launchThreads = Channel<String>(capacity = Channel.UNLIMITED)
    private var listener: ((BillingPurchaseUpdate) -> Unit)? = null

    override fun setPurchaseUpdateListener(listener: ((BillingPurchaseUpdate) -> Unit)?) {
        this.listener = listener
    }

    override suspend fun connect(): BillingResponseCode {
        connectCount += 1
        return if (connectCodes.size > 1) connectCodes.removeFirst() else connectCodes.first()
    }

    override suspend fun queryProductDetails(productId: String): BillingQueryResult<BillingProduct> {
        productQueries += productId
        return BillingQueryResult(BillingResponseCode.OK, products)
    }

    override suspend fun queryPurchases(): BillingQueryResult<BillingPurchase> {
        purchaseQueryCount += 1
        val response = if (purchaseQueryCodes.size > 1) {
            purchaseQueryCodes.removeFirst()
        } else {
            purchaseQueryCodes.first()
        }
        return BillingQueryResult(response, purchases)
    }

    override fun launch(activity: BillingActivityHandle, request: BillingFlowRequest): BillingResponseCode {
        assertEquals(ActivityMarker, activity.value)
        launchThreads.trySend(Thread.currentThread().name)
        launches += request
        afterLaunch()
        return launchCode
    }

    override fun close() {
        closeCount += 1
    }

    fun emit(update: BillingPurchaseUpdate) {
        listener?.invoke(update)
    }
}
