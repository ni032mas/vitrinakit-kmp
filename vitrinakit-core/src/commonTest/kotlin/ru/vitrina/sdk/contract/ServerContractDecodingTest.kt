package ru.vitrina.sdk.contract

import kotlinx.coroutines.test.runTest
import ru.vitrina.sdk.purchase.SubscriberScope
import ru.vitrina.sdk.VitrinaClient
import ru.vitrina.sdk.VitrinaConfig
import ru.vitrina.sdk.model.VitrinaResult
import ru.vitrina.sdk.cache.SubscriberCacheKey
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.http.VitrinaHttpResponse
import ru.vitrina.sdk.CheckoutSessionRequest
import ru.vitrina.sdk.model.CheckoutSession
import ru.vitrina.sdk.model.Paywall
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.model.SubscriptionStatus
import ru.vitrina.sdk.purchase.PurchaseApiResult
import ru.vitrina.sdk.purchase.PurchaseAttempt
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAttemptStatus
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feeds representative server responses through the real [VitrinaClient] decoding path.
 *
 * The client's JSON configuration and its response models are private, so these tests go through
 * the client itself: a response the released models cannot read fails here instead of on a device.
 */
class ServerContractDecodingTest {

    @Test
    fun oneTimeSubscriberProfileDecodes() = runTest {
        val state = subscriber(ServerContractFixtures.SUBSCRIBER_ME_ONE_TIME)

        assertEquals("customer-1", state.externalUserId)
        assertTrue(state.hasAccess)
        val subscription = assertNotNullSubscription(state)
        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
        assertFalse(subscription.autoRenewEnabled)
        assertNull(subscription.paymentMethod)
        assertNull(subscription.nextChargeAt)
        assertFalse(state.entitlements.single().autoRenewEnabled)
    }

    @Test
    fun extendedProfileDecodes() = runTest {
        val state = subscriber(ServerContractFixtures.SUBSCRIBER_ME_EXTENDED)

        assertEquals("2026-12-14T12:00:00Z", assertNotNullSubscription(state).currentPeriodEnd)
    }

    @Test
    fun recurringProfileDecodes() = runTest {
        val subscription = assertNotNullSubscription(subscriber(ServerContractFixtures.SUBSCRIBER_ME_RECURRING))

        assertTrue(subscription.autoRenewEnabled)
        assertEquals("4242", subscription.paymentMethod?.last4)
        assertEquals("2026-11-14T12:00:00Z", subscription.nextChargeAt)
    }

    @Test
    fun twoRowProfileDecodes() = runTest {
        val state = subscriber(ServerContractFixtures.SUBSCRIBER_ME_TWO_ROWS)

        assertTrue(assertNotNullSubscription(state).autoRenewEnabled)
        assertFalse(state.entitlements.single().autoRenewEnabled)
    }

    @Test
    fun paywallDecodes() = runTest {
        val paywall = assertIs<VitrinaResult.Success<Paywall>>(
            client(ServerContractFixtures.PAYWALL_MAIN).fetchPaywall(
                placementKey = "main",
                externalUserId = "customer-1",
                subscriberSession = null,
            ),
        ).value

        assertEquals("main", paywall.placementKey)
        assertEquals("premium_monthly", paywall.products.single().productKey)
    }

    @Test
    fun storePurchaseStartsDecode() = runTest {
        val fixtures = mapOf(
            ServerContractFixtures.PURCHASE_START_GOOGLE_FRESH to VitrinaKitPurchaseCapability.GOOGLE_PLAY,
            ServerContractFixtures.PURCHASE_START_GOOGLE_REUSE to VitrinaKitPurchaseCapability.GOOGLE_PLAY,
            ServerContractFixtures.PURCHASE_START_GOOGLE_REPLAY to VitrinaKitPurchaseCapability.GOOGLE_PLAY,
            ServerContractFixtures.PURCHASE_START_RUSTORE_FRESH to VitrinaKitPurchaseCapability.RUSTORE,
        )

        fixtures.forEach { (body, capability) ->
            val attempt = assertIs<PurchaseApiResult.Success<PurchaseAttempt>>(
                client(body, statusCode = CREATED).startPurchase(
                    scope = scope(),
                    placementId = "main",
                    productReference = "premium_monthly",
                    capability = capability,
                    idempotencyKey = "key-1",
                ),
            ).value

            assertEquals(VitrinaKitPurchaseAttemptStatus.PROVIDER_READY, attempt.status)
            assertEquals(capability, attempt.capability)
            assertEquals("33333333-3333-3333-3333-333333333333", attempt.reference)
        }
    }

    @Test
    fun attemptViewDecodes() = runTest {
        val attempt = assertIs<PurchaseApiResult.Success<PurchaseAttempt>>(
            client(ServerContractFixtures.PURCHASE_ATTEMPT_VIEW_GOOGLE).getPurchase(
                scope = scope(),
                attemptReference = "33333333-3333-3333-3333-333333333333",
            ),
        ).value

        assertEquals(VitrinaKitPurchaseAttemptStatus.PENDING, attempt.status)
        assertEquals(VitrinaKitPurchaseCapability.GOOGLE_PLAY, attempt.capability)
    }

    @Test
    fun hostedAttemptViewDecodesOnReadAndOnCancel() = runTest {
        val reference = "33333333-3333-3333-3333-333333333333"
        val body = ServerContractFixtures.PURCHASE_ATTEMPT_VIEW_HOSTED_CANCELLED
        val read = assertIs<PurchaseApiResult.Success<PurchaseAttempt>>(
            client(body).getPurchase(scope = scope(), attemptReference = reference),
        ).value
        val cancelled = assertIs<PurchaseApiResult.Success<PurchaseAttempt>>(
            client(body).cancelPurchase(
                externalUserId = null,
                subscriberSession = "opaque-session",
                attemptReference = reference,
                idempotencyKey = "key-1",
            ),
        ).value

        listOf(read, cancelled).forEach { attempt ->
            assertEquals(reference, attempt.reference)
            assertEquals(VitrinaKitPurchaseCapability.YOOKASSA_HOSTED, attempt.capability)
            assertEquals(VitrinaKitPurchaseAttemptStatus.CANCELLED, attempt.status)
            assertEquals("client_cancelled", attempt.reason)
        }
    }

    @Test
    fun hostedCheckoutSessionDecodes() = runTest {
        val session = assertIs<VitrinaResult.Success<CheckoutSession>>(
            client(ServerContractFixtures.CHECKOUT_SESSION_HOSTED, statusCode = CREATED).createCheckoutSession(
                request = CheckoutSessionRequest(
                    placementKey = "main",
                    productReference = "premium_monthly",
                    returnUrl = "app://return",
                ),
                externalUserId = "customer-1",
                subscriberSession = null,
                idempotencyKey = "key-1",
            ),
        ).value

        assertEquals("https://pay.test", session.confirmationUrl)
        assertEquals("33333333-3333-3333-3333-333333333333", session.purchaseAttemptReference)
        assertEquals("pending", session.status)
        assertEquals("pay_1", session.providerPaymentId)
        assertEquals("2026-12-31T18:00:00Z", session.expiresAt)
        assertFalse(session.reused)
    }

    private suspend fun subscriber(body: String): SubscriberState = assertIs<VitrinaResult.Success<SubscriberState>>(
        client(body).refreshSubscriber(externalUserId = "customer-1", subscriberSession = null),
    ).value

    private fun assertNotNullSubscription(state: SubscriberState) =
        assertIs<ru.vitrina.sdk.model.SubscriberSubscription>(state.subscription)

    private fun scope() = SubscriberScope(
        cacheKey = SubscriberCacheKey("production", "app-1", "subscriber-1"),
        sessionToken = "opaque-session",
    )

    private fun client(body: String, statusCode: Int = OK) = VitrinaClient(
        config = VitrinaConfig(publishableKey = "pk_test", installationId = "installation-1"),
        httpClient = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest) = VitrinaHttpResponse(statusCode, body)
        },
    )

    private companion object {
        const val OK = 200
        const val CREATED = 201
    }
}
