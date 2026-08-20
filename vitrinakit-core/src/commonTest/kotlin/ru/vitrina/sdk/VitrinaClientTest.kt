@file:Suppress("DEPRECATION")
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class,
)

package ru.vitrina.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpMethod
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.http.VitrinaHttpResponse
import ru.vitrina.sdk.installation.VitrinaKitInstallationIdStorage
import ru.vitrina.sdk.cache.SubscriberCacheKey
import ru.vitrina.sdk.model.BillingIntervalUnit
import ru.vitrina.sdk.model.CheckoutSession
import ru.vitrina.sdk.model.Entitlement
import ru.vitrina.sdk.model.Paywall
import ru.vitrina.sdk.model.PaywallProduct
import ru.vitrina.sdk.model.EntitlementSource
import ru.vitrina.sdk.model.SubscriberEntitlementState
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.model.SubscriptionStatus
import ru.vitrina.sdk.model.VitrinaCheckoutErrorCode
import ru.vitrina.sdk.model.VitrinaError
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitAccessResolution
import ru.vitrina.sdk.model.VitrinaKitIdentifyResult
import ru.vitrina.sdk.model.VitrinaKitPaywall
import ru.vitrina.sdk.model.VitrinaKitPurchase
import ru.vitrina.sdk.model.VitrinaKitProfile
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.model.VitrinaResult
import ru.vitrina.sdk.purchase.VitrinaKitAdapterPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationAdapter
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationRequest
import ru.vitrina.sdk.purchase.VitrinaKitProviderProof
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseCapability
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseErrorCode
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseInstruction
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResumeData
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase
import ru.vitrina.sdk.purchase.PurchaseApiResult
import ru.vitrina.sdk.purchase.SubscriberScope
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAttemptStatus

class VitrinaClientTest {
    @AfterTest
    fun tearDown() {
        VitrinaKit.resetForTesting()
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun activationRequiresExactlyOneAdapter() {
        VitrinaKit.resetForTesting()

        val missing = VitrinaKit.activate(VitrinaKitConfig.Builder("pk_test").build())
        val duplicate = VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )
        val mixed = VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .withHostedMigrationAdapter(FacadeHostedMigrationAdapter())
                .build(),
        )
        val hosted = VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHostedMigrationAdapter(FacadeHostedMigrationAdapter())
                .build(),
        )
        val active = VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )

        assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(missing).error)
        assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(duplicate).error)
        assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(mixed).error)
        assertIs<VitrinaKitResult.Success<Unit>>(hosted)
        assertIs<VitrinaKitResult.Success<Unit>>(active)
    }

    @Test
    fun threeTierFacadeUsesInstallationIdentityAndOneAuthorizationAuthority() = runTest {
        val storage = MemoryInstallationIdStorage("installation-1")
        val http = RoutingHttpClient { request ->
            when (request.path) {
                "/api/v1/subscriber/identify" -> VitrinaHttpResponse(
                    HttpStatusOk,
                    """{"merged":true,"subscriber":$subscriberJson}""",
                )
                "/api/v1/subscriber/me" -> VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                else -> error("Unexpected request: ${request.path}")
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(storage)
                .withHttpClient(http)
                .withHostedMigrationAdapter(FacadeHostedMigrationAdapter())
                .build(),
        )

        VitrinaKit.getPaywall(placementId = "main")
        val identified = VitrinaKit.identify(userId = "customer-1")
        VitrinaKit.getPaywall(placementId = "main")
        VitrinaKit.setSubscriberSession(session = "session-token")
        VitrinaKit.getPaywall(placementId = "main")

        val identification = assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(identified).value
        assertTrue(identification.merged)
        assertTrue(identification.profile.hasAccess)
        assertTrue(http.requests.all { request ->
            request.headers["X-Vitrina-Installation-Id"] == "installation-1"
        })
        val identifyRequest = http.requests.single { it.path == "/api/v1/subscriber/identify" }
        assertEquals(VitrinaHttpMethod.POST, identifyRequest.method)
        assertEquals("PublishableKey pk_test", identifyRequest.headers["Authorization"])
        assertEquals("customer-1", identifyRequest.headers["X-Vitrina-Subscriber-Id"])
        val paywallRequests = http.requests.filter { it.path == "/api/v1/paywall/main" }
        assertEquals("PublishableKey pk_test", paywallRequests[0].headers["Authorization"])
        assertEquals(null, paywallRequests[0].headers["X-Vitrina-Subscriber-Id"])
        assertEquals("customer-1", paywallRequests[1].headers["X-Vitrina-Subscriber-Id"])
        assertEquals("Bearer session-token", paywallRequests[2].headers["Authorization"])
        assertEquals(null, paywallRequests[2].headers["X-Vitrina-Subscriber-Id"])
        assertFalse(paywallRequests[2].headers.values.any { value -> value.contains("pk_test") })
    }

    @Test
    fun logoutRotatesInstallationIdentityBeforeReturningToTierOne() = runTest {
        val storage = MemoryInstallationIdStorage("installation-before-logout")
        val http = RoutingHttpClient { request ->
            when (request.path) {
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                else -> error("Unexpected request: ${request.path}")
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(storage)
                .withHttpClient(http)
                .withHostedMigrationAdapter(FacadeHostedMigrationAdapter())
                .build(),
        )

        VitrinaKit.getPaywall(placementId = "main")
        assertIs<VitrinaKitResult.Success<Unit>>(VitrinaKit.logout())
        VitrinaKit.getPaywall(placementId = "main")

        val installationIds = http.requests.map { request ->
            request.headers.getValue("X-Vitrina-Installation-Id")
        }
        assertEquals("installation-before-logout", installationIds.first())
        assertNotEquals(installationIds.first(), installationIds.last())
        assertEquals(storage.read(), installationIds.last())
    }

    @Test
    fun activationRestoresStorePurchaseWithoutBlockingAndExposesCheckingProfile() = runTest {
        val restoreStarted = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        val restoreCompleted = CompletableDeferred<Unit>()
        val storage = MemoryInstallationIdStorage()
        val adapter = FacadePurchaseAdapter(
            beforeAutomaticRestoreQuery = {
                restoreStarted.complete(Unit)
                releaseRestore.await()
            },
            restorablePurchases = listOf(
                VitrinaKitRestorablePurchase(
                    placementId = "main",
                    productReference = "premium_monthly",
                    proof = VitrinaKitProviderProof("store-proof"),
                ),
            ),
        )
        val http = RoutingHttpClient { request ->
            when (request.path) {
                "/api/v1/purchases/restore" -> {
                    restoreCompleted.complete(Unit)
                    VitrinaHttpResponse(HttpStatusOk, restoreSuccessJson)
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }

        val activated = VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(storage)
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        assertIs<VitrinaKitResult.Success<Unit>>(activated)
        restoreStarted.await()
        val checking = assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value
        assertEquals(VitrinaKitAccessResolution.CHECKING, checking.accessResolution)

        releaseRestore.complete(Unit)
        restoreCompleted.await()
        val current = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) {
                var profile: VitrinaKitProfile
                do {
                    profile = assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value
                    if (profile.accessResolution != VitrinaKitAccessResolution.CURRENT) {
                        delay(10)
                    }
                } while (profile.accessResolution != VitrinaKitAccessResolution.CURRENT)
                profile
            }
        }

        assertTrue(requireNotNull(current).hasAccess)
        val restoreRequest = http.requests.single { it.path == "/api/v1/purchases/restore" }
        assertEquals(storage.read(), restoreRequest.headers["X-Vitrina-Installation-Id"])
        assertEquals("PublishableKey pk_test", restoreRequest.headers["Authorization"])
    }

    @Test
    fun emailVerificationRequestDoesNotExposeServerAddressKnowledge() = runTest {
        val storage = MemoryInstallationIdStorage("installation-email")
        val http = QueueHttpClient(
            VitrinaHttpResponse(202, """{"status":"accepted","internal":"owner"}"""),
            VitrinaHttpResponse(202, """{"status":"accepted","internal":"unknown"}"""),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(storage)
                .withHttpClient(http)
                .withHostedMigrationAdapter(FacadeHostedMigrationAdapter())
                .build(),
        )

        val owner = VitrinaKit.requestEmailVerification(email = "owner@example.com")
        val unknown = VitrinaKit.requestEmailVerification(email = "unknown@example.com")

        assertEquals(VitrinaKitResult.Success(Unit), owner)
        assertEquals(VitrinaKitResult.Success(Unit), unknown)
        assertTrue(http.requests.all { request ->
            request.path == "/api/v1/email-verifications" &&
                request.headers["X-Vitrina-Installation-Id"] == "installation-email"
        })
    }

    @Test
    fun emailVerificationConfirmationBindsReturnedSessionAsSoleAuthority() = runTest {
        val http = RoutingHttpClient { request ->
            when (request.path) {
                "/api/v1/email-verifications/confirm" -> VitrinaHttpResponse(
                    HttpStatusOk,
                    """{"session_token":"email-session","expires_at":"2026-08-16T12:00:00Z","profile":$subscriberJson}""",
                )
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                else -> error("Unexpected request: ${request.path}")
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage("installation-email"))
                .withHttpClient(http)
                .withHostedMigrationAdapter(FacadeHostedMigrationAdapter())
                .build(),
        )

        val confirmed = VitrinaKit.confirmEmailVerification(
            email = "owner@example.com",
            code = "123456",
        )
        VitrinaKit.getPaywall("main")

        assertTrue(assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(confirmed).value.hasAccess)
        val confirmation = http.requests.single { it.path == "/api/v1/email-verifications/confirm" }
        assertEquals("PublishableKey pk_test", confirmation.headers["Authorization"])
        val paywall = http.requests.single { it.path == "/api/v1/paywall/main" }
        assertEquals("Bearer email-session", paywall.headers["Authorization"])
        assertFalse(paywall.headers.containsKey("X-Vitrina-Subscriber-Id"))
        assertFalse(paywall.headers.values.any { value -> value.contains("pk_test") })
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun foregroundRecoveryConfirmsCachedAttemptWithoutRelaunchingPresentation() = runTest {
        var confirmRequestCount = 0
        val http = object : VitrinaHttpClient {
            val requests = mutableListOf<VitrinaHttpRequest>()

            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/subscriber/me" -> VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                    "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                    "/api/v1/purchase-attempts" -> VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson)
                    "/api/v1/purchase-attempts/attempt-1" -> VitrinaHttpResponse(
                        HttpStatusOk,
                        purchaseAttemptJson,
                    )
                    "/api/v1/purchase-attempts/attempt-1/confirm" -> {
                        confirmRequestCount += 1
                        if (confirmRequestCount == 1) {
                            VitrinaHttpResponse(
                                statusCode = 503,
                                body = """{"type":"https://api.vitrinakit.ru/problems/provider_validation_unavailable","title":"Unavailable","status":503,"detail":"Validation is temporarily unavailable.","instance":"/api/v1/purchase-attempts/attempt-1/confirm","code":"provider_validation_unavailable","request_id":"request-retry","meta":{"retryable":true}}""",
                            )
                        } else {
                            VitrinaHttpResponse(HttpStatusOk, purchaseSuccessJson)
                        }
                    }
                    else -> error("Unexpected request: ${request.path}")
                }
            }
        }
        val adapter = FacadePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(
                VitrinaKitProviderProof("initial-proof"),
            ),
            recoverResult = VitrinaKitAdapterPurchaseResult.ProofReady(
                VitrinaKitProviderProof("foreground-proof"),
            ),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        withContext(Dispatchers.Default) {
            while (adapter.restoreQueryCount == 0) {
                delay(1)
            }
        }
        VitrinaKit.setSubscriberSession(session = "opaque-session")
        val product = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(
            VitrinaKit.getPaywall(placementId = "main"),
        ).value.products.single()
        val initial = assertIs<VitrinaKitPurchaseResult.Failure>(VitrinaKit.purchase(product))

        val recovered = assertIs<VitrinaKitPurchaseResult.Success>(VitrinaKit.onForeground())

        assertEquals(VitrinaKitPurchaseErrorCode.PROVIDER_VALIDATION_UNAVAILABLE, initial.error.code)
        assertEquals("attempt-1", recovered.purchaseReference)
        assertEquals(1, adapter.presentCount)
        assertEquals(1, adapter.recoverCount)
        assertEquals(1, adapter.acceptedProofCount)
        assertEquals(2, confirmRequestCount)
        assertEquals(
            listOf(
                "/api/v1/purchase-attempts/attempt-1/confirm",
                "/api/v1/purchase-attempts/attempt-1",
                "/api/v1/purchase-attempts/attempt-1/confirm",
            ),
            http.requests.map { request -> request.path }.takeLast(3),
        )
        assertEquals("Bearer opaque-session", http.requests.last().headers["Authorization"])
        assertFalse(http.requests.joinToString().contains("foreground-proof"))
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun foregroundRecoveryResolvesActivePresentationAndConfirmsExactlyOnce() = runTest {
        val presentationReachedBoundary = CompletableDeferred<Unit>()
        val releasePresentation = CompletableDeferred<Unit>()
        var confirmRequestCount = 0
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/me" -> VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                "/api/v1/purchase-attempts" -> VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson)
                "/api/v1/purchase-attempts/attempt-1" -> VitrinaHttpResponse(HttpStatusOk, purchaseAttemptJson)
                "/api/v1/purchase-attempts/attempt-1/confirm" -> {
                    confirmRequestCount += 1
                    VitrinaHttpResponse(HttpStatusOk, purchaseSuccessJson)
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        val adapter = FacadePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(
                VitrinaKitProviderProof("foreground-resolved-proof"),
            ),
            beforePresent = {
                presentationReachedBoundary.complete(Unit)
                releasePresentation.await()
            },
            beforeRecover = {
                releasePresentation.complete(Unit)
            },
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        VitrinaKit.setSubscriberSession(session = "opaque-session")
        val product = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(
            VitrinaKit.getPaywall(placementId = "main"),
        ).value.products.single()
        val purchasing = async { VitrinaKit.purchase(product) }
        presentationReachedBoundary.await()

        val foreground = withTimeoutOrNull(100L) { VitrinaKit.onForeground() }
        if (foreground == null) {
            releasePresentation.complete(Unit)
            purchasing.cancelAndJoin()
        }

        assertIs<VitrinaKitPurchaseResult.Pending>(foreground)
        assertIs<VitrinaKitPurchaseResult.Success>(purchasing.await())
        assertEquals(1, adapter.presentCount)
        assertEquals(1, adapter.recoverCount)
        assertEquals(1, adapter.acceptedProofCount)
        assertEquals(1, confirmRequestCount)
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun defaultForegroundRecoveryNeverDelegatesToPresentationResume() = runTest {
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
            VitrinaHttpResponse(HttpStatusOk, paywallJson),
            VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson),
            VitrinaHttpResponse(HttpStatusOk, purchaseAttemptJson),
        )
        val adapter = DefaultRecoveryFacadeAdapter()
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        VitrinaKit.setSubscriberSession(session = "opaque-session")
        val product = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(
            VitrinaKit.getPaywall(placementId = "main"),
        ).value.products.single()
        assertIs<VitrinaKitPurchaseResult.Pending>(VitrinaKit.purchase(product))

        val foreground = VitrinaKit.onForeground()

        assertIs<VitrinaKitPurchaseResult.Pending>(foreground)
        assertEquals(0, adapter.resumeCount)
    }

    @Test
    fun foregroundWithoutIdentifiedPendingAttemptIsNoOp() = runTest {
        val adapter = FacadePurchaseAdapter()
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
        )

        assertEquals(null, VitrinaKit.onForeground())
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        assertEquals(null, VitrinaKit.onForeground())
        VitrinaKit.setSubscriberSession(session = "opaque-session")
        assertEquals(null, VitrinaKit.onForeground())
        assertEquals(0, adapter.recoverCount)
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun identityTransitionWaitsAtForegroundProviderRecoveryBoundary() = runTest {
        val recoveryReachedBoundary = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val replacementRequestRecorded = CompletableDeferred<Unit>()
        var confirmRequestCount = 0
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/me" -> VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                "/api/v1/purchase-attempts" -> VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson)
                "/api/v1/purchase-attempts/attempt-1" -> VitrinaHttpResponse(HttpStatusOk, purchaseAttemptJson)
                "/api/v1/purchase-attempts/attempt-1/confirm" -> {
                    confirmRequestCount += 1
                    VitrinaHttpResponse(
                        statusCode = 503,
                        body = """{"type":"https://api.vitrinakit.ru/problems/provider_validation_unavailable","title":"Unavailable","status":503,"detail":"Validation is temporarily unavailable.","instance":"/api/v1/purchase-attempts/attempt-1/confirm","code":"provider_validation_unavailable","request_id":"request-retry","meta":{"retryable":true}}""",
                    )
                }
                "/api/v1/subscriber/identify" -> {
                    replacementRequestRecorded.complete(Unit)
                    VitrinaHttpResponse(HttpStatusOk, identifyResponseJson("replacement"))
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        val adapter = FacadePurchaseAdapter(
            presentResult = VitrinaKitAdapterPurchaseResult.ProofReady(
                VitrinaKitProviderProof("initial-proof"),
            ),
            recoverResult = VitrinaKitAdapterPurchaseResult.Cancelled,
            beforeRecover = {
                recoveryReachedBoundary.complete(Unit)
                releaseRecovery.await()
            },
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        VitrinaKit.setSubscriberSession(session = "opaque-session")
        val product = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(
            VitrinaKit.getPaywall(placementId = "main"),
        ).value.products.single()
        assertIs<VitrinaKitPurchaseResult.Failure>(VitrinaKit.purchase(product))

        val foreground = async { VitrinaKit.onForeground() }
        recoveryReachedBoundary.await()
        val replacing = async {
            VitrinaKit.identify(userId = "replacement")
        }
        runCurrent()

        assertFalse(replacementRequestRecorded.isCompleted)
        releaseRecovery.complete(Unit)
        assertIs<VitrinaKitPurchaseResult.Cancelled>(foreground.await())
        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(replacing.await())
        assertEquals(1, adapter.recoverCount)
        assertEquals(1, confirmRequestCount)
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun identityTransitionWaitsAtPurchaseNetworkSideEffectBoundary() = runTest {
        val startReachedBoundary = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val replacementRequestRecorded = CompletableDeferred<Unit>()
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/me" -> VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                "/api/v1/purchase-attempts" -> {
                    startReachedBoundary.complete(Unit)
                    releaseStart.await()
                    VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson)
                }
                "/api/v1/subscriber/identify" -> {
                    replacementRequestRecorded.complete(Unit)
                    VitrinaHttpResponse(HttpStatusOk, identifyResponseJson("replacement"))
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )
        assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(
            VitrinaKit.setSubscriberSession(session = "opaque-session"),
        )
        val paywallResult = VitrinaKit.getPaywall(placementId = "main")
        val paywall = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(
            paywallResult,
            paywallResult.toString(),
        ).value

        val purchasing = async { VitrinaKit.purchase(paywall.products.single()) }
        startReachedBoundary.await()
        val replacing = async {
            VitrinaKit.identify(userId = "replacement")
        }
        runCurrent()

        assertFalse(replacementRequestRecorded.isCompleted)
        releaseStart.complete(Unit)
        assertIs<VitrinaKitPurchaseResult.Failure>(purchasing.await())
        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(replacing.await())
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun identityTransitionWaitsAtProviderPresentationSideEffectBoundary() = runTest {
        val presentationReachedBoundary = CompletableDeferred<Unit>()
        val releasePresentation = CompletableDeferred<Unit>()
        val replacementRequestRecorded = CompletableDeferred<Unit>()
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/me" -> VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                "/api/v1/purchase-attempts" -> VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson)
                "/api/v1/subscriber/identify" -> {
                    replacementRequestRecorded.complete(Unit)
                    VitrinaHttpResponse(HttpStatusOk, identifyResponseJson("replacement"))
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        val adapter = FacadePurchaseAdapter(
            beforePresent = {
                presentationReachedBoundary.complete(Unit)
                releasePresentation.await()
            },
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        withContext(Dispatchers.Default) {
            while (adapter.restoreQueryCount == 0) {
                delay(1)
            }
        }
        VitrinaKit.setSubscriberSession(session = "opaque-session")
        val paywall = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(
            VitrinaKit.getPaywall(placementId = "main"),
        ).value

        val purchasing = async { VitrinaKit.purchase(paywall.products.single()) }
        presentationReachedBoundary.await()
        val replacing = async {
            VitrinaKit.identify(userId = "replacement")
        }
        runCurrent()

        assertFalse(replacementRequestRecorded.isCompleted)
        assertEquals(0, adapter.presentCount)
        releasePresentation.complete(Unit)
        assertIs<VitrinaKitPurchaseResult.Cancelled>(purchasing.await())
        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(replacing.await())
        assertEquals(1, adapter.presentCount)
    }

    @Test
    fun synchronousLogoutReturnsBusyWithoutBlockingAQueuedSingleThreadSideEffect() = runTest {
        val presentationReachedBoundary = CompletableDeferred<Unit>()
        val releasePresentation = CompletableDeferred<Unit>()
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/me" -> VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                "/api/v1/purchase-attempts" -> VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson)
                else -> error("Unexpected request: ${request.path}")
            }
        }
        val adapter = FacadePurchaseAdapter(
            beforePresent = {
                presentationReachedBoundary.complete(Unit)
                releasePresentation.await()
            },
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(
            VitrinaKit.setSubscriberSession(session = "opaque-session"),
        )
        val paywallResult = VitrinaKit.getPaywall(placementId = "main")
        val paywall = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(
            paywallResult,
            paywallResult.toString(),
        ).value
        val purchasing = async { VitrinaKit.purchase(paywall.products.single()) }
        presentationReachedBoundary.await()
        val closeCountBeforeBusyTransitions = adapter.closeCount

        val busy = VitrinaKit.logout()
        val busyActivation = VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_replacement")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )

        assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(busy).error)
        assertIs<VitrinaKitError.Configuration>(
            assertIs<VitrinaKitResult.Failure>(busyActivation).error,
        )
        assertEquals(0, adapter.presentCount)
        assertEquals(closeCountBeforeBusyTransitions, adapter.closeCount)
        releasePresentation.complete(Unit)
        assertIs<VitrinaKitPurchaseResult.Cancelled>(purchasing.await())
        assertIs<VitrinaKitResult.Success<Unit>>(VitrinaKit.logout())
    }

    @Test
    fun identityTransitionWaitsAtRestoreProviderQuerySideEffectBoundary() = runTest {
        val queryReachedBoundary = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val replacementRequestRecorded = CompletableDeferred<Unit>()
        var restoreApiRequestCount = 0
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/me" -> VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                "/api/v1/purchases/restore" -> {
                    restoreApiRequestCount += 1
                    VitrinaHttpResponse(HttpStatusOk, restoreSuccessJson)
                }
                "/api/v1/subscriber/identify" -> {
                    replacementRequestRecorded.complete(Unit)
                    VitrinaHttpResponse(HttpStatusOk, identifyResponseJson("replacement"))
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        val adapter = FacadePurchaseAdapter(
            beforeRestoreQuery = {
                queryReachedBoundary.complete(Unit)
                releaseQuery.await()
            },
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        withContext(Dispatchers.Default) {
            while (adapter.restoreQueryCount == 0) {
                delay(1)
            }
        }
        VitrinaKit.setSubscriberSession(session = "opaque-session")

        val restoring = async { VitrinaKit.restorePurchases() }
        queryReachedBoundary.await()
        val replacing = async {
            VitrinaKit.identify(userId = "replacement")
        }
        runCurrent()

        assertFalse(replacementRequestRecorded.isCompleted)
        assertEquals(1, adapter.restoreQueryCount)
        releaseQuery.complete(Unit)
        assertIs<ru.vitrina.sdk.purchase.VitrinaKitRestoreResult.Failure>(restoring.await())
        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(replacing.await())
        assertEquals(2, adapter.restoreQueryCount)
        assertEquals(1, restoreApiRequestCount)
    }

    @Test
    fun identityTransitionWaitsAtRestoreApiSideEffectBoundary() = runTest {
        val restoreApiReachedBoundary = CompletableDeferred<Unit>()
        val releaseRestoreApi = CompletableDeferred<Unit>()
        val replacementRequestRecorded = CompletableDeferred<Unit>()
        var restoreApiRequestCount = 0
        val emptyRestoreJson = """{"purchases":[],"profile":$subscriberJson}"""
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/me" -> VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                "/api/v1/purchases/restore" -> {
                    restoreApiRequestCount += 1
                    if (restoreApiRequestCount > 1) {
                        restoreApiReachedBoundary.complete(Unit)
                        releaseRestoreApi.await()
                    }
                    VitrinaHttpResponse(HttpStatusOk, emptyRestoreJson)
                }
                "/api/v1/subscriber/identify" -> {
                    replacementRequestRecorded.complete(Unit)
                    VitrinaHttpResponse(HttpStatusOk, identifyResponseJson("replacement"))
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )
        withContext(Dispatchers.Default) {
            while (restoreApiRequestCount == 0) {
                delay(1)
            }
        }
        VitrinaKit.setSubscriberSession(session = "opaque-session")

        val restoring = async { VitrinaKit.restorePurchases() }
        restoreApiReachedBoundary.await()
        val replacing = async {
            VitrinaKit.identify(userId = "replacement")
        }
        runCurrent()

        assertFalse(replacementRequestRecorded.isCompleted)
        assertEquals(2, restoreApiRequestCount)
        releaseRestoreApi.complete(Unit)
        assertIs<ru.vitrina.sdk.purchase.VitrinaKitRestoreResult.NoPurchases>(restoring.await())
        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(replacing.await())
        assertEquals(2, restoreApiRequestCount)
    }

    @Test
    fun httpResponseToStringRedactsSubscriberSessionBodies() {
        val secret = "opaque-session-secret"

        val response = VitrinaHttpResponse(HttpStatusCreated, "{\"session_token\":\"$secret\"}")

        assertFalse(response.toString().contains(secret))
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun externalIdentityReplacementAndLogoutReplaceCachedProfileWithFreshInstallationState() = runTest {
        val firstProfile = subscriberJson.replace("user-1", "user-first")
        val secondProfile = subscriberJson.replace("user-1", "user-second")
        val installationStorage = MemoryInstallationIdStorage("installation-before-logout")
        val logoutRestoreStarted = CompletableDeferred<Unit>()
        val releaseLogoutRestore = CompletableDeferred<Unit>()
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, firstProfile),
            VitrinaHttpResponse(HttpStatusOk, secondProfile),
        )
        val adapter = FacadePurchaseAdapter(
            beforeRestoreQuery = {
                logoutRestoreStarted.complete(Unit)
                releaseLogoutRestore.await()
            },
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(installationStorage)
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        awaitResolvedProfile()

        VitrinaKit.identify(userId = "user-first")
        assertEquals("user-first", assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value.externalUserId)
        VitrinaKit.identify(userId = "user-second")
        assertEquals("user-second", assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value.externalUserId)
        val previousInstallationId = installationStorage.read()
        VitrinaKit.logout()
        logoutRestoreStarted.await()

        val loggedOutProfile = assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(
            VitrinaKit.getProfile(),
        ).value
        assertEquals("", loggedOutProfile.externalUserId)
        assertFalse(loggedOutProfile.hasAccess)
        assertEquals(VitrinaKitAccessResolution.CHECKING, loggedOutProfile.accessResolution)
        assertNotEquals(previousInstallationId, installationStorage.read())
        assertEquals(3, adapter.closeCount)
        releaseLogoutRestore.complete(Unit)
        awaitResolvedProfile()
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun logoutReturnsBusyDuringSuspendedIdentifyThenClearsAfterRetry() = runTest {
        val profileRequested = CompletableDeferred<Unit>()
        val releaseProfile = CompletableDeferred<Unit>()
        val initialRestoreStarted = CompletableDeferred<Unit>()
        val releaseInitialRestore = CompletableDeferred<Unit>()
        val logoutRestoreStarted = CompletableDeferred<Unit>()
        val releaseLogoutRestore = CompletableDeferred<Unit>()
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/me" -> {
                    profileRequested.complete(Unit)
                    releaseProfile.await()
                    VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        val adapter = FacadePurchaseAdapter(
            beforeAutomaticRestoreQuery = {
                initialRestoreStarted.complete(Unit)
                releaseInitialRestore.await()
            },
            beforeRestoreQuery = {
                logoutRestoreStarted.complete(Unit)
                releaseLogoutRestore.await()
            },
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        initialRestoreStarted.await()
        releaseInitialRestore.complete(Unit)

        val identifying = async {
            VitrinaKit.setSubscriberSession(session = "opaque-session")
        }
        profileRequested.await()
        val busy = VitrinaKit.logout()
        try {
            assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(busy).error)
        } finally {
            releaseProfile.complete(Unit)
        }

        assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(identifying.await())
        assertIs<VitrinaKitResult.Success<Unit>>(VitrinaKit.logout())
        logoutRestoreStarted.await()
        try {
            assertEquals(
                VitrinaKitAccessResolution.CHECKING,
                assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value.accessResolution,
            )
        } finally {
            releaseLogoutRestore.complete(Unit)
        }
    }

    @Test
    fun logoutReturnsBusyUntilCancelledProfileRefreshCleanupCompletes() = runTest {
        val refreshRequested = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/identify" -> VitrinaHttpResponse(
                    HttpStatusOk,
                    identifyResponseJson("user-1"),
                )
                "/api/v1/subscriber/me" -> {
                    refreshRequested.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(FacadeHostedMigrationAdapter())
                .build(),
        )
        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(
            VitrinaKit.identify(userId = "user-1"),
        )

        val refreshing = async { VitrinaKit.getProfile(forceRefresh = true) }
        refreshRequested.await()
        refreshing.cancel()
        cleanupStarted.await()

        val busy = VitrinaKit.logout()
        try {
            assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(busy).error)
        } finally {
            releaseCleanup.complete(Unit)
        }
        refreshing.cancelAndJoin()

        assertIs<VitrinaKitResult.Success<Unit>>(VitrinaKit.logout())
    }

    @Test
    fun logoutReturnsBusyDuringFailingProfileRefreshThenClearsAfterFailure() = runTest {
        val refreshRequested = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/identify" -> VitrinaHttpResponse(
                    HttpStatusOk,
                    identifyResponseJson("user-1"),
                )
                "/api/v1/subscriber/me" -> {
                    refreshRequested.complete(Unit)
                    releaseRefresh.await()
                    VitrinaHttpResponse(HttpStatusServiceUnavailable, "{}")
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(FacadeHostedMigrationAdapter())
                .build(),
        )
        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(
            VitrinaKit.identify(userId = "user-1"),
        )

        val refreshing = async { VitrinaKit.getProfile(forceRefresh = true) }
        refreshRequested.await()

        val busy = VitrinaKit.logout()
        try {
            assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(busy).error)
        } finally {
            releaseRefresh.complete(Unit)
        }
        assertIs<VitrinaKitResult.Failure>(refreshing.await())

        assertIs<VitrinaKitResult.Success<Unit>>(VitrinaKit.logout())
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun logoutReturnsBusyDuringProfileRefreshThenClearsAfterRetry() = runTest {
        val refreshRequested = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val initialRestoreStarted = CompletableDeferred<Unit>()
        val releaseInitialRestore = CompletableDeferred<Unit>()
        val logoutRestoreStarted = CompletableDeferred<Unit>()
        val releaseLogoutRestore = CompletableDeferred<Unit>()
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/identify" -> VitrinaHttpResponse(
                    HttpStatusOk,
                    identifyResponseJson("user-1"),
                )
                "/api/v1/subscriber/me" -> {
                    refreshRequested.complete(Unit)
                    releaseRefresh.await()
                    VitrinaHttpResponse(HttpStatusOk, subscriberJson)
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        val adapter = FacadePurchaseAdapter(
            beforeAutomaticRestoreQuery = {
                initialRestoreStarted.complete(Unit)
                releaseInitialRestore.await()
            },
            beforeRestoreQuery = {
                logoutRestoreStarted.complete(Unit)
                releaseLogoutRestore.await()
            },
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(adapter)
                .build(),
        )
        initialRestoreStarted.await()
        releaseInitialRestore.complete(Unit)
        val identification = VitrinaKit.identify(userId = "user-1")
        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(
            identification,
            identification.toString(),
        )

        val refreshing = async { VitrinaKit.getProfile(forceRefresh = true) }
        refreshRequested.await()
        val busy = VitrinaKit.logout()
        try {
            assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(busy).error)
        } finally {
            releaseRefresh.complete(Unit)
        }

        assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(refreshing.await())
        assertIs<VitrinaKitResult.Success<Unit>>(VitrinaKit.logout())
        logoutRestoreStarted.await()
        try {
            assertEquals(
                VitrinaKitAccessResolution.CHECKING,
                assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value.accessResolution,
            )
        } finally {
            releaseLogoutRestore.complete(Unit)
        }
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun equalProductsFromDifferentPaywallsRetainExactPlacementProvenance() = runTest {
        val firstPaywall = paywallJson.replace("\"placement_key\":\"main\"", "\"placement_key\":\"first\"")
        val secondPaywall = paywallJson.replace("\"placement_key\":\"main\"", "\"placement_key\":\"second\"")
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
            VitrinaHttpResponse(HttpStatusOk, firstPaywall),
            VitrinaHttpResponse(HttpStatusOk, secondPaywall),
            VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )
        VitrinaKit.setSubscriberSession(session = "opaque-session")
        val firstProduct = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(
            VitrinaKit.getPaywall("first"),
        ).value.products.single()
        val secondProduct = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(
            VitrinaKit.getPaywall("second"),
        ).value.products.single()

        assertEquals(firstProduct, secondProduct)
        assertIs<VitrinaKitPurchaseResult.Cancelled>(VitrinaKit.purchase(secondProduct))
        assertEquals("second", http.requests.last().jsonBodyValue("placement_key"))
    }

    @OptIn(VitrinaKitPurchaseAdapterApi::class)
    @Test
    fun unknownProblemCodeMapsToSafeForwardCompatiblePurchaseError() = runTest {
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
            VitrinaHttpResponse(HttpStatusOk, paywallJson),
            VitrinaHttpResponse(
                HttpStatusConflict,
                """{"type":"https://api.vitrinakit.ru/problems/future_code","title":"Future","status":409,"detail":"Safe detail.","instance":"/api/v1/purchase-attempts","code":"future_code","request_id":"request-42","meta":{"retryable":{"unexpected":true}}}""",
            ),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )
        VitrinaKit.setSubscriberSession(session = "opaque-session")
        val product = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(
            VitrinaKit.getPaywall("main"),
        ).value.products.single()

        val result = VitrinaKit.purchase(product)

        val error = assertIs<VitrinaKitPurchaseResult.Failure>(result).error
        assertEquals(VitrinaKitPurchaseErrorCode.UNKNOWN, error.code)
        assertEquals("Safe detail.", error.message)
        assertEquals("request-42", error.supportReference)
    }

    @Test
    fun purchaseStatusAndRestoreMatchPublishedWireContract() = runTest {
        val statusHttp = QueueHttpClient(VitrinaHttpResponse(HttpStatusOk, purchaseAttemptJson))
        val scope = SubscriberScope(
            cacheKey = SubscriberCacheKey("production", "app-1", "subscriber-1"),
            sessionToken = "opaque-session",
        )

        val status = newClient(http = statusHttp).getPurchase(
            scope = scope,
            attemptReference = "attempt-1",
        )

        assertEquals(
            VitrinaKitPurchaseAttemptStatus.PROVIDER_READY,
            assertIs<PurchaseApiResult.Success<ru.vitrina.sdk.purchase.PurchaseAttempt>>(status).value.status,
        )
        assertEquals("/api/v1/purchase-attempts/attempt-1", statusHttp.requests.single().path)
        assertEquals("Bearer opaque-session", statusHttp.requests.single().headers["Authorization"])

        val restoreHttp = QueueHttpClient(VitrinaHttpResponse(HttpStatusOk, restoreSuccessJson))
        val restored = newClient(http = restoreHttp).restorePurchases(
            scope = scope,
            purchases = listOf(
                VitrinaKitRestorablePurchase(
                    placementId = "main",
                    productReference = "premium_monthly",
                    proof = VitrinaKitProviderProof("restore-proof-secret"),
                ),
            ),
            capability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
        )

        val restoreRequest = restoreHttp.requests.single()
        assertIs<PurchaseApiResult.Success<ru.vitrina.sdk.purchase.PurchaseRestoreResponse>>(restored)
        assertEquals("/api/v1/purchases/restore", restoreRequest.path)
        val restoreItem = Json.parseToJsonElement(restoreRequest.body.orEmpty())
            .jsonObject.getValue("purchases")
            .jsonArray.single().jsonObject
        assertEquals("main", restoreItem.getValue("placement_key").jsonPrimitive.content)
        assertEquals("premium_monthly", restoreItem.getValue("product_reference").jsonPrimitive.content)
        assertEquals("google_play", restoreItem.getValue("capability").jsonPrimitive.content)
        assertEquals("restore-proof-secret", restoreItem.getValue("proof").jsonPrimitive.content)
        assertFalse(restoreRequest.toString().contains("restore-proof-secret"))
    }

    @Test
    fun terminalAndNonRecoverableConfirmFailuresEvictClientPurchaseRecoveryIndex() = runTest {
        val terminalAttempt = purchaseAttemptJson.replace("\"provider_ready\"", "\"succeeded\"")
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson),
            VitrinaHttpResponse(HttpStatusOk, terminalAttempt),
            VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson),
            VitrinaHttpResponse(
                HttpStatusBadRequest,
                """{"type":"https://api.vitrinakit.ru/problems/provider_proof_invalid","title":"Invalid proof","status":400,"detail":"The proof is invalid.","instance":"/api/v1/purchase-attempts/attempt-1/confirm","code":"provider_proof_invalid","request_id":"request-43"}""",
            ),
        )
        val client = newClient(http = http)
        val scope = SubscriberScope(
            cacheKey = SubscriberCacheKey("production", "subscriber-1", "opaque-session"),
            sessionToken = "opaque-session",
        )

        client.startPurchase(
            scope = scope,
            placementId = "main",
            productReference = "premium_monthly",
            capability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
            idempotencyKey = "start-1",
        )
        assertEquals(1, client.trackedPurchaseAttemptCount)
        client.getPurchase(scope = scope, attemptReference = "attempt-1")
        assertEquals(0, client.trackedPurchaseAttemptCount)

        client.startPurchase(
            scope = scope,
            placementId = "main",
            productReference = "premium_monthly",
            capability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
            idempotencyKey = "start-2",
        )
        client.confirmPurchase(
            scope = scope,
            attemptReference = "attempt-1",
            idempotencyKey = "confirm-1",
            proof = VitrinaKitProviderProof("invalid-proof"),
        )

        assertEquals(0, client.trackedPurchaseAttemptCount)
    }

    @Test
    fun identityClearPreventsLateAttemptResponseFromRepopulatingClientRecoveryIndex() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
                requestStarted.complete(Unit)
                releaseResponse.await()
                return VitrinaHttpResponse(HttpStatusCreated, purchaseAttemptJson)
            }
        }
        val client = newClient(http = http)
        val scope = SubscriberScope(
            cacheKey = SubscriberCacheKey("production", "subscriber-1", "opaque-session"),
            sessionToken = "opaque-session",
        )

        val starting = async {
            client.startPurchase(
                scope = scope,
                placementId = "main",
                productReference = "premium_monthly",
                capability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
                idempotencyKey = "start-1",
            )
        }
        requestStarted.await()
        client.clearPurchaseAttempts()
        releaseResponse.complete(Unit)

        assertIs<PurchaseApiResult.Success<ru.vitrina.sdk.purchase.PurchaseAttempt>>(starting.await())
        assertEquals(0, client.trackedPurchaseAttemptCount)

        client.clearPurchaseAttempts()
        assertIs<PurchaseApiResult.Success<ru.vitrina.sdk.purchase.PurchaseAttempt>>(
            client.startPurchase(
                scope = scope,
                placementId = "main",
                productReference = "premium_monthly",
                capability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
                idempotencyKey = "start-2",
            ),
        )
        assertEquals(0, client.trackedPurchaseAttemptCount)
    }

    @Test
    fun facadeRequiresActivationBeforeUse() = runTest {
        VitrinaKit.resetForTesting()

        val result = VitrinaKit.getPaywall(
            placementId = "main",
            userId = "user-1",
        )

        val failure = assertIs<VitrinaKitResult.Failure>(result)
        assertIs<VitrinaKitError.Configuration>(failure.error)
    }

    @Test
    fun facadeFetchesPaywallAfterActivation() = runTest {
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
            VitrinaHttpResponse(HttpStatusOk, paywallJson),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )
        val identification = VitrinaKit.identify(userId = "user-1")
        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(
            identification,
            identification.toString(),
        )

        val result = VitrinaKit.getPaywall(placementId = "main")

        val success = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(result, result.toString())
        assertEquals("main", success.value.placementKey)
        assertEquals("PublishableKey pk_test", http.requests.last().headers["Authorization"])
        assertEquals(
            "$VitrinaKitApiBaseUrl/api/v1/paywall/main",
            http.requests.last().url,
        )
        assertEquals("user-1", http.requests.last().headers["X-Vitrina-Subscriber-Id"])
        assertTrue(http.requests.last().headers.getValue("X-Vitrina-Installation-Id").isNotBlank())
        assertEquals(VitrinaKitApiEnvironment.name, http.requests.last().headers["X-Vitrina-Environment"])
    }

    @Test
    fun facadeReturnsProductsFromPaywall() {
        val paywall = Paywall(
            placementKey = "main",
            products = listOf(monthlyProduct()),
        )

        val result = VitrinaKit.getPaywallProducts(paywall)

        val success = assertIs<VitrinaKitResult.Success<List<PaywallProduct>>>(result)
        assertEquals("price-1", success.value.single().priceId)
    }

    @Test
    fun deprecatedHostedPurchaseRequiresHostedAdapter() = runTest {
        val http = QueueHttpClient(VitrinaHttpResponse(HttpStatusOk, subscriberJson))
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )
        VitrinaKit.identify(userId = "bound-user")

        val result = VitrinaKit.makePurchase(
            product = monthlyProduct(),
            userId = "user-1",
            receiptEmail = "buyer@example.com",
            returnUrl = "vitrina://done",
        )

        assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(result).error)
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) {
                while (
                    assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile())
                        .value.accessResolution == VitrinaKitAccessResolution.CHECKING
                ) {
                    delay(10)
                }
            }
        }
    }

    @Test
    fun deprecatedHostedPurchaseDelegatesThroughCoreBoundCheckoutAndIgnoresPerCallFields() = runTest {
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
            VitrinaHttpResponse(HttpStatusOk, paywallJson),
            VitrinaHttpResponse(HttpStatusCreated, checkoutJson),
        )
        val adapter = FacadeHostedMigrationAdapter()
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )
        VitrinaKit.identify(userId = "bound-user")
        val product = cachedMonthlyProduct()

        val result = VitrinaKit.makePurchase(
            product = product,
            userId = "untrusted-per-call-user",
            receiptEmail = "untrusted@example.com",
            returnUrl = "untrusted://return",
        )

        assertEquals("session-1", assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(result).value.id)
        assertEquals("bound-user", adapter.lastRequest?.externalUserId)
        assertEquals("premium_monthly", adapter.lastRequest?.product?.productKey)
        assertEquals("bound-user", http.requests.last().headers["X-Vitrina-Subscriber-Id"])
        assertEquals("main", http.requests.last().jsonBodyValue("placement_key"))
        assertEquals("premium_monthly", http.requests.last().jsonBodyValue("product_reference"))
        assertEquals("vitrinakit-test://configured", http.requests.last().jsonBodyValue("return_url"))
        assertEquals("PublishableKey pk_test", http.requests.last().headers["Authorization"])
        assertFalse(http.requests.last().body.orEmpty().contains("untrusted-per-call-user"))
        assertFalse(http.requests.last().body.orEmpty().contains("untrusted@example.com"))
        assertFalse(http.requests.last().body.orEmpty().contains("untrusted://return"))
    }

    @Test
    fun backendSessionIsSoleCheckoutAuthorityWithoutBeingExposedToAdapter() = runTest {
        val subscriberSession = "subscriber-session-secret"
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
            VitrinaHttpResponse(HttpStatusOk, paywallJson),
            VitrinaHttpResponse(HttpStatusCreated, checkoutJson),
        )
        val adapter = FacadeHostedMigrationAdapter()
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )
        VitrinaKit.setSubscriberSession(session = subscriberSession)
        val product = cachedMonthlyProduct()

        val result = VitrinaKit.makePurchase(
            product = product,
            userId = "untrusted-per-call-user",
            receiptEmail = "untrusted@example.com",
            returnUrl = "untrusted://return",
        )

        assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(result)
        val checkoutRequest = http.requests.last()
        assertEquals("Bearer $subscriberSession", checkoutRequest.headers["Authorization"])
        assertFalse(checkoutRequest.headers.values.any { value -> value.contains("pk_test") })
        assertFalse(checkoutRequest.headers.containsKey("X-Vitrina-Subscriber-Id"))
        assertEquals("main", checkoutRequest.jsonBodyValue("placement_key"))
        assertEquals("premium_monthly", checkoutRequest.jsonBodyValue("product_reference"))
        assertEquals("vitrinakit-test://configured", checkoutRequest.jsonBodyValue("return_url"))
        assertFalse(adapter.lastRequest.toString().contains(subscriberSession))
        assertFalse(checkoutRequest.toString().contains(subscriberSession))
    }

    @Test
    fun identityTransitionWaitsAtHostedCheckoutNetworkSideEffectBoundary() = runTest {
        val checkoutReachedBoundary = CompletableDeferred<Unit>()
        val releaseCheckout = CompletableDeferred<Unit>()
        val replacementRequestRecorded = CompletableDeferred<Unit>()
        var checkoutRequestCount = 0
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/identify" -> {
                    val userId = request.headers.getValue("X-Vitrina-Subscriber-Id")
                    if (userId == "replacement") {
                        replacementRequestRecorded.complete(Unit)
                    }
                    VitrinaHttpResponse(HttpStatusOk, identifyResponseJson(userId))
                }
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                "/api/v1/checkout/sessions" -> {
                    checkoutReachedBoundary.complete(Unit)
                    releaseCheckout.await()
                    checkoutRequestCount += 1
                    VitrinaHttpResponse(HttpStatusCreated, checkoutJson)
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(FacadeHostedMigrationAdapter())
                .build(),
        )
        VitrinaKit.identify(userId = "bound-user")
        val product = cachedMonthlyProduct()

        val purchasing = async {
            VitrinaKit.makePurchase(
                product = product,
                userId = "ignored",
                receiptEmail = "ignored@example.com",
                returnUrl = "ignored://return",
            )
        }
        checkoutReachedBoundary.await()
        val replacing = async {
            VitrinaKit.identify(userId = "replacement")
        }
        runCurrent()

        assertFalse(replacementRequestRecorded.isCompleted)
        assertEquals(0, checkoutRequestCount)
        releaseCheckout.complete(Unit)
        assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(purchasing.await())
        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(replacing.await())
        assertEquals(1, checkoutRequestCount)
    }

    @Test
    fun detachedHostedCheckoutDoesNotInheritTheOuterAdaptersLeaseOwnership() = runTest {
        val detachedStarted = CompletableDeferred<Unit>()
        val releaseDetached = CompletableDeferred<Unit>()
        val releaseAdapter = CompletableDeferred<Unit>()
        val checkoutReachedBoundary = CompletableDeferred<Unit>()
        val releaseCheckout = CompletableDeferred<Unit>()
        val detachedResult = CompletableDeferred<VitrinaKitResult<VitrinaKitPurchase>>()
        val adapter = object : VitrinaKitHostedMigrationAdapter {
            override suspend fun purchaseForBoundIdentity(
                request: VitrinaKitHostedMigrationRequest,
            ): VitrinaKitResult<VitrinaKitPurchase> {
                CoroutineScope(currentCoroutineContext() + SupervisorJob()).launch {
                    detachedStarted.complete(Unit)
                    releaseDetached.await()
                    detachedResult.complete(
                        request.checkout.create(
                            receiptEmail = "configured@example.com",
                            returnUrl = "vitrinakit-test://configured",
                        ),
                    )
                }
                releaseAdapter.await()
                return VitrinaKitResult.Success(hostedPurchase())
            }

            override fun close() = Unit
        }
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/identify" -> VitrinaHttpResponse(
                    HttpStatusOk,
                    identifyResponseJson(request.headers.getValue("X-Vitrina-Subscriber-Id")),
                )
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                "/api/v1/checkout/sessions" -> {
                    checkoutReachedBoundary.complete(Unit)
                    releaseCheckout.await()
                    VitrinaHttpResponse(HttpStatusCreated, checkoutJson)
                }
                else -> error("Unexpected request: ${request.path}")
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )
        VitrinaKit.identify(userId = "bound-user")
        val product = cachedMonthlyProduct()

        val purchasing = async {
            VitrinaKit.makePurchase(
                product = product,
                userId = "ignored",
                receiptEmail = "ignored@example.com",
                returnUrl = "ignored://return",
            )
        }
        detachedStarted.await()
        releaseAdapter.complete(Unit)
        assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(purchasing.await())
        assertIs<VitrinaKitResult.Success<Unit>>(VitrinaKit.logout())

        releaseDetached.complete(Unit)
        runCurrent()
        val crossedCheckoutBoundary = checkoutReachedBoundary.isCompleted
        if (crossedCheckoutBoundary) {
            releaseCheckout.complete(Unit)
        }
        assertFalse(crossedCheckoutBoundary)
        assertIs<VitrinaKitResult.Failure>(detachedResult.await())
    }

    @Test
    fun enteredDetachedCheckoutDrainsBeforeOuterAdapterLeaseUnlocks() = runTest {
        val checkoutReachedBoundary = CompletableDeferred<Unit>()
        val releaseCheckout = CompletableDeferred<Unit>()
        val detachedResult = CompletableDeferred<VitrinaKitResult<VitrinaKitPurchase>>()
        val adapter = detachedCheckoutAdapter(
            detachedResult = detachedResult,
            afterLaunch = {
                checkoutReachedBoundary.await()
                VitrinaKitResult.Success(hostedPurchase())
            },
        )
        val http = hostedBoundaryHttpClient(
            checkoutReachedBoundary = checkoutReachedBoundary,
            releaseCheckout = releaseCheckout,
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )
        VitrinaKit.identify(userId = "bound-user")
        val product = cachedMonthlyProduct()

        val purchasing = async { makeIgnoredHostedPurchase(product) }
        checkoutReachedBoundary.await()
        runCurrent()
        val purchaseCompletedBeforeCheckout = purchasing.isCompleted
        val busy = VitrinaKit.logout()
        releaseCheckout.complete(Unit)
        val detached = detachedResult.await()
        val purchased = purchasing.await()

        assertFalse(purchaseCompletedBeforeCheckout)
        assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(busy).error)
        assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(detached)
        assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(purchased)
        assertIs<VitrinaKitResult.Success<Unit>>(VitrinaKit.logout())
    }

    @Test
    fun cancelledOuterAdapterDrainsEnteredDetachedCheckoutBeforeUnlock() = runTest {
        val checkoutReachedBoundary = CompletableDeferred<Unit>()
        val releaseCheckout = CompletableDeferred<Unit>()
        val detachedResult = CompletableDeferred<VitrinaKitResult<VitrinaKitPurchase>>()
        val adapter = detachedCheckoutAdapter(
            detachedResult = detachedResult,
            afterLaunch = { awaitCancellation() },
        )
        val http = hostedBoundaryHttpClient(
            checkoutReachedBoundary = checkoutReachedBoundary,
            releaseCheckout = releaseCheckout,
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )
        VitrinaKit.identify(userId = "bound-user")
        val product = cachedMonthlyProduct()

        val purchasing = async { makeIgnoredHostedPurchase(product) }
        checkoutReachedBoundary.await()
        purchasing.cancel()
        runCurrent()
        val cancellationCompletedBeforeCheckout = purchasing.isCompleted
        val busy = VitrinaKit.logout()
        releaseCheckout.complete(Unit)

        assertFalse(cancellationCompletedBeforeCheckout)
        assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(busy).error)
        assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(detachedResult.await())
        assertFailsWith<CancellationException> { purchasing.await() }
        assertIs<VitrinaKitResult.Success<Unit>>(VitrinaKit.logout())
    }

    @Test
    fun structuredHostedCheckoutChildReentersWhileOuterLeaseIsActive() = runTest {
        val adapter = object : VitrinaKitHostedMigrationAdapter {
            override suspend fun purchaseForBoundIdentity(
                request: VitrinaKitHostedMigrationRequest,
            ): VitrinaKitResult<VitrinaKitPurchase> = coroutineScope {
                async {
                    request.checkout.create(
                        receiptEmail = "configured@example.com",
                        returnUrl = "vitrinakit-test://configured",
                    )
                }.await()
            }

            override fun close() = Unit
        }
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
            VitrinaHttpResponse(HttpStatusOk, paywallJson),
            VitrinaHttpResponse(HttpStatusCreated, checkoutJson),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )
        VitrinaKit.identify(userId = "bound-user")
        val product = cachedMonthlyProduct()

        val result = VitrinaKit.makePurchase(
            product = product,
            userId = "ignored",
            receiptEmail = "ignored@example.com",
            returnUrl = "ignored://return",
        )

        assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(result)
        assertEquals(1, http.requests.count { it.path == "/api/v1/checkout/sessions" })
    }

    @Test
    fun nestedIdentifyIsRejectedWithoutClosingTheActiveHostedAdapter() = runTest {
        val nestedIdentify = CompletableDeferred<VitrinaKitResult<VitrinaKitIdentifyResult>>()
        var closeCount = 0
        val adapter = object : VitrinaKitHostedMigrationAdapter {
            override suspend fun purchaseForBoundIdentity(
                request: VitrinaKitHostedMigrationRequest,
            ): VitrinaKitResult<VitrinaKitPurchase> {
                nestedIdentify.complete(
                    VitrinaKit.identify(userId = "replacement"),
                )
                return VitrinaKitResult.Success(hostedPurchase())
            }

            override fun close() {
                closeCount += 1
            }
        }
        val http = object : VitrinaHttpClient {
            val requests = mutableListOf<VitrinaHttpRequest>()

            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/subscriber/identify" -> VitrinaHttpResponse(
                        HttpStatusOk,
                        identifyResponseJson(request.headers.getValue("X-Vitrina-Subscriber-Id")),
                    )
                    "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                    else -> error("Unexpected request: ${request.path}")
                }
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )
        VitrinaKit.identify(userId = "bound-user")
        val product = cachedMonthlyProduct()
        val closeCountBeforePurchase = closeCount

        val purchase = makeIgnoredHostedPurchase(product)

        assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(purchase)
        assertIs<VitrinaKitError.Configuration>(
            assertIs<VitrinaKitResult.Failure>(nestedIdentify.await()).error,
        )
        assertEquals(
            0,
            http.requests.count { request ->
                request.path == "/api/v1/subscriber/identify" &&
                    request.headers["X-Vitrina-Subscriber-Id"] == "replacement"
            },
        )
        assertEquals(closeCountBeforePurchase, closeCount)
        assertEquals("bound-user", assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value.externalUserId)
    }

    @Test
    fun escapedIdentifyReacquiresAfterOuterHostedLeaseUnlocks() = runTest {
        val detachedStarted = CompletableDeferred<Unit>()
        val releaseDetached = CompletableDeferred<Unit>()
        val detachedIdentify = CompletableDeferred<VitrinaKitResult<VitrinaKitIdentifyResult>>()
        val adapter = object : VitrinaKitHostedMigrationAdapter {
            override suspend fun purchaseForBoundIdentity(
                request: VitrinaKitHostedMigrationRequest,
            ): VitrinaKitResult<VitrinaKitPurchase> {
                CoroutineScope(currentCoroutineContext() + SupervisorJob()).launch {
                    detachedStarted.complete(Unit)
                    releaseDetached.await()
                    detachedIdentify.complete(
                        VitrinaKit.identify(userId = "replacement"),
                    )
                }
                return VitrinaKitResult.Success(hostedPurchase())
            }

            override fun close() = Unit
        }
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
            VitrinaHttpResponse(HttpStatusOk, paywallJson),
            VitrinaHttpResponse(HttpStatusOk, identifyResponseJson("replacement")),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )
        VitrinaKit.identify(userId = "bound-user")
        val product = cachedMonthlyProduct()

        val purchasing = async { makeIgnoredHostedPurchase(product) }
        detachedStarted.await()
        assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(purchasing.await())
        releaseDetached.complete(Unit)

        assertIs<VitrinaKitResult.Success<VitrinaKitIdentifyResult>>(detachedIdentify.await())
        assertEquals("/api/v1/subscriber/identify", http.requests.last().path)
    }

    @Test
    fun hostedMigrationKeepsLogoutBusyAtAdapterBoundary() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FacadeHostedMigrationAdapter(
            onPurchase = {
                started.complete(Unit)
                release.await()
                VitrinaKitResult.Success(hostedPurchase())
            },
        )
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
            VitrinaHttpResponse(HttpStatusOk, paywallJson),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )

        VitrinaKit.identify(userId = "bound-user")
        val product = cachedMonthlyProduct()
        val closeCountBeforePurchase = adapter.closeCount
        val purchasing = async {
            VitrinaKit.makePurchase(
                product = product,
                userId = "ignored",
                receiptEmail = "ignored@example.com",
                returnUrl = "ignored://return",
            )
        }
        started.await()
        val busy = VitrinaKit.logout()
        assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(busy).error)
        release.complete(Unit)

        assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(purchasing.await())
        assertIs<VitrinaKitResult.Success<Unit>>(VitrinaKit.logout())
        assertEquals(1, adapter.purchaseCount)
        assertEquals(closeCountBeforePurchase + 1, adapter.closeCount)
    }

    @Test
    fun hostedMigrationPropagatesCheckoutTransportCoroutineCancellation() = runTest {
        val adapter = FacadeHostedMigrationAdapter()
        val http = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
                "/api/v1/subscriber/identify" -> VitrinaHttpResponse(
                    HttpStatusOk,
                    identifyResponseJson(request.headers.getValue("X-Vitrina-Subscriber-Id")),
                )
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
                "/api/v1/checkout/sessions" -> throw CancellationException("cancelled")
                else -> error("Unexpected request: ${request.path}")
            }
        }
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )
        VitrinaKit.identify(userId = "bound-user")
        val product = cachedMonthlyProduct()

        assertFailsWith<CancellationException> {
            VitrinaKit.makePurchase(
                product = product,
                userId = "ignored",
                receiptEmail = "ignored@example.com",
                returnUrl = "ignored://return",
            )
        }
    }

    @Test
    fun facadeGetsProfile() = runTest {
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )
        VitrinaKit.identify(userId = "user-1")

        val result = VitrinaKit.getProfile()

        val success = assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(result)
        assertEquals(true, success.value.hasAccess)
        assertEquals("premium_access", success.value.entitlements.single().key)
    }

    @Test
    fun facadeBlockingWrapperFetchesPaywall() = runTest {
        val http = QueueHttpClient(
            VitrinaHttpResponse(HttpStatusOk, subscriberJson),
            VitrinaHttpResponse(HttpStatusOk, paywallJson),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withHttpClient(http)
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )
        VitrinaKit.identify(userId = "user-1")

        val result = VitrinaKit.getPaywallBlocking(
            placementId = "main",
            userId = "user-1",
        )

        assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(result)
    }

    @Test
    fun createCheckoutSessionReturnsConfirmationUrl() = runTest {
        val http = FakeHttpClient(
            response = VitrinaHttpResponse(
                statusCode = HttpStatusCreated,
                body = checkoutJson,
            ),
        )
        val client = newClient(http = http)

        val result = client.createCheckoutSession(
            request = CheckoutSessionRequest(
                placementKey = "main",
                productReference = "premium_monthly",
                returnUrl = "vitrina://done",
            ),
            externalUserId = "user-1",
            idempotencyKey = "idem-test",
            subscriberSession = null,
        )

        // The endpoint refuses a request without this header with 400 before it reads the body,
        // which is how every purchase failed in production while the body itself was correct.
        assertEquals("idem-test", http.singleRequest().headers["Idempotency-Key"])
        val success = assertIs<VitrinaResult.Success<CheckoutSession>>(result)
        assertEquals("session-1", success.value.id)
        assertEquals("payment-1", success.value.paymentId)
        assertEquals("pay_1", success.value.providerPaymentId)
        assertEquals("https://pay.example/confirm", success.value.confirmationUrl)
        assertEquals("pending", success.value.status)
        assertEquals("2026-07-22T12:00:00Z", success.value.expiresAt)
        assertEquals(false, success.value.reused)
        assertEquals("/api/v1/checkout/sessions", http.singleRequest().path)
        assertEquals(VitrinaHttpMethod.POST, http.singleRequest().method)
        assertEquals("main", http.singleRequest().jsonBodyValue("placement_key"))
        assertEquals("premium_monthly", http.singleRequest().jsonBodyValue("product_reference"))
        assertEquals("user-1", http.singleRequest().headers["X-Vitrina-Subscriber-Id"])
        assertFalse(http.singleRequest().body.orEmpty().contains("receipt_email"))
        assertFalse(http.singleRequest().body.orEmpty().contains("external_user_id"))
    }

    @Test
    fun checkoutSessionDeserializesPublicContract() {
        val payload = """
            {
              "id": "session-1",
              "payment_id": "payment-1",
              "provider_payment_id": "pay_1",
              "confirmation_url": "https://pay.example/confirm",
              "status": "pending",
              "expires_at": "2026-07-22T12:00:00Z",
              "reused": true
            }
        """.trimIndent()

        val session = json.decodeFromString<CheckoutSession>(payload)

        assertEquals("session-1", session.id)
        assertEquals("payment-1", session.paymentId)
        assertEquals("pay_1", session.providerPaymentId)
        assertEquals("https://pay.example/confirm", session.confirmationUrl)
        assertEquals("pending", session.status)
        assertEquals("2026-07-22T12:00:00Z", session.expiresAt)
        assertEquals(true, session.reused)
    }

    @Test
    fun refreshSubscriberMapsEntitlementState() = runTest {
        val http = FakeHttpClient(
            response = VitrinaHttpResponse(
                statusCode = HttpStatusOk,
                body = subscriberJson,
            ),
        )
        val client = newClient(http = http)

        val result = client.refreshSubscriber(externalUserId = "user-1", subscriberSession = null)

        val success = assertIs<VitrinaResult.Success<SubscriberState>>(result)
        assertEquals("user-1", success.value.externalUserId)
        assertEquals(true, success.value.hasAccess)
        assertEquals("premium_access", success.value.entitlements.single().key)
        assertEquals("2026-07-22T12:00:00Z", success.value.entitlements.single().expiresAt)
        assertEquals(EntitlementSource.YOOKASSA, success.value.entitlements.single().source)
        assertEquals(true, success.value.entitlements.single().autoRenewEnabled)
        assertEquals("/api/v1/subscriber/me", http.singleRequest().path)
        assertEquals("user-1", http.singleRequest().headers["X-Vitrina-Subscriber-Id"])
    }

    @Test
    fun networkFailureMapsToTypedError() = runTest {
        val client = newClient(http = FakeHttpClient(error = IllegalStateException("offline")))

        val result = client.fetchPaywall(
            placementKey = "main",
            externalUserId = "user-1",
            subscriberSession = null,
        )

        val failure = assertIs<VitrinaResult.Failure>(result)
        assertIs<VitrinaError.Network>(failure.error)
    }

    @Test
    fun cachedFallbackIsReturnedWhenConfigured() {
        val client = newClient()
        val fallback = Paywall(
            placementKey = "main",
            products = emptyList(),
        )

        client.cacheFallbackPaywall(placementKey = "main", paywall = fallback)

        assertEquals(fallback, client.loadFallbackPaywall(placementKey = "MAIN"))
    }

    @Test
    fun httpErrorsMapToTypedErrors() = runTest {
        val auth = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusUnauthorized, errorJson)),
        ).fetchPaywall("main", externalUserId = "user-1", subscriberSession = null)
        val provider = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusConflict, errorJson)),
        ).createCheckoutSession(
            request = checkoutSessionRequest(),
            externalUserId = "user-1",
            idempotencyKey = "idem-test",
            subscriberSession = null,
        )
        val subscription = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusNotFound, errorJson)),
        ).refreshSubscriber(externalUserId = "user-1", subscriberSession = null)

        assertIs<VitrinaError.Auth>(assertIs<VitrinaResult.Failure>(auth).error)
        assertIs<VitrinaError.Provider>(assertIs<VitrinaResult.Failure>(provider).error)
        assertIs<VitrinaError.Subscription>(assertIs<VitrinaResult.Failure>(subscription).error)
    }

    @Test
    fun unrecognizedCheckoutBadRequestSurfacesTheServerBodyInsteadOfANetworkError() = runTest {
        val result = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusBadRequest, errorJson)),
        ).createCheckoutSession(
            request = checkoutSessionRequest(),
            externalUserId = "user-1",
            idempotencyKey = "idem-test",
            subscriberSession = null,
        )

        val failure = assertIs<VitrinaError.Provider>(assertIs<VitrinaResult.Failure>(result).error)
        assertEquals(errorJson, failure.message)
    }

    @Test
    fun checkoutValidationErrorsMapToTypedCheckoutErrors() = runTest {
        val required = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusBadRequest, receiptEmailRequiredJson)),
        ).createCheckoutSession(
            request = checkoutSessionRequest(),
            externalUserId = "user-1",
            idempotencyKey = "idem-test",
            subscriberSession = null,
        )
        val invalid = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusBadRequest, invalidReceiptEmailJson)),
        ).createCheckoutSession(
            request = checkoutSessionRequest(),
            externalUserId = "user-1",
            idempotencyKey = "idem-test",
            subscriberSession = null,
        )
        val active = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusConflict, activeSubscriptionJson)),
        ).createCheckoutSession(
            request = checkoutSessionRequest(),
            externalUserId = "user-1",
            idempotencyKey = "idem-test",
            subscriberSession = null,
        )
        val emailUnverified = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusConflict, emailVerificationRequiredJson)),
        ).createCheckoutSession(
            request = checkoutSessionRequest(),
            externalUserId = "user-1",
            idempotencyKey = "idem-test",
            subscriberSession = null,
        )

        assertEquals(
            VitrinaCheckoutErrorCode.RECEIPT_EMAIL_REQUIRED,
            assertIs<VitrinaError.Checkout>(assertIs<VitrinaResult.Failure>(required).error).code,
        )
        assertEquals(
            VitrinaCheckoutErrorCode.INVALID_RECEIPT_EMAIL,
            assertIs<VitrinaError.Checkout>(assertIs<VitrinaResult.Failure>(invalid).error).code,
        )
        assertEquals(
            VitrinaCheckoutErrorCode.ACTIVE_SUBSCRIPTION_EXISTS,
            assertIs<VitrinaError.Checkout>(assertIs<VitrinaResult.Failure>(active).error).code,
        )
        val emailUnverifiedError = assertIs<VitrinaError.Checkout>(
            assertIs<VitrinaResult.Failure>(emailUnverified).error,
        )
        assertEquals(VitrinaCheckoutErrorCode.EMAIL_VERIFICATION_REQUIRED, emailUnverifiedError.code)
        assertEquals("A verified email is required before checkout.", emailUnverifiedError.message)
    }

    @Test
    fun deprecatedHostedPurchaseDoesNotBypassAdapterBoundary() = runTest {
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withInstallationIdStorage(MemoryInstallationIdStorage())
                .withPurchaseAdapter(FacadePurchaseAdapter())
                .build(),
        )

        val result = VitrinaKit.makePurchase(
            product = monthlyProduct(),
            userId = "user-1",
            receiptEmail = "buyer@example.com",
            returnUrl = "vitrina://done",
        )

        assertIs<VitrinaKitError.Configuration>(assertIs<VitrinaKitResult.Failure>(result).error)
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) {
                while (
                    assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile())
                        .value.accessResolution == VitrinaKitAccessResolution.CHECKING
                ) {
                    delay(10)
                }
            }
        }
    }

    @Test
    fun invalidConfigurationMapsToTypedError() = runTest {
        val client = VitrinaClient(
            config = VitrinaConfig(
                publishableKey = "",
                installationId = "installation-1",
            ),
            httpClient = FakeHttpClient(),
        )

        val result = client.refreshSubscriber(externalUserId = "user-1", subscriberSession = null)

        assertIs<VitrinaError.Configuration>(assertIs<VitrinaResult.Failure>(result).error)
    }
}

private fun newClient(http: VitrinaHttpClient = FakeHttpClient()): VitrinaClient = VitrinaClient(
    config = VitrinaConfig(
        publishableKey = "pk_test",
        installationId = "installation-1",
    ),
    httpClient = http,
)

private suspend fun awaitResolvedProfile(): VitrinaKitProfile = withContext(Dispatchers.Default) {
    requireNotNull(
        withTimeoutOrNull(5_000) {
            var profile: VitrinaKitProfile
            do {
                profile = assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value
                if (profile.accessResolution == VitrinaKitAccessResolution.CHECKING) {
                    delay(10)
                }
            } while (profile.accessResolution == VitrinaKitAccessResolution.CHECKING)
            profile
        },
    )
}

private class FakeHttpClient(
    private val response: VitrinaHttpResponse = VitrinaHttpResponse(
        statusCode = HttpStatusOk,
        body = "{}",
    ),
    private val error: Throwable? = null,
) : VitrinaHttpClient {
    private val requests = mutableListOf<VitrinaHttpRequest>()

    override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
        requests += request
        error?.let { throw it }
        return response
    }

    fun singleRequest(): VitrinaHttpRequest = requests.single()
}

private class QueueHttpClient(vararg responses: VitrinaHttpResponse) : VitrinaHttpClient {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<VitrinaHttpRequest>()

    override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
        requests += request
        if (request.path == "/api/v1/purchases/restore" &&
            responses.firstOrNull()?.body?.contains("\"purchases\"") != true
        ) {
            return VitrinaHttpResponse(
                HttpStatusOk,
                """{"purchases":[],"profile":$subscriberJson}""",
            )
        }
        var response = responses.removeFirst()
        if (request.path == "/api/v1/subscriber/identify" && !response.body.contains("\"subscriber\"")) {
            return response.copy(body = """{"merged":false,"subscriber":${response.body}}""")
        }
        return response
    }
}

private class RoutingHttpClient(
    private val response: suspend (VitrinaHttpRequest) -> VitrinaHttpResponse,
) : VitrinaHttpClient {
    val requests = mutableListOf<VitrinaHttpRequest>()

    override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
        requests += request
        return response(request)
    }
}

private class MemoryInstallationIdStorage(
    initialValue: String? = null,
) : VitrinaKitInstallationIdStorage {
    private var value = initialValue

    override fun read(): String? = value

    override fun write(installationId: String) {
        value = installationId
    }
}

@OptIn(VitrinaKitPurchaseAdapterApi::class)
private class FacadePurchaseAdapter(
    override val capability: VitrinaKitPurchaseCapability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
    private val presentResult: VitrinaKitAdapterPurchaseResult = VitrinaKitAdapterPurchaseResult.Cancelled,
    private val beforePresent: suspend () -> Unit = {},
    private val beforeAutomaticRestoreQuery: suspend () -> Unit = {},
    private val beforeRestoreQuery: suspend () -> Unit = {},
    private val recoverResult: VitrinaKitAdapterPurchaseResult? = null,
    private val beforeRecover: suspend () -> Unit = {},
    private val restorablePurchases: List<VitrinaKitRestorablePurchase> = emptyList(),
) : VitrinaKitPurchaseAdapter {
    var closeCount = 0
    var presentCount = 0
    var restoreQueryCount = 0
    var recoverCount = 0
    var acceptedProofCount = 0

    override suspend fun present(instruction: VitrinaKitPurchaseInstruction): VitrinaKitAdapterPurchaseResult {
        beforePresent()
        presentCount += 1
        return presentResult
    }

    override suspend fun queryRestorablePurchases(): List<VitrinaKitRestorablePurchase> {
        if (restoreQueryCount == 0) {
            beforeAutomaticRestoreQuery()
        } else {
            beforeRestoreQuery()
        }
        restoreQueryCount += 1
        return restorablePurchases
    }

    override suspend fun resume(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData,
    ): VitrinaKitAdapterPurchaseResult = VitrinaKitAdapterPurchaseResult.Cancelled

    override suspend fun recover(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData?,
    ): VitrinaKitAdapterPurchaseResult? {
        beforeRecover()
        recoverCount += 1
        return recoverResult
    }

    override fun onProofAccepted(proof: VitrinaKitProviderProof) {
        acceptedProofCount += 1
    }

    override fun close() {
        closeCount += 1
    }
}

@OptIn(VitrinaKitPurchaseAdapterApi::class)
private class DefaultRecoveryFacadeAdapter : VitrinaKitPurchaseAdapter {
    override val capability = VitrinaKitPurchaseCapability.GOOGLE_PLAY
    var resumeCount = 0

    override suspend fun present(
        instruction: VitrinaKitPurchaseInstruction,
    ): VitrinaKitAdapterPurchaseResult = VitrinaKitAdapterPurchaseResult.Pending(
        resumeData = VitrinaKitPurchaseResumeData("opaque-resume-state"),
    )

    override suspend fun queryRestorablePurchases(): List<VitrinaKitRestorablePurchase> = emptyList()

    override suspend fun resume(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData,
    ): VitrinaKitAdapterPurchaseResult {
        resumeCount += 1
        return VitrinaKitAdapterPurchaseResult.Cancelled
    }

    override fun close() = Unit
}

@OptIn(VitrinaKitPurchaseAdapterApi::class)
private class FacadeHostedMigrationAdapter(
    private val onPurchase: (suspend (VitrinaKitHostedMigrationRequest) -> VitrinaKitResult<VitrinaKitPurchase>)? = null,
) : VitrinaKitHostedMigrationAdapter {
    var lastRequest: VitrinaKitHostedMigrationRequest? = null
    var purchaseCount = 0
    var closeCount = 0

    override suspend fun purchaseForBoundIdentity(
        request: VitrinaKitHostedMigrationRequest,
    ): VitrinaKitResult<VitrinaKitPurchase> {
        purchaseCount += 1
        lastRequest = request
        return onPurchase?.invoke(request) ?: request.checkout.create(
            receiptEmail = "configured@example.com",
            returnUrl = "vitrinakit-test://configured",
        )
    }

    override fun close() {
        closeCount += 1
    }
}

private fun detachedCheckoutAdapter(
    detachedResult: CompletableDeferred<VitrinaKitResult<VitrinaKitPurchase>>,
    afterLaunch: suspend () -> VitrinaKitResult<VitrinaKitPurchase> = {
        VitrinaKitResult.Success(hostedPurchase())
    },
): VitrinaKitHostedMigrationAdapter = object : VitrinaKitHostedMigrationAdapter {
    override suspend fun purchaseForBoundIdentity(
        request: VitrinaKitHostedMigrationRequest,
    ): VitrinaKitResult<VitrinaKitPurchase> {
        CoroutineScope(currentCoroutineContext() + SupervisorJob()).launch {
            detachedResult.complete(
                request.checkout.create(
                    receiptEmail = "configured@example.com",
                    returnUrl = "vitrinakit-test://configured",
                ),
            )
        }
        return afterLaunch()
    }

    override fun close() = Unit
}

private fun hostedBoundaryHttpClient(
    checkoutReachedBoundary: CompletableDeferred<Unit>,
    releaseCheckout: CompletableDeferred<Unit>,
): VitrinaHttpClient = object : VitrinaHttpClient {
    override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse = when (request.path) {
        "/api/v1/subscriber/identify" -> VitrinaHttpResponse(
            HttpStatusOk,
            identifyResponseJson(request.headers.getValue("X-Vitrina-Subscriber-Id")),
        )
        "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, paywallJson)
        "/api/v1/checkout/sessions" -> {
            checkoutReachedBoundary.complete(Unit)
            releaseCheckout.await()
            VitrinaHttpResponse(HttpStatusCreated, checkoutJson)
        }
        else -> error("Unexpected request: ${request.path}")
    }
}

private suspend fun makeIgnoredHostedPurchase(
    product: PaywallProduct,
): VitrinaKitResult<VitrinaKitPurchase> = VitrinaKit.makePurchase(
    product = product,
    userId = "ignored",
    receiptEmail = "ignored@example.com",
    returnUrl = "ignored://return",
)

private fun VitrinaHttpRequest.jsonBodyValue(name: String): String {
    val parsed = Json.parseToJsonElement(body.orEmpty()).jsonObject
    return parsed.getValue(name).jsonPrimitive.content
}

private const val HttpStatusOk = 200
private const val HttpStatusCreated = 201
private const val HttpStatusBadRequest = 400
private const val HttpStatusUnauthorized = 401
private const val HttpStatusNotFound = 404
private const val HttpStatusConflict = 409
private const val HttpStatusServiceUnavailable = 503

private val json = Json { encodeDefaults = true }

private suspend fun cachedMonthlyProduct(placementId: String = "main"): PaywallProduct =
    assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(VitrinaKit.getPaywall(placementId)).value.products.single()

private fun checkoutSessionRequest(
    placementKey: String = "main",
    productReference: String = "premium_monthly",
    returnUrl: String = "vitrina://done",
): CheckoutSessionRequest = CheckoutSessionRequest(
    placementKey = placementKey,
    productReference = productReference,
    returnUrl = returnUrl,
)

private fun monthlyProduct(): PaywallProduct = PaywallProduct(
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

private val paywallJson = json.encodeToString(
    Paywall(
        placementKey = "main",
        products = listOf(monthlyProduct()),
    ),
)

private val checkoutJson = json.encodeToString(
    hostedPurchase(),
)

private fun hostedPurchase(): CheckoutSession = CheckoutSession(
    id = "session-1",
    paymentId = "payment-1",
    providerPaymentId = "pay_1",
    confirmationUrl = "https://pay.example/confirm",
    status = "pending",
    expiresAt = "2026-07-22T12:00:00Z",
    reused = false,
)

private val subscriberJson = json.encodeToString(
    SubscriberState(
        externalUserId = "user-1",
        hasAccess = true,
        entitlements = listOf(
            SubscriberEntitlementState(
                key = "premium_access",
                status = SubscriptionStatus.ACTIVE,
                hasAccess = true,
                expiresAt = "2026-07-22T12:00:00Z",
                planKey = "premium",
                productKey = "premium_monthly",
                source = EntitlementSource.YOOKASSA,
                autoRenewEnabled = true,
            ),
        ),
    ),
)

private fun identifyResponseJson(userId: String, merged: Boolean = false): String =
    """{"merged":$merged,"subscriber":${subscriberJson.replace("user-1", userId)}}"""


private const val purchaseAttemptJson = """{"reference":"attempt-1","status":"provider_ready","expires_at":"2026-08-06T12:00:00Z","capability":"google_play","presentation":{"product_id":"store-product","price_id":"store-offer","package_name":"ru.example.app","account_binding":"opaque-binding"}}"""

private val purchaseSuccessJson = """{"attempt":{"reference":"attempt-1","status":"succeeded","expires_at":"2026-08-06T12:00:00Z","capability":"google_play","presentation":{"product_id":"store-product","price_id":"store-offer","package_name":"ru.example.app","account_binding":"opaque-binding"}},"pending":false,"profile":$subscriberJson}"""

private val restoreSuccessJson = """{"purchases":[{"reference":"restored-1","status":"duplicate_coverage","replayed":true}],"profile":$subscriberJson}"""

private const val errorJson = """{"error":"failed"}"""
private const val receiptEmailRequiredJson = """{"error":"receipt_email_required"}"""
private const val invalidReceiptEmailJson = """{"error":"invalid_receipt_email"}"""
private const val activeSubscriptionJson = """{"error":"checkout_active_subscription_exists"}"""
private const val emailVerificationRequiredJson =
    """{"error":"email_verification_required","message":"A verified email is required before checkout."}"""
