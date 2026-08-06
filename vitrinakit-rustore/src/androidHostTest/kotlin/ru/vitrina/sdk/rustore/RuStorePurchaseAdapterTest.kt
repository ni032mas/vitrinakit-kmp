@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class,
)

package ru.vitrina.sdk.rustore

import android.content.Intent
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.vitrina.sdk.purchase.VitrinaKitAdapterPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseInstruction
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResumeData
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterException
import ru.rustore.sdk.pay.model.ProductId
import ru.rustore.sdk.pay.model.PurchaseId
import ru.rustore.sdk.pay.model.RuStorePaymentException

class RuStorePurchaseAdapterTest {
    @Test
    fun purchaseResolvesFreshExactProductAndBindsAccountOnMainContext() = runTest {
        val mainExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "rustore-main-test") }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        val lookupThreads = Channel<String>(capacity = Channel.UNLIMITED)
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val adapter = RuStorePurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = {
                lookupThreads.trySend(Thread.currentThread().name)
                RuStoreActivityHandle(ActivityMarker)
            },
            restoreReferenceResolver = resolver(),
            gatewayFactory = { gateway },
            launchDispatcher = mainDispatcher,
        )

        try {
            val first = async(Dispatchers.Default) { adapter.present(instruction()) }
            assertTrue(lookupThreads.receive().startsWith("rustore-main-test"))
            assertTrue(gateway.purchaseThreads.receive().startsWith("rustore-main-test"))
            gateway.completePurchase(RuStorePurchaseOutcome.Success(PURCHASE_ONE, PRODUCT_ID))
            assertEquals(
                PURCHASE_ONE,
                proofValue(assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(first.await())),
            )

            val second = async(Dispatchers.Default) { adapter.present(instruction()) }
            assertTrue(lookupThreads.receive().startsWith("rustore-main-test"))
            gateway.completePurchase(RuStorePurchaseOutcome.Success(PURCHASE_TWO, PRODUCT_ID))
            assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(second.await())

            assertEquals(listOf(PRODUCT_ID, PRODUCT_ID), gateway.productQueries)
            assertEquals(listOf(ACCOUNT_BINDING, ACCOUNT_BINDING), gateway.requests.map { it.appUserId })
            assertTrue(gateway.requests.all { it.purchaseType == RuStorePurchaseType.ONE_STEP })
            assertFalse(gateway.requests.first().toString().contains(ACCOUNT_BINDING))
        } finally {
            adapter.close()
            mainDispatcher.close()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun cancellationBeforeMainLaunchDoesNotPresentOrRetainActivity() = runTest {
        val dispatcher = HoldingDispatcher()
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val activity = Any()
        val reference = WeakReference(activity)
        val adapter = RuStorePurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = { RuStoreActivityHandle(activity) },
            restoreReferenceResolver = resolver(),
            gatewayFactory = { gateway },
            launchDispatcher = dispatcher,
        )
        val purchase = async(Dispatchers.Default) { adapter.present(instruction()) }
        dispatcher.awaitDispatch()

        purchase.cancel()
        dispatcher.runAll()
        purchase.join()

        assertTrue(gateway.requests.isEmpty())
        assertFalse(hasActiveWaiter(adapter))
        assertTrue(reference.get() === activity)
        assertTrue(adapter.javaClass.declaredFields.none { it.type.name == "android.app.Activity" })
    }

    @Test
    fun packageAndExactProductMismatchFailWithoutPresentation() = runTest {
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val adapter = adapter(gateway)

        val wrongPackage = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(
            adapter.present(instruction(packageName = "example.wrong")),
        )
        gateway.products = listOf(RuStoreProduct("different.product"))
        val wrongProduct = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(adapter.present(instruction()))

        assertFalse(wrongPackage.error.retryable)
        assertFalse(wrongProduct.error.retryable)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun productResolutionPreservesSafeProviderRetryability() = runTest {
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        gateway.productQueryFailure = RuStoreFailureKind.CONFIGURATION

        val failure = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(
            adapter(gateway).present(instruction()),
        )

        assertFalse(failure.error.retryable)
        assertEquals("RSP-CONFIGURATION", failure.error.supportReference)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun successCancellationAndFailuresMapWithoutProviderPayloads() = runTest {
        val successGateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val success = async { adapter(successGateway).present(instruction()) }
        runCurrent()
        successGateway.completePurchase(RuStorePurchaseOutcome.Success(PURCHASE_ONE, PRODUCT_ID))
        val proofReady = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(success.await())
        assertEquals(PURCHASE_ONE, proofValue(proofReady))
        assertFalse(proofReady.toString().contains(PURCHASE_ONE))

        val cancelledGateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val cancelled = async { adapter(cancelledGateway).present(instruction()) }
        runCurrent()
        cancelledGateway.completePurchase(RuStorePurchaseOutcome.Cancelled)
        assertIs<VitrinaKitAdapterPurchaseResult.Cancelled>(cancelled.await())

        val networkGateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val network = async { adapter(networkGateway).present(instruction()) }
        runCurrent()
        networkGateway.completePurchase(RuStorePurchaseOutcome.Failure(RuStoreFailureKind.NETWORK))
        val networkFailure = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(network.await())
        assertTrue(networkFailure.error.retryable)
        assertEquals("RSP-NETWORK", networkFailure.error.supportReference)
        assertFalse(networkFailure.toString().contains("provider-detail"))

        val configGateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val config = async { adapter(configGateway).present(instruction()) }
        runCurrent()
        configGateway.completePurchase(RuStorePurchaseOutcome.Failure(RuStoreFailureKind.CONFIGURATION))
        assertFalse(assertIs<VitrinaKitAdapterPurchaseResult.Failure>(config.await()).error.retryable)
    }

    @Test
    fun opaqueResumeRemainsPendingUntilACompletedPurchaseBecomesVisible() = runTest {
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val adapter = adapter(gateway)

        val pending = assertIs<VitrinaKitAdapterPurchaseResult.Pending>(
            adapter.resume(instruction(), VitrinaKitPurchaseResumeData(ATTEMPT_REFERENCE)),
        )
        assertEquals(ATTEMPT_REFERENCE, pending.resumeData.value)
        assertFalse(pending.resumeData.value.contains(PURCHASE_ONE))

        gateway.purchases = listOf(RuStorePurchase(PURCHASE_ONE, PRODUCT_ID, RuStorePurchaseState.PURCHASED))
        val recovered = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(
            adapter.resume(instruction(), pending.resumeData),
        )
        assertEquals(PURCHASE_ONE, proofValue(recovered))
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun foregroundRecoveryRetriesFailuresThenThrottlesSameAttempt() = runTest {
        var nowMillis = 1_000L
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        gateway.queryFailures.add(RuStoreFailureKind.NETWORK)
        val adapter = adapter(
            gateway = gateway,
            foregroundQueryIntervalMillis = 5_000L,
            clockMillis = { nowMillis },
        )

        assertIs<VitrinaKitAdapterPurchaseResult.Failure>(adapter.recover(instruction(), null))
        assertNull(adapter.recover(instruction(), null))
        assertNull(adapter.recover(instruction(), null))
        assertEquals(2, gateway.purchaseQueryCount)

        assertNull(adapter.recover(instruction(attemptReference = "new-attempt"), null))
        assertEquals(3, gateway.purchaseQueryCount)
        nowMillis += 5_000L
        assertNull(adapter.recover(instruction(), null))
        assertEquals(4, gateway.purchaseQueryCount)
    }

    @Test
    fun prelaunchPresentationOwnsProofWhileItsFirstQueryIsSuspended() = runTest {
        val firstQueryStarted = CompletableDeferred<Unit>()
        val releaseFirstQuery = CompletableDeferred<Unit>()
        val gateway = FakeRuStorePayGateway(
            products = listOf(RuStoreProduct(PRODUCT_ID)),
            beforePurchaseQuery = { count ->
                if (count == 1) {
                    firstQueryStarted.complete(Unit)
                    releaseFirstQuery.await()
                }
            },
        )
        gateway.purchases = listOf(
            RuStorePurchase(PURCHASE_ONE, PRODUCT_ID, RuStorePurchaseState.PURCHASED),
        )
        val adapter = adapter(gateway)
        val presentation = async { adapter.present(instruction()) }
        firstQueryStarted.await()

        val foreground = async { adapter.recover(instruction(), null) }
        runCurrent()
        assertNull(foreground.await())
        releaseFirstQuery.complete(Unit)

        val result = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(presentation.await())
        assertEquals(PURCHASE_ONE, proofValue(result))
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun foregroundQueryAndPurchaseCallbackHandOffProofExactlyOnce() = runTest {
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val adapter = adapter(gateway)
        val presentation = async { adapter.present(instruction()) }
        runCurrent()
        gateway.purchases = listOf(RuStorePurchase(PURCHASE_ONE, PRODUCT_ID, RuStorePurchaseState.PURCHASED))

        assertNull(adapter.recover(instruction(), null))
        gateway.completePurchase(RuStorePurchaseOutcome.Success(PURCHASE_ONE, PRODUCT_ID))
        val result = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(presentation.await())

        assertEquals(PURCHASE_ONE, proofValue(result))
        adapter.onProofAccepted(result.proof)
        assertTrue(adapter.queryRestorablePurchases().isEmpty())
    }

    @Test
    fun suspendedQueryOwnsProofWhenPresentationIsCancelled() = runTest {
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val gateway = FakeRuStorePayGateway(
            products = listOf(RuStoreProduct(PRODUCT_ID)),
            beforePurchaseQuery = { count ->
                if (count == 2) {
                    queryStarted.complete(Unit)
                    releaseQuery.await()
                }
            },
        )
        val adapter = adapter(gateway)
        val presentation = async { adapter.present(instruction()) }
        runCurrent()
        gateway.purchases = listOf(RuStorePurchase(PURCHASE_ONE, PRODUCT_ID, RuStorePurchaseState.PURCHASED))
        val foreground = async { adapter.recover(instruction(), null) }
        queryStarted.await()

        presentation.cancelAndJoin()
        releaseQuery.complete(Unit)

        val result = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(foreground.await())
        assertEquals(PURCHASE_ONE, proofValue(result))
    }

    @Test
    fun closeInvalidatesSuspendedRecoveryWithoutRecreatingGateway() = runTest {
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val gateway = FakeRuStorePayGateway(
            products = listOf(RuStoreProduct(PRODUCT_ID)),
            beforePurchaseQuery = {
                queryStarted.complete(Unit)
                releaseQuery.await()
            },
        )
        gateway.purchases = listOf(
            RuStorePurchase(PURCHASE_ONE, PRODUCT_ID, RuStorePurchaseState.PURCHASED),
        )
        var gatewayCreations = 0
        val adapter = RuStorePurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = { RuStoreActivityHandle(ActivityMarker) },
            restoreReferenceResolver = resolver(),
            gatewayFactory = {
                gatewayCreations += 1
                gateway
            },
            launchDispatcher = Dispatchers.Unconfined,
        )
        val recovery = async { adapter.recover(instruction(), null) }
        queryStarted.await()

        adapter.close()
        releaseQuery.complete(Unit)

        assertNull(recovery.await())
        assertEquals(1, gatewayCreations)
        assertEquals(1, gateway.closeCount)
    }

    @Test
    fun newerPresentationInvalidatesSuspendedRecoveryFromCancelledAttempt() = runTest {
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val gateway = FakeRuStorePayGateway(
            products = listOf(RuStoreProduct(PRODUCT_ID)),
            beforePurchaseQuery = { count ->
                if (count == 2) {
                    queryStarted.complete(Unit)
                    releaseQuery.await()
                }
            },
        )
        val adapter = adapter(gateway)
        val firstPresentation = async { adapter.present(instruction()) }
        runCurrent()
        gateway.purchases = listOf(
            RuStorePurchase(PURCHASE_ONE, PRODUCT_ID, RuStorePurchaseState.PURCHASED),
        )
        val staleRecovery = async { adapter.recover(instruction(), null) }
        queryStarted.await()
        firstPresentation.cancelAndJoin()

        val newerResult = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(
            adapter.present(instruction(attemptReference = "newer-attempt")),
        )
        releaseQuery.complete(Unit)

        assertEquals(PURCHASE_ONE, proofValue(newerResult))
        assertNull(staleRecovery.await())
        assertTrue(gateway.requests.size <= 1)
    }

    @Test
    fun recoveredProofBeforeQueuedMainLaunchSkipsPurchaseUi() = runTest {
        val dispatcher = HoldingDispatcher()
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val adapter = RuStorePurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = { RuStoreActivityHandle(ActivityMarker) },
            restoreReferenceResolver = resolver(),
            gatewayFactory = { gateway },
            launchDispatcher = dispatcher,
        )
        val presentation = async(Dispatchers.Default) { adapter.present(instruction()) }
        dispatcher.awaitDispatch()
        gateway.purchases = listOf(
            RuStorePurchase(PURCHASE_ONE, PRODUCT_ID, RuStorePurchaseState.PURCHASED),
        )

        assertNull(adapter.recover(instruction(), null))
        dispatcher.runAll()

        val result = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(presentation.await())
        assertEquals(PURCHASE_ONE, proofValue(result))
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun restoreUsesExplicitMappedOrSkipPolicyAndRetriesUntilAcceptance() = runTest {
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        gateway.purchases = listOf(
            RuStorePurchase(PURCHASE_ONE, PRODUCT_ID, RuStorePurchaseState.PURCHASED),
            RuStorePurchase(PURCHASE_TWO, "unmapped.product", RuStorePurchaseState.PURCHASED),
        )
        val adapter = adapter(gateway)

        val first = adapter.queryRestorablePurchases().single()
        val retry = adapter.queryRestorablePurchases().single()
        adapter.onProofAccepted(first.proof)

        assertEquals(PLACEMENT_ID, first.placementId)
        assertEquals(PRODUCT_REFERENCE, first.productReference)
        assertEquals(PURCHASE_ONE, proofValue(first.proof))
        assertEquals(PURCHASE_ONE, proofValue(retry.proof))
        assertTrue(adapter.queryRestorablePurchases().isEmpty())
    }

    @Test
    fun closeCancelsActiveWorkClearsIdentityDedupeAndSupportsReuse() = runTest {
        val firstGateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val secondGateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val thirdGateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val gateways = ArrayDeque(listOf(firstGateway, secondGateway, thirdGateway))
        val adapter = RuStorePurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = { RuStoreActivityHandle(ActivityMarker) },
            restoreReferenceResolver = resolver(),
            gatewayFactory = { gateways.removeFirst() },
            launchDispatcher = Dispatchers.Unconfined,
        )
        val active = async { adapter.present(instruction()) }
        runCurrent()

        adapter.close()
        val closed = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(active.await())
        assertTrue(closed.error.retryable)
        assertEquals(1, firstGateway.closeCount)

        secondGateway.purchases = listOf(
            RuStorePurchase(PURCHASE_ONE, PRODUCT_ID, RuStorePurchaseState.PURCHASED),
        )
        val reused = assertIs<VitrinaKitAdapterPurchaseResult.ProofReady>(adapter.present(instruction()))
        adapter.onProofAccepted(reused.proof)
        adapter.close()
        thirdGateway.purchases = listOf(
            RuStorePurchase(PURCHASE_ONE, PRODUCT_ID, RuStorePurchaseState.PURCHASED),
        )
        assertEquals(1, adapter.queryRestorablePurchases().size)
    }

    @Test
    fun hostForwardsPaymentIntentWithoutAdapterRetention() = runTest {
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        val adapter = adapter(gateway)
        val intent = Intent("ru.example.PAYMENT_RETURN")

        adapter.handlePaymentIntent(intent)

        assertTrue(gateway.forwardedIntents.single() === intent)
        assertTrue(adapter.javaClass.declaredFields.none { it.type.name == "android.content.Intent" })
    }

    @Test
    fun paymentIntentFailureIsFixedAndRedactedAtPublicBoundary() = runTest {
        val gateway = FakeRuStorePayGateway(products = listOf(RuStoreProduct(PRODUCT_ID)))
        gateway.intentFailure = RuStoreGatewayException(RuStoreFailureKind.NETWORK)
        val adapter = adapter(gateway)

        val failure = runCatching { adapter.handlePaymentIntent(null) }.exceptionOrNull()
        val adapterFailure = assertIs<VitrinaKitPurchaseAdapterException>(failure)

        assertEquals("The purchase adapter operation failed.", adapterFailure.message)
        assertEquals("RSP-NETWORK", adapterFailure.error.supportReference)
        assertFalse(adapterFailure.toString().contains("provider-detail"))
    }

    @Test
    fun publicAdapterSurfaceHasNoMobileOwnershipOrTwoStepOperations() {
        val forbidden = setOf(
            "confirmTwoStepPurchase",
            "cancelTwoStepPurchase",
            "updateAcknowledgementState",
            "consume",
            "refund",
            "revoke",
        )

        assertTrue(RuStorePurchaseAdapter::class.java.methods.none { it.name in forbidden })
    }

    @Test
    fun providerExceptionsMapToFixedKindsWithoutLeakingProviderDiagnostics() {
        val constructor = RuStorePaymentException.RuStorePaymentNetworkException::class.java
            .getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Throwable::class.java)
        constructor.isAccessible = true
        val network = constructor.newInstance(
            "provider-code",
            "provider-id",
            "provider-detail",
            IllegalStateException("provider-cause"),
        )

        val kind = network.toSafeRuStoreFailureKind()
        val gatewayException = network.toSafeRuStoreGatewayException()

        assertEquals(RuStoreFailureKind.NETWORK, kind)
        assertEquals(RuStoreFailureKind.NETWORK, gatewayException.kind)
        assertFalse(gatewayException.toString().contains("provider-detail"))
        assertFalse(gatewayException.toString().contains("provider-id"))
    }

    @Test
    fun failedPresentationWithMatchingPurchaseReferenceHandsProofToServer() {
        val constructor = RuStorePaymentException.ProductPurchaseException::class.java
            .declaredConstructors
            .single { candidate -> candidate.parameterCount == 9 }
        constructor.isAccessible = true
        val exception = constructor.newInstance(
            null,
            PurchaseId(PURCHASE_ONE),
            ProductId(PRODUCT_ID),
            null,
            null,
            null,
            null,
            null,
            IllegalStateException("provider-detail"),
        ) as Throwable

        val outcome = assertIs<RuStorePurchaseOutcome.Success>(
            exception.toSafeRuStorePurchaseOutcome(),
        )

        assertEquals(PURCHASE_ONE, outcome.purchaseId)
        assertEquals(PRODUCT_ID, outcome.productId)
        assertFalse(outcome.toString().contains(PURCHASE_ONE))
    }

    @Test
    fun gatewayFactoryIsLazyAndInitializationFailureIsRedactedAfterClose() = runTest {
        var creations = 0
        val adapter = RuStorePurchaseAdapter(
            packageName = PACKAGE_NAME,
            activityHandleProvider = { RuStoreActivityHandle(ActivityMarker) },
            restoreReferenceResolver = resolver(),
            gatewayFactory = {
                creations += 1
                throw IllegalStateException("provider-detail")
            },
            launchDispatcher = Dispatchers.Unconfined,
        )

        assertEquals(0, creations)
        adapter.close()
        val failure = assertIs<VitrinaKitAdapterPurchaseResult.Failure>(adapter.present(instruction()))

        assertEquals(1, creations)
        assertEquals("RSP-UNKNOWN", failure.error.supportReference)
        assertFalse(failure.toString().contains("provider-detail"))
    }

    @Test
    fun taskTrackerCancelsSuspendedQueryExactlyOnceAndIgnoresLateProviderResult() = runTest {
        val tracker = RuStoreActiveTaskTracker()
        var cancelCalls = 0
        lateinit var completeQuery: (String) -> Unit
        val query = async {
            tracker.await(
                cancelProvider = { cancelCalls += 1 },
                registerListeners = { onSuccess, _ -> completeQuery = onSuccess },
            )
        }
        runCurrent()

        tracker.close()
        completeQuery("provider-result-after-close")

        assertFailsWith<CancellationException> { query.await() }
        assertEquals(1, cancelCalls)

        var completedCancelCalls = 0
        val completedTracker = RuStoreActiveTaskTracker()
        val completed = completedTracker.await(
            cancelProvider = { completedCancelCalls += 1 },
            registerListeners = { onSuccess, _ -> onSuccess("synchronous-result") },
        )
        completedTracker.close()

        assertEquals("synchronous-result", completed)
        assertEquals(0, completedCancelCalls)
    }

    @Test
    fun taskTrackerRemovesEveryTerminalPathAndRejectsWorkAfterClose() = runTest {
        val providerFailure = IllegalStateException("provider-failure")
        var failedTaskCancelCalls = 0
        val failedTracker = RuStoreActiveTaskTracker()
        val failure = assertFailsWith<IllegalStateException> {
            failedTracker.await<String>(
                cancelProvider = { failedTaskCancelCalls += 1 },
                registerListeners = { _, onFailure -> onFailure(providerFailure) },
            )
        }
        failedTracker.close()
        assertEquals("provider-failure", failure.message)
        assertEquals(0, failedTaskCancelCalls)

        var cancelledTaskCancelCalls = 0
        lateinit var completeCancelledTask: (String) -> Unit
        lateinit var failCancelledTask: (Throwable) -> Unit
        val cancelledTracker = RuStoreActiveTaskTracker()
        val cancelledTask = async {
            cancelledTracker.await(
                cancelProvider = { cancelledTaskCancelCalls += 1 },
                registerListeners = { onSuccess, onFailure ->
                    completeCancelledTask = onSuccess
                    failCancelledTask = onFailure
                },
            )
        }
        runCurrent()
        cancelledTask.cancelAndJoin()
        completeCancelledTask("late-result")
        failCancelledTask(IllegalStateException("late-failure"))
        cancelledTracker.close()
        assertEquals(1, cancelledTaskCancelCalls)

        var registrationCancelCalls = 0
        val registrationTracker = RuStoreActiveTaskTracker()
        assertFailsWith<IllegalArgumentException> {
            registrationTracker.await<String>(
                cancelProvider = { registrationCancelCalls += 1 },
                registerListeners = { _, _ -> throw IllegalArgumentException("listener-registration") },
            )
        }
        registrationTracker.close()
        assertEquals(1, registrationCancelCalls)

        var postCloseCancelCalls = 0
        var postCloseListenerRegistrations = 0
        val closedTracker = RuStoreActiveTaskTracker().also(RuStoreActiveTaskTracker::close)
        val postCloseTask = async {
            closedTracker.await<String>(
                cancelProvider = { postCloseCancelCalls += 1 },
                registerListeners = { _, _ -> postCloseListenerRegistrations += 1 },
            )
        }
        runCurrent()
        assertFailsWith<CancellationException> { postCloseTask.await() }
        assertEquals(1, postCloseCancelCalls)
        assertEquals(0, postCloseListenerRegistrations)
    }

    @Test
    fun taskTrackerLinearizesConcurrentCompletionAndClose() = runTest {
        val tracker = RuStoreActiveTaskTracker()
        var cancelCalls = 0
        lateinit var completeQuery: (String) -> Unit
        val query = async {
            tracker.await(
                cancelProvider = { cancelCalls += 1 },
                registerListeners = { onSuccess, _ -> completeQuery = onSuccess },
            )
        }
        runCurrent()
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val completion = executor.submit {
                ready.countDown()
                start.await()
                completeQuery("concurrent-result")
            }
            val closing = executor.submit {
                ready.countDown()
                start.await()
                tracker.close()
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            completion.get(5, TimeUnit.SECONDS)
            closing.get(5, TimeUnit.SECONDS)

            val outcome = runCatching { query.await() }
            if (outcome.isSuccess) {
                assertEquals("concurrent-result", outcome.getOrThrow())
                assertEquals(0, cancelCalls)
            } else {
                assertIs<CancellationException>(outcome.exceptionOrNull())
                assertEquals(1, cancelCalls)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun trackedPurchaseSuppressesCallbackAfterCloseAndCancelsExactlyOnce() {
        val tracker = RuStoreActiveTaskTracker()
        var providerCancelCalls = 0
        var callbackCalls = 0
        val purchaseTask = tracker.track { providerCancelCalls += 1 }

        tracker.close()
        if (purchaseTask.complete()) {
            callbackCalls += 1
        }
        purchaseTask.cancel()
        tracker.close()

        assertEquals(1, providerCancelCalls)
        assertEquals(0, callbackCalls)
    }

    private fun adapter(
        gateway: FakeRuStorePayGateway,
        foregroundQueryIntervalMillis: Long = 30_000L,
        clockMillis: () -> Long = { 1_000L },
    ): RuStorePurchaseAdapter = RuStorePurchaseAdapter(
        packageName = PACKAGE_NAME,
        activityHandleProvider = { RuStoreActivityHandle(ActivityMarker) },
        restoreReferenceResolver = resolver(),
        gatewayFactory = { gateway },
        launchDispatcher = Dispatchers.Unconfined,
        foregroundQueryIntervalMillis = foregroundQueryIntervalMillis,
        clockMillis = clockMillis,
    )

    private fun resolver(): RuStoreRestoreReferenceResolver =
        RuStoreRestoreReferenceResolver { productId ->
            if (productId == PRODUCT_ID) {
                RuStoreRestoreResolution.Mapped(
                    placementId = PLACEMENT_ID,
                    productReference = PRODUCT_REFERENCE,
                )
            } else {
                RuStoreRestoreResolution.Skip
            }
        }

    private fun instruction(
        productId: String = PRODUCT_ID,
        packageName: String = PACKAGE_NAME,
        attemptReference: String = ATTEMPT_REFERENCE,
    ): VitrinaKitPurchaseInstruction = VitrinaKitPurchaseInstruction(
        attemptReference = attemptReference,
        productId = productId,
        priceId = null,
        packageName = packageName,
        accountBinding = ACCOUNT_BINDING,
        expiresAt = "2026-08-06T12:00:00Z",
    )

    private fun proofValue(result: VitrinaKitAdapterPurchaseResult.ProofReady): String =
        proofValue(result.proof)

    private fun proofValue(proof: ru.vitrina.sdk.purchase.VitrinaKitProviderProof): String {
        val field = proof.javaClass.getDeclaredField("value")
        field.isAccessible = true
        return field.get(proof) as String
    }

    private fun hasActiveWaiter(adapter: RuStorePurchaseAdapter): Boolean {
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
        const val ACCOUNT_BINDING = "opaque-account-binding"
        const val ATTEMPT_REFERENCE = "attempt-reference"
        const val PLACEMENT_ID = "main"
        const val PRODUCT_REFERENCE = "premium-monthly"
        const val PURCHASE_ONE = "rustore-purchase-one"
        const val PURCHASE_TWO = "rustore-purchase-two"
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

private class FakeRuStorePayGateway(
    var products: List<RuStoreProduct>,
    private val beforePurchaseQuery: suspend (Int) -> Unit = {},
) : RuStorePayGateway {
    val productQueries = mutableListOf<String>()
    val requests = mutableListOf<RuStorePurchaseRequest>()
    val purchaseThreads = Channel<String>(capacity = Channel.UNLIMITED)
    val forwardedIntents = mutableListOf<Intent?>()
    val queryFailures = ArrayDeque<RuStoreFailureKind>()
    var productQueryFailure: RuStoreFailureKind? = null
    var intentFailure: RuStoreGatewayException? = null
    var purchases: List<RuStorePurchase> = emptyList()
    var purchaseQueryCount: Int = 0
    var closeCount: Int = 0
    private var callback: ((RuStorePurchaseOutcome) -> Unit)? = null

    override suspend fun getProducts(productId: String): List<RuStoreProduct> {
        productQueries += productId
        productQueryFailure?.let { failure -> throw RuStoreGatewayException(failure) }
        return products
    }

    override suspend fun getPurchases(): List<RuStorePurchase> {
        purchaseQueryCount += 1
        beforePurchaseQuery(purchaseQueryCount)
        queryFailures.removeFirstOrNull()?.let { failure -> throw RuStoreGatewayException(failure) }
        return purchases
    }

    override fun purchase(
        request: RuStorePurchaseRequest,
        callback: (RuStorePurchaseOutcome) -> Unit,
    ): RuStorePurchaseOperation {
        purchaseThreads.trySend(Thread.currentThread().name)
        requests += request
        this.callback = callback
        return RuStorePurchaseOperation { this.callback = null }
    }

    override fun proceedIntent(intent: Intent?) {
        intentFailure?.let { failure -> throw failure }
        forwardedIntents += intent
    }

    override fun close() {
        closeCount += 1
        callback = null
    }

    fun completePurchase(outcome: RuStorePurchaseOutcome) {
        callback?.invoke(outcome)
    }
}
