@file:OptIn(
    ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package ru.vitrina.sdk.hosted

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import ru.vitrina.sdk.VitrinaKit
import ru.vitrina.sdk.VitrinaKitConfig
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.http.VitrinaHttpResponse
import ru.vitrina.sdk.model.VitrinaCheckoutErrorCode
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.model.CheckoutSession
import ru.vitrina.sdk.model.Entitlement
import ru.vitrina.sdk.model.EntitlementSource
import ru.vitrina.sdk.model.PaywallProduct
import ru.vitrina.sdk.model.SubscriberEntitlementState
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.model.SubscriptionStatus
import ru.vitrina.sdk.model.BillingIntervalUnit
import ru.vitrina.sdk.purchase.VitrinaKitHostedCancelOperation
import ru.vitrina.sdk.purchase.VitrinaKitHostedCancellation
import ru.vitrina.sdk.purchase.VitrinaKitHostedCheckoutOperation
import ru.vitrina.sdk.purchase.VitrinaKitHostedProfileOperation
import ru.vitrina.sdk.purchase.VitrinaKitHostedPurchaseRequest
import ru.vitrina.sdk.purchase.VitrinaKitHostedRestoreRequest
import ru.vitrina.sdk.purchase.VitrinaKitHostedPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationRequest
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseErrorCode
import ru.vitrina.sdk.purchase.VitrinaKitRestoreResult
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class HostedCheckoutAdapterTest {
    @AfterTest
    fun tearDown() {
        VitrinaKit.logout()
    }

    @Test
    fun hostedAdapterCompletesNormalFacadePurchaseOnlyAfterMatchingAuthoritativeProfile() = runTest {
        val http = HostedFacadeHttpClient()
        val launchedUrls = mutableListOf<String>()
        val adapter = HostedCheckoutAdapter(
            configuration = HostedCheckoutConfiguration(
                launcher = HostedCheckoutLauncher { request ->
                    launchedUrls += request.confirmationUrl
                    HostedCheckoutLauncherSignal.Returned(
                        HostedCheckoutReturnUri("vitrinakit-test://checkout-return"),
                    )
                },
                receiptEmail = { "buyer@example.com" },
                returnUrl = "vitrinakit-test://checkout-return",
                allowedReturnSchemes = setOf("vitrinakit-test"),
            ),
        )

        val activation = VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_public_test")
                .withHttpClient(http)
                .withHostedCheckoutAdapter(adapter)
                .build(),
        )
        assertIs<VitrinaKitResult.Success<Unit>>(activation)
        assertIs<VitrinaKitResult.Success<*>>(
            VitrinaKit.setSubscriberSession("opaque-session"),
        )
        val paywall = assertIs<VitrinaKitResult.Success<*>>(VitrinaKit.getPaywall("main"))
        val product = (paywall.value as ru.vitrina.sdk.model.VitrinaKitPaywall).products.single()

        val result = VitrinaKit.purchase(product)

        val success = assertIs<VitrinaKitPurchaseResult.Success>(result)
        assertEquals("attempt-1", success.purchaseReference)
        assertTrue(success.profile.hasAccess)
        assertEquals(listOf("https://pay.example/confirm"), launchedUrls)
        val checkout = http.requests.single { it.path == "/api/v1/checkout/sessions" }
        assertEquals("Bearer opaque-session", checkout.headers["Authorization"])
        assertFalse(checkout.headers.containsKey("X-Vitrina-Subscriber-Id"))
        assertTrue(checkout.body.orEmpty().contains("\"placement_key\":\"main\""))
        assertTrue(checkout.body.orEmpty().contains("\"product_reference\":\"premium_monthly\""))
        assertTrue(checkout.body.orEmpty().contains("vitrinakit-test://checkout-return"))
        assertFalse(checkout.body.orEmpty().contains("buyer@example.com"))
        assertFalse(checkout.body.orEmpty().contains("capability"))
        assertTrue(http.requests.none { it.path.startsWith("/api/v1/purchase-attempts") })
    }

    @Test
    fun invalidConfiguredReturnSchemeFailsBeforeCoreCheckoutPost() = runTest {
        var checkoutCalls = 0
        var receiptCalls = 0
        val adapter = HostedCheckoutAdapter(
            HostedCheckoutConfiguration(
                launcher = HostedCheckoutLauncher { HostedCheckoutLauncherSignal.Dismissed },
                receiptEmail = {
                    receiptCalls += 1
                    "buyer@example.com"
                },
                returnUrl = "unapproved://return",
                allowedReturnSchemes = setOf("vitrinakit-test"),
            ),
        )

        val result = adapter.purchase(
            hostedRequest(
                checkout = {
                    checkoutCalls += 1
                    checkoutSession()
                },
            ),
        )

        val failure = assertIs<VitrinaKitPurchaseResult.Failure>(result)
        assertEquals(VitrinaKitPurchaseErrorCode.INVALID_REQUEST, failure.error.code)
        assertEquals(0, receiptCalls)
        assertEquals(0, checkoutCalls)
    }

    @Test
    fun eachCheckoutErrorCodeReachesPurchaseAsItsOwnCodeWithServerMessage() = runTest {
        val cases = listOf(
            Triple(
                VitrinaCheckoutErrorCode.RECEIPT_EMAIL_REQUIRED,
                VitrinaKitPurchaseErrorCode.RECEIPT_EMAIL_REQUIRED,
                "Checkout requires a receipt delivery email address.",
            ),
            Triple(
                VitrinaCheckoutErrorCode.INVALID_RECEIPT_EMAIL,
                VitrinaKitPurchaseErrorCode.INVALID_RECEIPT_EMAIL,
                "Receipt email is invalid.",
            ),
            Triple(
                VitrinaCheckoutErrorCode.ACTIVE_SUBSCRIPTION_EXISTS,
                VitrinaKitPurchaseErrorCode.ACTIVE_ENTITLEMENT_EXISTS,
                "An active subscription already exists.",
            ),
            Triple(
                VitrinaCheckoutErrorCode.EMAIL_VERIFICATION_REQUIRED,
                VitrinaKitPurchaseErrorCode.EMAIL_VERIFICATION_REQUIRED,
                "A verified email is required before checkout.",
            ),
        )
        val adapter = HostedCheckoutAdapter(hostedConfiguration())

        cases.forEach { (checkoutCode, purchaseCode, serverMessage) ->
            val result = adapter.purchase(
                hostedRequestWithCheckoutFailure(
                    error = VitrinaKitError.Checkout(code = checkoutCode, message = serverMessage),
                ),
            )

            val failure = assertIs<VitrinaKitPurchaseResult.Failure>(result)
            assertEquals(purchaseCode, failure.error.code)
            assertEquals(serverMessage, failure.error.message)
        }
    }

    @Test
    fun nonHttpsConfirmationUrlNeverReachesLauncher() = runTest {
        var launches = 0
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    launches += 1
                    HostedCheckoutLauncherSignal.Dismissed
                },
            ),
        )

        val result = adapter.purchase(
            hostedRequest(checkout = { checkoutSession(confirmationUrl = "http://pay.example/confirm") }),
        )

        val failure = assertIs<VitrinaKitPurchaseResult.Failure>(result)
        assertEquals(VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED, failure.error.code)
        assertEquals(0, launches)
    }

    @Test
    fun hostlessHttpsConfirmationUrlNeverReachesLauncher() = runTest {
        var launches = 0
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    launches += 1
                    HostedCheckoutLauncherSignal.Dismissed
                },
            ),
        )

        val result = adapter.purchase(
            hostedRequest(checkout = { checkoutSession(confirmationUrl = "https://@") }),
        )

        val failure = assertIs<VitrinaKitPurchaseResult.Failure>(result)
        assertEquals(VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED, failure.error.code)
        assertEquals(0, launches)
    }

    @Test
    fun returnedDeepLinkPollsBoundedTimesAndCannotGrantAccessByNavigation() = runTest {
        var refreshCalls = 0
        val delays = mutableListOf<Long>()
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    HostedCheckoutLauncherSignal.Returned(
                        HostedCheckoutReturnUri("vitrinakit-test://return?secret=hidden"),
                    )
                },
                pollingPolicy = HostedCheckoutPollingPolicy(
                    maxRefreshAttempts = 3,
                    intervalMilliseconds = 250,
                ),
                pollingDelay = HostedCheckoutDelay { milliseconds -> delays += milliseconds },
            ),
        )

        val result = adapter.purchase(
            hostedRequest(
                profile = {
                    refreshCalls += 1
                    inactiveProfile()
                },
            ),
        )

        val pending = assertIs<VitrinaKitPurchaseResult.Pending>(result)
        assertEquals("attempt-1", pending.attemptReference)
        assertFalse(pending.profile?.hasAccess ?: true)
        assertEquals(3, refreshCalls)
        assertEquals(listOf(250L, 250L), delays)
    }

    @Test
    fun deterministicDismissalRefreshesOnceThenCancelsAndClearsResumeState() = runTest {
        val store = RecordingResumeStateStore()
        var refreshCalls = 0
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher { HostedCheckoutLauncherSignal.Dismissed },
                resumeStateStore = store,
            ),
        )

        val result = adapter.purchase(
            hostedRequest(
                profile = {
                    refreshCalls += 1
                    inactiveProfile()
                },
            ),
        )

        assertIs<VitrinaKitPurchaseResult.Cancelled>(result)
        assertEquals(1, refreshCalls)
        assertEquals(null, store.state)
    }

    @Test
    fun deterministicDismissalEndsTheAttemptOnTheServer() = runTest {
        val cancel = RecordingCancelOperation()
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher { HostedCheckoutLauncherSignal.Dismissed },
            ),
        )

        val result = adapter.purchase(hostedRequest(cancel = cancel))

        assertIs<VitrinaKitPurchaseResult.Cancelled>(result)
        assertEquals(1, cancel.calls)
        assertEquals("attempt-1", cancel.lastReference)
    }

    @Test
    fun grantedAccessAfterDismissalNeverCancelsTheAttempt() = runTest {
        val cancel = RecordingCancelOperation()
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher { HostedCheckoutLauncherSignal.Dismissed },
            ),
        )

        val result = adapter.purchase(hostedRequest(profile = { activeProfile() }, cancel = cancel))

        assertIs<VitrinaKitPurchaseResult.Success>(result)
        assertEquals(0, cancel.calls)
    }

    @Test
    fun anUnknownOutcomeNeverCancelsTheAttempt() = runTest {
        val cancel = RecordingCancelOperation()
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher { HostedCheckoutLauncherSignal.TimedOut },
            ),
        )

        val result = adapter.purchase(hostedRequest(cancel = cancel))

        assertIs<VitrinaKitPurchaseResult.Pending>(result)
        assertEquals(0, cancel.calls)
    }

    @Test
    fun aFailedCancelNeverChangesTheDismissalOutcome() = runTest {
        for (cancel in listOf(
            RecordingCancelOperation(
                outcome = VitrinaKitResult.Failure(VitrinaKitError.Network("Network request failed.")),
            ),
            RecordingCancelOperation(failure = IllegalStateException("transport exploded")),
        )) {
            val adapter = HostedCheckoutAdapter(
                hostedConfiguration(
                    launcher = HostedCheckoutLauncher { HostedCheckoutLauncherSignal.Dismissed },
                ),
            )

            val result = adapter.purchase(hostedRequest(cancel = cancel))

            assertIs<VitrinaKitPurchaseResult.Cancelled>(result)
            assertEquals(1, cancel.calls)
        }
    }

    @Test
    fun launcherFailureIsTypedAndDoesNotExposePlatformDetails() = runTest {
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    HostedCheckoutLauncherSignal.Failed(
                        HostedCheckoutLauncherError(HostedCheckoutLauncherErrorCode.OPEN_FAILED),
                    )
                },
            ),
        )

        val result = adapter.purchase(hostedRequest())

        val failure = assertIs<VitrinaKitPurchaseResult.Failure>(result)
        assertEquals(VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE, failure.error.code)
        assertFalse(failure.toString().contains("https://pay.example/confirm"))
    }

    @Test
    fun thrownBrowserOpenFailureClearsResumeStateSoServerReuseRelaunches() = runTest {
        val store = RecordingResumeStateStore()
        val failedAdapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    throw IllegalStateException("platform-secret-browser-detail")
                },
                resumeStateStore = store,
            ),
        )

        val failed = failedAdapter.purchase(hostedRequest())

        val failure = assertIs<VitrinaKitPurchaseResult.Failure>(failed)
        assertEquals(VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE, failure.error.code)
        assertFalse(failure.toString().contains("platform-secret-browser-detail"))
        assertEquals(null, store.state)

        var retryLaunches = 0
        val retriedAdapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    retryLaunches += 1
                    HostedCheckoutLauncherSignal.Returned(
                        HostedCheckoutReturnUri("vitrinakit-test://return"),
                    )
                },
                resumeStateStore = store,
            ),
        )
        val retried = retriedAdapter.purchase(
            hostedRequest(
                checkout = { checkoutSession(reused = true) },
                profile = { activeProfile() },
            ),
        )

        assertIs<VitrinaKitPurchaseResult.Success>(retried)
        assertEquals(1, retryLaunches)
    }

    @Test
    fun typedBrowserOpenFailureClearsResumeStateSoServerReuseRelaunches() = runTest {
        val store = RecordingResumeStateStore()
        val failedAdapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    HostedCheckoutLauncherSignal.Failed(
                        HostedCheckoutLauncherError(HostedCheckoutLauncherErrorCode.OPEN_FAILED),
                    )
                },
                resumeStateStore = store,
            ),
        )

        val failed = failedAdapter.purchase(hostedRequest())

        val failure = assertIs<VitrinaKitPurchaseResult.Failure>(failed)
        assertEquals(VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE, failure.error.code)
        assertEquals(null, store.state)

        var retryLaunches = 0
        val retriedAdapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    retryLaunches += 1
                    HostedCheckoutLauncherSignal.Returned(
                        HostedCheckoutReturnUri("vitrinakit-test://return"),
                    )
                },
                resumeStateStore = store,
            ),
        )
        val retried = retriedAdapter.purchase(
            hostedRequest(
                checkout = { checkoutSession(reused = true) },
                profile = { activeProfile() },
            ),
        )

        assertIs<VitrinaKitPurchaseResult.Success>(retried)
        assertEquals(1, retryLaunches)
    }

    @Test
    fun coroutineCancellationAfterCheckoutCreationPreservesDurableResumeState() = runTest {
        val store = RecordingResumeStateStore()
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher { throw kotlinx.coroutines.CancellationException("cancelled") },
                resumeStateStore = store,
            ),
        )

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            adapter.purchase(hostedRequest())
        }

        assertEquals("checkout-1", store.state?.checkoutReference)
    }

    @Test
    fun restartResumeRepostsCheckoutAndPollsWithoutOpeningBrowserAgain() = runTest {
        val store = RecordingResumeStateStore()
        var checkoutCalls = 0
        val first = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher { HostedCheckoutLauncherSignal.TimedOut },
                resumeStateStore = store,
                pollingPolicy = HostedCheckoutPollingPolicy(
                    maxRefreshAttempts = 1,
                    intervalMilliseconds = 100,
                ),
            ),
        )
        val firstResult = first.purchase(
            hostedRequest(
                checkout = {
                    checkoutCalls += 1
                    checkoutSession()
                },
            ),
        )
        assertIs<VitrinaKitPurchaseResult.Pending>(firstResult)
        assertEquals("checkout-1", store.state?.checkoutReference)
        assertFalse(store.state.toString().contains("pay.example"))

        var resumedLaunches = 0
        val resumed = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    resumedLaunches += 1
                    HostedCheckoutLauncherSignal.Dismissed
                },
                resumeStateStore = store,
            ),
        )
        val resumedResult = resumed.purchase(
            hostedRequest(
                checkout = {
                    checkoutCalls += 1
                    checkoutSession(reused = true)
                },
                profile = { activeProfile() },
            ),
        )

        assertIs<VitrinaKitPurchaseResult.Success>(resumedResult)
        assertEquals(2, checkoutCalls)
        assertEquals(0, resumedLaunches)
        assertEquals(null, store.state)
    }

    @Test
    fun matchingStoredReferenceDoesNotResumeUnlessServerMarksCheckoutReused() = runTest {
        val store = RecordingResumeStateStore().apply {
            state = HostedCheckoutResumeState(
                checkoutReference = "checkout-1",
                expiresAtEpochMilliseconds = 1_800_000_000_000,
            )
        }
        var launches = 0
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    launches += 1
                    HostedCheckoutLauncherSignal.Returned(
                        HostedCheckoutReturnUri("vitrinakit-test://return"),
                    )
                },
                resumeStateStore = store,
            ),
        )

        val result = adapter.purchase(
            hostedRequest(
                checkout = { checkoutSession(reused = false) },
                profile = { activeProfile() },
            ),
        )

        assertIs<VitrinaKitPurchaseResult.Success>(result)
        assertEquals(1, launches)
    }

    @Test
    fun hostedRestoreIsExactlyOneAuthoritativeProfileRefresh() = runTest {
        var refreshCalls = 0
        val adapter = HostedCheckoutAdapter(hostedConfiguration())

        val result = adapter.restore(
            VitrinaKitHostedRestoreRequest(
                profile = VitrinaKitHostedProfileOperation {
                    refreshCalls += 1
                    VitrinaKitResult.Success(activeProfile())
                },
            ),
        )

        val success = assertIs<VitrinaKitRestoreResult.Success>(result)
        assertTrue(success.profile.hasAccess)
        assertTrue(success.purchases.isEmpty())
        assertEquals(1, refreshCalls)
    }

    @Test
    fun publicHostedValuesRedactUrlsCallbacksAndResumeReferences() {
        val configuration = hostedConfiguration()
        val request = HostedCheckoutLaunchRequest("https://pay.example/confirm?token=secret")
        val returned = HostedCheckoutLauncherSignal.Returned(
            HostedCheckoutReturnUri("vitrinakit-test://return?token=secret"),
        )
        val state = HostedCheckoutResumeState(
            checkoutReference = "checkout-secret",
            expiresAtEpochMilliseconds = 1_800_000_000_000,
        )

        assertFalse(configuration.toString().contains("buyer@example.com"))
        assertFalse(request.toString().contains("pay.example"))
        assertFalse(returned.toString().contains("token=secret"))
        assertFalse(state.toString().contains("checkout-secret"))
    }

    @Test
    fun coreRejectsHostedSuccessWhenRefreshedProfileDoesNotMatchSelectedProductOrEntitlement() = runTest {
        val unrelatedProfile =
            """{"external_user_id":"user-1","has_access":true,"entitlements":[{"key":"unrelated","status":"active","has_access":true,"product_key":"other_product","source":"yookassa","auto_renew_enabled":true}]}"""
        val http = HostedFacadeHttpClient(authoritativeProfileJson = unrelatedProfile)
        val adapter = object : VitrinaKitHostedPurchaseAdapter {
            override suspend fun purchase(request: VitrinaKitHostedPurchaseRequest): VitrinaKitPurchaseResult {
                val profile = assertIs<VitrinaKitResult.Success<*>>(request.profile.refresh())
                    .value as SubscriberState
                return VitrinaKitPurchaseResult.Success(
                    purchaseReference = "fabricated-success",
                    profile = profile,
                )
            }

            override suspend fun restore(request: VitrinaKitHostedRestoreRequest): VitrinaKitRestoreResult =
                VitrinaKitRestoreResult.NoPurchases(inactiveProfile())

            override suspend fun purchaseForBoundIdentity(
                request: VitrinaKitHostedMigrationRequest,
            ): VitrinaKitResult<CheckoutSession> = request.checkout.create(
                receiptEmail = "configured@example.com",
                returnUrl = "vitrinakit-test://return",
            )

            override fun close() = Unit
        }
        assertIs<VitrinaKitResult.Success<Unit>>(
            VitrinaKit.activate(
                VitrinaKitConfig.Builder("pk_public_test")
                    .withHttpClient(http)
                    .withHostedCheckoutAdapter(adapter)
                    .build(),
            ),
        )
        assertIs<VitrinaKitResult.Success<*>>(
            VitrinaKit.setSubscriberSession("opaque-session"),
        )
        val paywall = assertIs<VitrinaKitResult.Success<*>>(VitrinaKit.getPaywall("main"))
        val product = (paywall.value as ru.vitrina.sdk.model.VitrinaKitPaywall).products.single()

        val result = VitrinaKit.purchase(product)

        val failure = assertIs<VitrinaKitPurchaseResult.Failure>(result)
        assertEquals(VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED, failure.error.code)
    }

    @Test
    fun registeringSameHostedAdapterThroughNewAndLegacyBuildersIsRejectedAsTwoAdapters() {
        val adapter = HostedCheckoutAdapter(hostedConfiguration())

        val result = VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_public_test")
                .withHostedCheckoutAdapter(adapter)
                .withHostedMigrationAdapter(adapter)
                .build(),
        )

        assertIs<VitrinaKitResult.Failure>(result)
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyFacadeKeepsCheckoutSessionResultAndIgnoresEveryPerCallHostedField() = runTest {
        val http = HostedFacadeHttpClient()
        val adapter = HostedCheckoutAdapter(hostedConfiguration())
        assertIs<VitrinaKitResult.Success<Unit>>(
            VitrinaKit.activate(
                VitrinaKitConfig.Builder("pk_public_test")
                    .withHttpClient(http)
                    .withHostedCheckoutAdapter(adapter)
                    .build(),
            ),
        )
        assertIs<VitrinaKitResult.Success<*>>(
            VitrinaKit.setSubscriberSession("opaque-session"),
        )
        val paywall = assertIs<VitrinaKitResult.Success<*>>(VitrinaKit.getPaywall("main"))
        val product = (paywall.value as ru.vitrina.sdk.model.VitrinaKitPaywall).products.single()

        val result = VitrinaKit.makePurchase(
            product = product,
            userId = "ignored-user",
            receiptEmail = "ignored@example.com",
            returnUrl = "ignored://return",
        )

        val success = assertIs<VitrinaKitResult.Success<CheckoutSession>>(result)
        assertEquals("checkout-1", success.value.id)
        val body = http.requests.single { it.path == "/api/v1/checkout/sessions" }.body.orEmpty()
        assertTrue(body.contains("\"placement_key\":\"main\""))
        assertTrue(body.contains("\"product_reference\":\"premium_monthly\""))
        assertTrue(body.contains("vitrinakit-test://return"))
        assertFalse(body.contains("buyer@example.com"))
        assertFalse(body.contains("ignored-user"))
        assertFalse(body.contains("ignored@example.com"))
        assertFalse(body.contains("ignored://return"))
    }

    @Test
    fun normalHostedRestoreUsesFacadeProfileRefreshWithoutCheckoutOrLauncher() = runTest {
        val http = HostedFacadeHttpClient()
        var launches = 0
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    launches += 1
                    HostedCheckoutLauncherSignal.Dismissed
                },
            ),
        )
        assertIs<VitrinaKitResult.Success<Unit>>(
            VitrinaKit.activate(
                VitrinaKitConfig.Builder("pk_public_test")
                    .withHttpClient(http)
                    .withHostedCheckoutAdapter(adapter)
                    .build(),
            ),
        )
        assertIs<VitrinaKitResult.Success<*>>(
            VitrinaKit.setSubscriberSession("opaque-session"),
        )

        val result = VitrinaKit.restorePurchases()

        assertIs<VitrinaKitRestoreResult.Success>(result)
        assertEquals(0, launches)
        assertTrue(http.requests.none { it.path == "/api/v1/checkout/sessions" })
        assertEquals(2, http.requests.count { it.path == "/api/v1/subscriber/me" })
    }

    @Test
    fun concurrentHostedPurchasesNeverPresentTwoBrowserFlows() = runTest {
        val firstLaunchReached = CompletableDeferred<Unit>()
        val releaseFirstLaunch = CompletableDeferred<Unit>()
        var launches = 0
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    launches += 1
                    if (launches == 1) {
                        firstLaunchReached.complete(Unit)
                        releaseFirstLaunch.await()
                        HostedCheckoutLauncherSignal.Returned(
                            HostedCheckoutReturnUri("vitrinakit-test://return"),
                        )
                    } else {
                        HostedCheckoutLauncherSignal.Dismissed
                    }
                },
            ),
        )
        val first = async { adapter.purchase(hostedRequest(profile = { activeProfile() })) }
        firstLaunchReached.await()

        val concurrent = adapter.purchase(hostedRequest())

        val failure = assertIs<VitrinaKitPurchaseResult.Failure>(concurrent)
        assertEquals(VitrinaKitPurchaseErrorCode.PURCHASE_IN_PROGRESS, failure.error.code)
        assertEquals(1, launches)
        releaseFirstLaunch.complete(Unit)
        assertIs<VitrinaKitPurchaseResult.Success>(first.await())
    }

    @Test
    fun concurrentFacadeHostedPurchaseFailsBeforeWaitingForFirstLauncher() = runTest {
        val firstLaunchReached = CompletableDeferred<Unit>()
        val releaseFirstLaunch = CompletableDeferred<Unit>()
        val http = HostedFacadeHttpClient()
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher {
                    firstLaunchReached.complete(Unit)
                    releaseFirstLaunch.await()
                    HostedCheckoutLauncherSignal.Returned(
                        HostedCheckoutReturnUri("vitrinakit-test://return"),
                    )
                },
            ),
        )
        assertIs<VitrinaKitResult.Success<Unit>>(
            VitrinaKit.activate(
                VitrinaKitConfig.Builder("pk_public_test")
                    .withHttpClient(http)
                    .withHostedCheckoutAdapter(adapter)
                    .build(),
            ),
        )
        assertIs<VitrinaKitResult.Success<*>>(
            VitrinaKit.setSubscriberSession("opaque-session"),
        )
        val paywall = assertIs<VitrinaKitResult.Success<*>>(VitrinaKit.getPaywall("main"))
        val product = (paywall.value as ru.vitrina.sdk.model.VitrinaKitPaywall).products.single()
        val first = async { VitrinaKit.purchase(product) }
        firstLaunchReached.await()

        val concurrent = async { VitrinaKit.purchase(product) }
        runCurrent()

        assertTrue(concurrent.isCompleted)
        val failure = assertIs<VitrinaKitPurchaseResult.Failure>(concurrent.await())
        assertEquals(VitrinaKitPurchaseErrorCode.PURCHASE_IN_PROGRESS, failure.error.code)
        releaseFirstLaunch.complete(Unit)
        assertIs<VitrinaKitPurchaseResult.Success>(first.await())
    }

    @Test
    fun closeSynchronouslyClearsDurableResumeState() = runTest {
        val store = RecordingResumeStateStore()
        val adapter = HostedCheckoutAdapter(
            hostedConfiguration(
                launcher = HostedCheckoutLauncher { HostedCheckoutLauncherSignal.TimedOut },
                pollingPolicy = HostedCheckoutPollingPolicy(
                    maxRefreshAttempts = 1,
                    intervalMilliseconds = 1,
                ),
                resumeStateStore = store,
            ),
        )
        assertIs<VitrinaKitPurchaseResult.Pending>(adapter.purchase(hostedRequest()))
        assertEquals("checkout-1", store.state?.checkoutReference)

        adapter.close()

        assertEquals(null, store.state)
    }
}

private fun hostedConfiguration(
    launcher: HostedCheckoutLauncher = HostedCheckoutLauncher {
        HostedCheckoutLauncherSignal.Returned(HostedCheckoutReturnUri("vitrinakit-test://return"))
    },
    pollingPolicy: HostedCheckoutPollingPolicy = HostedCheckoutPollingPolicy(
        maxRefreshAttempts = 2,
        intervalMilliseconds = 1,
    ),
    pollingDelay: HostedCheckoutDelay = HostedCheckoutDelay {},
    resumeStateStore: HostedCheckoutResumeStateStore = RecordingResumeStateStore(),
): HostedCheckoutConfiguration = HostedCheckoutConfiguration(
    launcher = launcher,
    receiptEmail = { "buyer@example.com" },
    returnUrl = "vitrinakit-test://return",
    allowedReturnSchemes = setOf("vitrinakit-test"),
    pollingPolicy = pollingPolicy,
    pollingDelay = pollingDelay,
    resumeStateStore = resumeStateStore,
    clock = HostedCheckoutClock { 1_780_000_000_000 },
)

private fun hostedRequest(
    checkout: suspend () -> CheckoutSession = { checkoutSession() },
    profile: suspend () -> SubscriberState = { inactiveProfile() },
    cancel: RecordingCancelOperation = RecordingCancelOperation(),
): VitrinaKitHostedPurchaseRequest = VitrinaKitHostedPurchaseRequest(
    product = hostedProduct(),
    checkout = VitrinaKitHostedCheckoutOperation { _, _ -> VitrinaKitResult.Success(checkout()) },
    profile = VitrinaKitHostedProfileOperation { VitrinaKitResult.Success(profile()) },
    cancel = cancel,
)

private fun hostedRequestWithCheckoutFailure(
    error: VitrinaKitError,
): VitrinaKitHostedPurchaseRequest = VitrinaKitHostedPurchaseRequest(
    product = hostedProduct(),
    checkout = VitrinaKitHostedCheckoutOperation { _, _ -> VitrinaKitResult.Failure(error) },
    profile = VitrinaKitHostedProfileOperation { VitrinaKitResult.Success(inactiveProfile()) },
    cancel = RecordingCancelOperation(),
)

/** Records what the adapter asked the server to cancel, and answers what the test configures. */
private class RecordingCancelOperation(
    private val outcome: VitrinaKitResult<VitrinaKitHostedCancellation> =
        VitrinaKitResult.Success(VitrinaKitHostedCancellation(cancelled = true, reason = "card_declined")),
    private val failure: Throwable? = null,
) : VitrinaKitHostedCancelOperation {
    var calls: Int = 0
        private set
    var lastReference: String? = null
        private set

    override suspend fun cancel(attemptReference: String): VitrinaKitResult<VitrinaKitHostedCancellation> {
        calls += 1
        lastReference = attemptReference
        failure?.let { throw it }
        return outcome
    }
}

private fun checkoutSession(
    confirmationUrl: String = "https://pay.example/confirm",
    reused: Boolean = false,
): CheckoutSession = CheckoutSession(
    id = "checkout-1",
    purchaseAttemptReference = "attempt-1",
    paymentId = "payment-1",
    providerPaymentId = "provider-payment-1",
    confirmationUrl = confirmationUrl,
    status = "pending",
    expiresAt = "2026-08-06T12:00:00Z",
    reused = reused,
)

private fun hostedProduct(): PaywallProduct = PaywallProduct(
    productId = "product-1",
    productKey = "premium_monthly",
    productName = "Premium",
    planId = "plan-1",
    planKey = "premium",
    planName = "Premium",
    priceId = "price-1",
    amountMinor = 9_900,
    currency = "RUB",
    intervalUnit = BillingIntervalUnit.MONTH,
    intervalCount = 1,
    trialIntervalUnit = null,
    trialIntervalCount = 0,
    highlighted = true,
    sortOrder = 0,
    entitlements = listOf(Entitlement(key = "premium", name = "Premium")),
)

private fun inactiveProfile(): SubscriberState = SubscriberState(
    externalUserId = "user-1",
    hasAccess = false,
    entitlements = emptyList(),
)

private fun activeProfile(): SubscriberState = SubscriberState(
    externalUserId = "user-1",
    hasAccess = true,
    entitlements = listOf(
        SubscriberEntitlementState(
            key = "premium",
            status = SubscriptionStatus.ACTIVE,
            hasAccess = true,
            expiresAt = null,
            planKey = "premium",
            productKey = "premium_monthly",
            source = EntitlementSource.YOOKASSA,
            autoRenewEnabled = true,
            inactiveReason = null,
        ),
    ),
)

private class RecordingResumeStateStore : HostedCheckoutResumeStateStore {
    var state: HostedCheckoutResumeState? = null

    override suspend fun load(): HostedCheckoutResumeState? = state

    override suspend fun save(state: HostedCheckoutResumeState) {
        this.state = state
    }

    override fun clear() {
        state = null
    }
}

private class HostedFacadeHttpClient(
    private val authoritativeProfileJson: String = ActiveProfileJson,
) : VitrinaHttpClient {
    val requests = mutableListOf<VitrinaHttpRequest>()
    private var profileRequests = 0

    override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
        requests += request
        return when (request.path) {
            "/api/v1/subscriber/me" -> {
                profileRequests += 1
                VitrinaHttpResponse(200, if (profileRequests == 1) InactiveProfileJson else authoritativeProfileJson)
            }
            "/api/v1/paywall/main" -> VitrinaHttpResponse(200, PaywallJson)
            "/api/v1/checkout/sessions" -> VitrinaHttpResponse(201, CheckoutJson)
            else -> error("Unexpected request path: ${request.path}")
        }
    }
}

private const val InactiveProfileJson =
    """{"external_user_id":"user-1","has_access":false,"entitlements":[]}"""
private const val ActiveProfileJson =
    """{"external_user_id":"user-1","has_access":true,"entitlements":[{"key":"premium","status":"active","has_access":true,"product_key":"premium_monthly","source":"yookassa","auto_renew_enabled":true}]}"""
private const val PaywallJson =
    """{"placement_key":"main","products":[{"product_id":"product-1","product_key":"premium_monthly","product_name":"Premium","plan_id":"plan-1","plan_key":"premium","plan_name":"Premium","price_id":"price-1","amount_minor":9900,"currency":"RUB","interval_unit":"month","interval_count":1,"trial_interval_count":0,"highlighted":true,"sort_order":0,"entitlements":[{"key":"premium","name":"Premium"}]}]}"""
private const val CheckoutJson =
    """{"id":"checkout-1","purchase_attempt_reference":"attempt-1","payment_id":"payment-1","provider_payment_id":"provider-payment-1","confirmation_url":"https://pay.example/confirm","status":"pending","expires_at":"2026-08-06T12:00:00Z","reused":false}"""
