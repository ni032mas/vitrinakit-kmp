package ru.vitrina.sdk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpMethod
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.http.VitrinaHttpResponse
import ru.vitrina.sdk.model.BillingIntervalUnit
import ru.vitrina.sdk.model.CheckoutSession
import ru.vitrina.sdk.model.Entitlement
import ru.vitrina.sdk.model.Paywall
import ru.vitrina.sdk.model.PaywallConfig
import ru.vitrina.sdk.model.PaywallProduct
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.model.SubscriptionState
import ru.vitrina.sdk.model.SubscriptionStatus
import ru.vitrina.sdk.model.VitrinaEnvironment
import ru.vitrina.sdk.model.VitrinaError
import ru.vitrina.sdk.model.VitrinaResult

class VitrinaClientTest {
    @Test
    fun fetchPaywallSerializesUserContext() = runTest {
        val http = FakeHttpClient(
            response = VitrinaHttpResponse(
                statusCode = HttpStatusOk,
                body = paywallJson,
            ),
        )
        val client = newClient(http = http)

        client.fetchPaywall(
            placementKey = "Main",
            userContext = UserContext(
                externalUserId = "user-1",
                attributes = mapOf("country" to "RU", "cohort" to "café trial"),
            ),
        )

        val request = http.singleRequest()
        assertEquals(
            "/api/v1/paywall/main?external_user_id=user-1&country=RU&cohort=caf%C3%A9%20trial",
            request.path,
        )
        assertEquals(VitrinaHttpMethod.GET, request.method)
    }

    @Test
    fun fetchPaywallUsesPublishableKey() = runTest {
        val http = FakeHttpClient(
            response = VitrinaHttpResponse(
                statusCode = HttpStatusOk,
                body = paywallJson,
            ),
        )
        val client = newClient(http = http)

        val result = client.fetchPaywall(
            placementKey = "main",
            userContext = UserContext(externalUserId = "user-1"),
        )

        assertIs<VitrinaResult.Success<Paywall>>(result)
        assertEquals("PublishableKey pk_test", http.singleRequest().headers["Authorization"])
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
            CheckoutSessionRequest(
                externalUserId = "user-1",
                productId = "product-1",
                priceId = "price-1",
                returnUrl = "vitrina://done",
            ),
        )

        val success = assertIs<VitrinaResult.Success<CheckoutSession>>(result)
        assertEquals("https://pay.example/confirm", success.value.confirmationUrl)
        assertEquals("/api/v1/checkout/sessions", http.singleRequest().path)
        assertEquals(VitrinaHttpMethod.POST, http.singleRequest().method)
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

        val result = client.refreshSubscriber(externalUserId = "user-1")

        val success = assertIs<VitrinaResult.Success<SubscriberState>>(result)
        assertEquals(true, success.value.hasActive)
        assertEquals("full_access", success.value.entitlements.single().key)
        assertEquals("/api/v1/subscriber/user-1", http.singleRequest().path)
    }

    @Test
    fun networkFailureMapsToTypedError() = runTest {
        val client = newClient(http = FakeHttpClient(error = IllegalStateException("offline")))

        val result = client.fetchPaywall(
            placementKey = "main",
            userContext = UserContext(externalUserId = "user-1"),
        )

        val failure = assertIs<VitrinaResult.Failure>(result)
        assertIs<VitrinaError.Network>(failure.error)
    }

    @Test
    fun cachedFallbackIsReturnedWhenConfigured() {
        val client = newClient()
        val fallback = Paywall(
            placementKey = "main",
            paywallId = "fallback",
            config = PaywallConfig(template = "fallback"),
            fallbackConfig = PaywallConfig(template = "fallback"),
            products = emptyList(),
        )

        client.cacheFallbackPaywall(placementKey = "main", paywall = fallback)

        assertEquals(fallback, client.loadFallbackPaywall(placementKey = "MAIN"))
    }

    @Test
    fun httpErrorsMapToTypedErrors() = runTest {
        val auth = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusUnauthorized, errorJson)),
        ).fetchPaywall("main", UserContext(externalUserId = "user-1"))
        val provider = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusConflict, errorJson)),
        ).createCheckoutSession(
            CheckoutSessionRequest("user-1", "product-1", "price-1", "vitrina://done"),
        )
        val subscription = newClient(
            http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusNotFound, errorJson)),
        ).refreshSubscriber(externalUserId = "user-1")

        assertIs<VitrinaError.Auth>(assertIs<VitrinaResult.Failure>(auth).error)
        assertIs<VitrinaError.Provider>(assertIs<VitrinaResult.Failure>(provider).error)
        assertIs<VitrinaError.Subscription>(assertIs<VitrinaResult.Failure>(subscription).error)
    }

    @Test
    fun invalidConfigurationMapsToTypedError() = runTest {
        val client = VitrinaClient(
            config = VitrinaConfig(
                appId = "",
                publishableKey = "pk_test",
                baseUrl = "https://api.vitrinakit.ru",
                environment = VitrinaEnvironment.SANDBOX,
            ),
            httpClient = FakeHttpClient(),
        )

        val result = client.refreshSubscriber(externalUserId = "user-1")

        assertIs<VitrinaError.Configuration>(assertIs<VitrinaResult.Failure>(result).error)
    }
}

private fun newClient(http: VitrinaHttpClient = FakeHttpClient()): VitrinaClient = VitrinaClient(
    config = VitrinaConfig(
        appId = "app-1",
        publishableKey = "pk_test",
        baseUrl = "https://api.vitrinakit.ru/",
        environment = VitrinaEnvironment.SANDBOX,
    ),
    httpClient = http,
)

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

private const val HttpStatusOk = 200
private const val HttpStatusCreated = 201
private const val HttpStatusUnauthorized = 401
private const val HttpStatusNotFound = 404
private const val HttpStatusConflict = 409

private val json = Json { encodeDefaults = true }

private val paywallJson = json.encodeToString(
    Paywall(
        placementKey = "main",
        paywallId = "paywall-1",
        config = PaywallConfig(template = "default"),
        fallbackConfig = PaywallConfig(template = "fallback"),
        products = listOf(
            PaywallProduct(
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
            ),
        ),
    ),
)

private val checkoutJson = json.encodeToString(
    CheckoutSession(
        paymentId = "payment-1",
        providerPaymentId = "pay_1",
        confirmationUrl = "https://pay.example/confirm",
        status = "pending",
    ),
)

private val subscriberJson = json.encodeToString(
    SubscriberState(
        userId = "user-1",
        hasActive = true,
        subscriptions = listOf(
            SubscriptionState(
                status = SubscriptionStatus.ACTIVE,
                entitlements = listOf(Entitlement(key = "full_access", name = "Full access")),
            ),
        ),
    ),
)

private const val errorJson = """{"error":"failed"}"""
