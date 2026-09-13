@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpMethod
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.http.VitrinaHttpResponse
import ru.vitrina.sdk.installation.VitrinaKitInstallationIdStorage
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.model.SubscriptionStatus
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitPaymentMethodState
import ru.vitrina.sdk.model.VitrinaKitProfile
import ru.vitrina.sdk.model.VitrinaKitPurchase
import ru.vitrina.sdk.model.VitrinaKitRenewalState
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationAdapter
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationRequest
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi

/**
 * Covers the subscription-management surface against the documented wire contract:
 * the `subscription` object on the subscriber profile, `cancel-renewal`, and `payment-method`.
 *
 * Every test drives the public facade through an in-memory transport. Nothing reaches a network.
 */
class SubscriptionManagementTest {
    @AfterTest
    fun tearDown() {
        VitrinaKit.resetForTesting()
    }

    @Test
    fun profileCarriesRenewalStateAndStoredPaymentMethod() = runTest {
        activateAndIdentify(SubscriptionHttpClient())

        val profile = assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value

        val subscription = assertNotNull(profile.subscription)
        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
        assertEquals(PeriodEnd, subscription.currentPeriodEnd)
        assertEquals(PeriodEnd, subscription.nextChargeAt)
        assertTrue(subscription.autoRenewEnabled)
        assertEquals("mir", subscription.paymentMethod?.brand)
        assertEquals("4242", subscription.paymentMethod?.last4)
        assertTrue(profile.entitlements.single().autoRenewEnabled)
    }

    @Test
    fun subscriptionWithoutStoredPaymentMethodDecodes() {
        val profile = wireJson.decodeFromString<SubscriberState>(subscriberProfileJson(paymentMethodStored = false))

        assertNull(assertNotNull(profile.subscription).paymentMethod)
    }

    @Test
    fun subscriptionOmittingTheRenewalFlagKeepsAutoRenewalOn() {
        val profile = wireJson.decodeFromString<SubscriberState>(MinimalSubscriptionProfileJson)

        val subscription = assertNotNull(profile.subscription)
        assertTrue(subscription.autoRenewEnabled)
        assertNull(subscription.nextChargeAt)
        assertNull(subscription.paymentMethod)
    }

    @Test
    fun subscriberWithoutSubscriptionDecodes() {
        val profile = wireJson.decodeFromString<SubscriberState>(NoSubscriptionProfileJson)

        assertNull(profile.subscription)
    }

    @Test
    fun cancelAutoRenewStopsRenewalAndKeepsAccessUntilThePeriodEnds() = runTest {
        val http = SubscriptionHttpClient()
        activateAndIdentify(http)

        val state = assertIs<VitrinaKitResult.Success<VitrinaKitRenewalState>>(VitrinaKit.cancelAutoRenew()).value

        assertFalse(state.autoRenewEnabled)
        assertEquals(PeriodEnd, state.currentPeriodEnd)
        val request = http.requests.single { candidate -> candidate.path == CancelRenewalPath }
        assertEquals(VitrinaHttpMethod.POST, request.method)
        assertNull(request.body)
        assertEquals("user-1", request.headers["X-Vitrina-Subscriber-Id"])
        assertTrue(request.headers.containsKey("Idempotency-Key"))

        val cached = assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value
        val subscription = assertNotNull(cached.subscription)
        assertFalse(subscription.autoRenewEnabled)
        assertNull(subscription.nextChargeAt)
        assertEquals(PeriodEnd, subscription.currentPeriodEnd)
        assertTrue(cached.hasAccess)
        assertEquals("mir", subscription.paymentMethod?.brand)
    }

    @Test
    fun repeatedCancelAutoRenewIsASuccessRatherThanAnError() = runTest {
        val http = SubscriptionHttpClient()
        activateAndIdentify(http)

        val first = assertIs<VitrinaKitResult.Success<VitrinaKitRenewalState>>(VitrinaKit.cancelAutoRenew())
        val second = assertIs<VitrinaKitResult.Success<VitrinaKitRenewalState>>(VitrinaKit.cancelAutoRenew())

        assertEquals(first.value, second.value)
        assertEquals(2, http.requests.count { candidate -> candidate.path == CancelRenewalPath })
        assertEquals(
            2,
            http.requests.filter { candidate -> candidate.path == CancelRenewalPath }
                .mapNotNull { candidate -> candidate.headers["Idempotency-Key"] }
                .distinct()
                .size,
        )
    }

    @Test
    fun rejectedCancelAutoRenewSurfacesTheTypedSubscriptionError() = runTest {
        val http = SubscriptionHttpClient(
            cancelRenewalResponse = VitrinaHttpResponse(StatusConflict, NoRenewingSubscriptionJson),
        )
        activateAndIdentify(http)

        val failure = assertIs<VitrinaKitResult.Failure>(VitrinaKit.cancelAutoRenew())

        val error = assertIs<VitrinaKitError.Subscription>(failure.error)
        assertEquals("The subscriber has no subscription that renews.", error.message)
    }

    @Test
    fun unauthorizedCancelAutoRenewSurfacesTheTypedAuthError() = runTest {
        val http = SubscriptionHttpClient(
            cancelRenewalResponse = VitrinaHttpResponse(StatusUnauthorized, UnauthorizedJson),
        )
        activateAndIdentify(http)

        val failure = assertIs<VitrinaKitResult.Failure>(VitrinaKit.cancelAutoRenew())

        assertIs<VitrinaKitError.Auth>(failure.error)
    }

    @Test
    fun serverFailureOnCancelAutoRenewStaysANetworkError() = runTest {
        val http = SubscriptionHttpClient(
            cancelRenewalResponse = VitrinaHttpResponse(StatusServiceUnavailable, ServiceUnavailableJson),
        )
        activateAndIdentify(http)

        val failure = assertIs<VitrinaKitResult.Failure>(VitrinaKit.cancelAutoRenew())

        assertIs<VitrinaKitError.Network>(failure.error)
    }

    @Test
    fun detachPaymentMethodRemovesTheStoredCardAndStopsRenewal() = runTest {
        val http = SubscriptionHttpClient()
        activateAndIdentify(http)

        val state = assertIs<VitrinaKitResult.Success<VitrinaKitPaymentMethodState>>(
            VitrinaKit.detachPaymentMethod(),
        ).value

        assertNull(state.paymentMethod)
        assertFalse(state.autoRenewEnabled)
        val request = http.requests.single { candidate -> candidate.path == PaymentMethodPath }
        assertEquals(VitrinaHttpMethod.DELETE, request.method)
        assertNull(request.body)
        assertEquals("user-1", request.headers["X-Vitrina-Subscriber-Id"])
        assertTrue(request.headers.containsKey("Idempotency-Key"))

        val cached = assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(VitrinaKit.getProfile()).value
        val subscription = assertNotNull(cached.subscription)
        assertNull(subscription.paymentMethod)
        assertFalse(subscription.autoRenewEnabled)
        assertNull(subscription.nextChargeAt)
        assertTrue(cached.hasAccess)
    }

    @Test
    fun repeatedDetachPaymentMethodIsASuccessRatherThanAnError() = runTest {
        val http = SubscriptionHttpClient()
        activateAndIdentify(http)

        val first = assertIs<VitrinaKitResult.Success<VitrinaKitPaymentMethodState>>(VitrinaKit.detachPaymentMethod())
        val second = assertIs<VitrinaKitResult.Success<VitrinaKitPaymentMethodState>>(VitrinaKit.detachPaymentMethod())

        assertEquals(first.value, second.value)
        assertEquals(2, http.requests.count { candidate -> candidate.path == PaymentMethodPath })
    }

    @Test
    fun rejectedDetachPaymentMethodSurfacesTheTypedSubscriptionError() = runTest {
        val http = SubscriptionHttpClient(
            detachPaymentMethodResponse = VitrinaHttpResponse(StatusNotFound, NoPaymentMethodJson),
        )
        activateAndIdentify(http)

        val failure = assertIs<VitrinaKitResult.Failure>(VitrinaKit.detachPaymentMethod())

        val error = assertIs<VitrinaKitError.Subscription>(failure.error)
        assertEquals("The subscriber has no stored payment method.", error.message)
    }

    @Test
    fun detachedPaymentMethodSurvivesAServerRefresh() = runTest {
        val http = SubscriptionHttpClient()
        activateAndIdentify(http)

        VitrinaKit.detachPaymentMethod()
        val refreshed = assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(
            VitrinaKit.getProfile(forceRefresh = true),
        ).value

        val subscription = assertNotNull(refreshed.subscription)
        assertNull(subscription.paymentMethod)
        assertFalse(subscription.autoRenewEnabled)
    }

    @Test
    fun subscriptionManagementRequiresActivation() = runTest {
        VitrinaKit.resetForTesting()

        val cancel = assertIs<VitrinaKitResult.Failure>(VitrinaKit.cancelAutoRenew())
        val detach = assertIs<VitrinaKitResult.Failure>(VitrinaKit.detachPaymentMethod())

        assertIs<VitrinaKitError.Configuration>(cancel.error)
        assertIs<VitrinaKitError.Configuration>(detach.error)
    }
}

private suspend fun activateAndIdentify(http: VitrinaHttpClient) {
    VitrinaKit.resetForTesting()
    VitrinaKit.activate(
        VitrinaKitConfig.Builder("pk_test")
            .withInstallationIdStorage(SubscriptionInstallationIdStorage("installation-1"))
            .withHttpClient(http)
            .withHostedMigrationAdapter(NoopHostedMigrationAdapter())
            .build(),
    )
    assertIs<VitrinaKitResult.Success<*>>(VitrinaKit.identify("user-1"))
}

/**
 * An in-memory VitrinaKit API that keeps the subscription state the calls under test change.
 *
 * The cancel and detach routes answer the same way every time they are called, the way a server
 * that treats them as idempotent does; a repeat is not a special case here either.
 */
private class SubscriptionHttpClient(
    private val cancelRenewalResponse: VitrinaHttpResponse? = null,
    private val detachPaymentMethodResponse: VitrinaHttpResponse? = null,
) : VitrinaHttpClient {
    val requests = mutableListOf<VitrinaHttpRequest>()
    private var autoRenewEnabled = true
    private var paymentMethodStored = true

    override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
        requests += request
        return when (request.path) {
            IdentifyPath -> VitrinaHttpResponse(
                statusCode = StatusOk,
                body = """{"merged":false,"subscriber":${currentSubscriberJson()}}""",
            )

            ProfilePath -> VitrinaHttpResponse(statusCode = StatusOk, body = currentSubscriberJson())

            CancelRenewalPath -> cancelRenewalResponse ?: run {
                autoRenewEnabled = false
                VitrinaHttpResponse(
                    statusCode = StatusOk,
                    body = """{"auto_renew_enabled":false,"current_period_end":"$PeriodEnd"}""",
                )
            }

            PaymentMethodPath -> detachPaymentMethodResponse ?: run {
                autoRenewEnabled = false
                paymentMethodStored = false
                VitrinaHttpResponse(
                    statusCode = StatusOk,
                    body = """{"payment_method":null,"auto_renew_enabled":false}""",
                )
            }

            else -> error("Unexpected request: ${request.path}")
        }
    }

    private fun currentSubscriberJson(): String = subscriberProfileJson(
        autoRenewEnabled = autoRenewEnabled,
        paymentMethodStored = paymentMethodStored,
    )
}

private class NoopHostedMigrationAdapter : VitrinaKitHostedMigrationAdapter {
    override suspend fun purchaseForBoundIdentity(
        request: VitrinaKitHostedMigrationRequest,
    ): VitrinaKitResult<VitrinaKitPurchase> = request.checkout.create(
        receiptEmail = "receipts@example.com",
        returnUrl = "vitrinakit-test://done",
    )

    override fun close() = Unit
}

private class SubscriptionInstallationIdStorage(
    initialValue: String? = null,
) : VitrinaKitInstallationIdStorage {
    private var value = initialValue

    override fun read(): String? = value

    override fun write(installationId: String) {
        value = installationId
    }
}

private val wireJson = Json { ignoreUnknownKeys = true }

/** The documented subscriber payload, written out as the server sends it rather than re-encoded. */
private fun subscriberProfileJson(
    autoRenewEnabled: Boolean = true,
    paymentMethodStored: Boolean = true,
): String = """
{
  "external_user_id": "user-1",
  "has_access": true,
  "entitlements": [
    {
      "key": "premium_access",
      "status": "active",
      "has_access": true,
      "expires_at": "$PeriodEnd",
      "plan_key": "premium",
      "product_key": "premium_monthly",
      "source": "yookassa",
      "auto_renew_enabled": $autoRenewEnabled
    }
  ],
  "subscription": {
    "status": "active",
    "current_period_end": "$PeriodEnd",
    "next_charge_at": ${if (autoRenewEnabled) "\"$PeriodEnd\"" else "null"},
    "auto_renew_enabled": $autoRenewEnabled,
    "payment_method": ${if (paymentMethodStored) """{"brand":"mir","last4":"4242"}""" else "null"}
  }
}
"""

private const val MinimalSubscriptionProfileJson = """
{
  "external_user_id": "user-1",
  "has_access": true,
  "entitlements": [],
  "subscription": {
    "status": "active",
    "current_period_end": "2026-10-13T09:00:00Z"
  }
}
"""

private const val NoSubscriptionProfileJson = """
{
  "external_user_id": "user-1",
  "has_access": false,
  "entitlements": []
}
"""

private const val NoRenewingSubscriptionJson = """
{
  "type": "https://vitrina.example/errors/subscription-not-renewing",
  "title": "Subscription is not renewing",
  "status": 409,
  "detail": "The subscriber has no subscription that renews.",
  "instance": "/api/v1/subscriber/subscription/cancel-renewal",
  "code": "subscription_not_renewing",
  "request_id": "req-1"
}
"""

private const val NoPaymentMethodJson = """
{
  "type": "https://vitrina.example/errors/payment-method-not-found",
  "title": "Payment method not found",
  "status": 404,
  "detail": "The subscriber has no stored payment method.",
  "instance": "/api/v1/subscriber/subscription/payment-method",
  "code": "payment_method_not_found",
  "request_id": "req-2"
}
"""

private const val UnauthorizedJson = """{"detail":"The publishable key does not own this subscriber."}"""

private const val ServiceUnavailableJson = """{"detail":"The subscription service is unavailable."}"""

private const val PeriodEnd = "2026-10-13T09:00:00Z"

private const val IdentifyPath = "/api/v1/subscriber/identify"
private const val ProfilePath = "/api/v1/subscriber/me"
private const val CancelRenewalPath = "/api/v1/subscriber/subscription/cancel-renewal"
private const val PaymentMethodPath = "/api/v1/subscriber/subscription/payment-method"

private const val StatusOk = 200
private const val StatusUnauthorized = 401
private const val StatusNotFound = 404
private const val StatusConflict = 409
private const val StatusServiceUnavailable = 503
