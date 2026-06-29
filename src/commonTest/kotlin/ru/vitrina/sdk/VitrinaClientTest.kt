package ru.vitrina.sdk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import ru.vitrina.sdk.model.EntitlementSource
import ru.vitrina.sdk.model.SubscriberEntitlementState
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.model.SubscriptionStatus
import ru.vitrina.sdk.model.VitrinaError
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitPaywall
import ru.vitrina.sdk.model.VitrinaKitPurchase
import ru.vitrina.sdk.model.VitrinaKitProfile
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.model.VitrinaResult

class VitrinaClientTest {
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
        val http = FakeHttpClient(
            response = VitrinaHttpResponse(
                statusCode = HttpStatusOk,
                body = paywallJson,
            ),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withAppId("app-1")
                .withHttpClient(http)
                .build(),
        )

        val result = VitrinaKit.getPaywall(
            placementId = "main",
            userId = "user-1",
        )

        val success = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(result)
        assertEquals("main", success.value.placementKey)
        assertEquals("PublishableKey pk_test", http.singleRequest().headers["Authorization"])
        assertEquals(
            "$VitrinaKitApiBaseUrl/api/v1/paywall/main?external_user_id=user-1",
            http.singleRequest().url,
        )
        assertEquals(VitrinaKitApiEnvironment.name, http.singleRequest().headers["X-Vitrina-Environment"])
    }

    @Test
    fun facadeReturnsProductsFromPaywall() {
        val paywall = Paywall(
            placementKey = "main",
            paywallId = "paywall-1",
            config = PaywallConfig(template = "default"),
            fallbackConfig = PaywallConfig(template = "fallback"),
            products = listOf(monthlyProduct()),
        )

        val result = VitrinaKit.getPaywallProducts(paywall)

        val success = assertIs<VitrinaKitResult.Success<List<PaywallProduct>>>(result)
        assertEquals("price-1", success.value.single().priceId)
    }

    @Test
    fun facadeCreatesPurchaseFromPaywallProduct() = runTest {
        val http = FakeHttpClient(
            response = VitrinaHttpResponse(
                statusCode = HttpStatusCreated,
                body = checkoutJson,
            ),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withAppId("app-1")
                .withHttpClient(http)
                .build(),
        )

        val result = VitrinaKit.makePurchase(
            product = monthlyProduct(),
            userId = "user-1",
            receiptEmail = "buyer@example.com",
            returnUrl = "vitrina://done",
        )

        val success = assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(result)
        assertEquals("session-1", success.value.id)
        assertEquals("https://pay.example/confirm", success.value.confirmationUrl)
        assertEquals("/api/v1/checkout/sessions", http.singleRequest().path)
        assertEquals("buyer@example.com", http.singleRequest().jsonBodyValue("receipt_email"))
    }

    @Test
    fun facadeGetsProfile() = runTest {
        val http = FakeHttpClient(
            response = VitrinaHttpResponse(
                statusCode = HttpStatusOk,
                body = subscriberJson,
            ),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withAppId("app-1")
                .withHttpClient(http)
                .build(),
        )

        val result = VitrinaKit.getProfile(userId = "user-1")

        val success = assertIs<VitrinaKitResult.Success<VitrinaKitProfile>>(result)
        assertEquals(true, success.value.hasAccess)
        assertEquals("premium_access", success.value.entitlements.single().key)
    }

    @Test
    fun facadeBlockingWrapperFetchesPaywall() {
        val http = FakeHttpClient(
            response = VitrinaHttpResponse(
                statusCode = HttpStatusOk,
                body = paywallJson,
            ),
        )
        VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withAppId("app-1")
                .withHttpClient(http)
                .build(),
        )

        val result = VitrinaKit.getPaywallBlocking(
            placementId = "main",
            userId = "user-1",
        )

        assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(result)
    }

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
                receiptEmail = "buyer@example.com",
                returnUrl = "vitrina://done",
            ),
        )

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
        assertEquals("buyer@example.com", http.singleRequest().jsonBodyValue("receipt_email"))
    }

    @Test
    fun checkoutSessionRequestSerializesReceiptEmail() {
        val payload = json.encodeToString(
            CheckoutSessionRequest(
                externalUserId = "user-1",
                productId = "product-1",
                priceId = "price-1",
                receiptEmail = "buyer@example.com",
                returnUrl = "vitrina://done",
            ),
        )

        val body = Json.parseToJsonElement(payload).jsonObject
        assertEquals("user-1", body.getValue("external_user_id").jsonPrimitive.content)
        assertEquals("product-1", body.getValue("product_id").jsonPrimitive.content)
        assertEquals("price-1", body.getValue("price_id").jsonPrimitive.content)
        assertEquals("buyer@example.com", body.getValue("receipt_email").jsonPrimitive.content)
        assertEquals("vitrina://done", body.getValue("return_url").jsonPrimitive.content)
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

        val result = client.refreshSubscriber(externalUserId = "user-1")

        val success = assertIs<VitrinaResult.Success<SubscriberState>>(result)
        assertEquals("user-1", success.value.externalUserId)
        assertEquals(true, success.value.hasAccess)
        assertEquals("premium_access", success.value.entitlements.single().key)
        assertEquals("2026-07-22T12:00:00Z", success.value.entitlements.single().expiresAt)
        assertEquals(EntitlementSource.YOOKASSA, success.value.entitlements.single().source)
        assertEquals(true, success.value.entitlements.single().autoRenewEnabled)
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
            CheckoutSessionRequest("user-1", "product-1", "price-1", "buyer@example.com", "vitrina://done"),
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
                appId = "app-1",
                publishableKey = "",
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

private fun VitrinaHttpRequest.jsonBodyValue(name: String): String {
    val parsed = Json.parseToJsonElement(body.orEmpty()).jsonObject
    return parsed.getValue(name).jsonPrimitive.content
}

private const val HttpStatusOk = 200
private const val HttpStatusCreated = 201
private const val HttpStatusUnauthorized = 401
private const val HttpStatusNotFound = 404
private const val HttpStatusConflict = 409

private val json = Json { encodeDefaults = true }

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
        paywallId = "paywall-1",
        config = PaywallConfig(template = "default"),
        fallbackConfig = PaywallConfig(template = "fallback"),
        products = listOf(monthlyProduct()),
    ),
)

private val checkoutJson = json.encodeToString(
    CheckoutSession(
        id = "session-1",
        paymentId = "payment-1",
        providerPaymentId = "pay_1",
        confirmationUrl = "https://pay.example/confirm",
        status = "pending",
        expiresAt = "2026-07-22T12:00:00Z",
        reused = false,
    ),
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

private const val errorJson = """{"error":"failed"}"""
