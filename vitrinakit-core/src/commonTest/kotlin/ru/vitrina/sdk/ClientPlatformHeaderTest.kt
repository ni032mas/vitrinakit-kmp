package ru.vitrina.sdk

import kotlinx.coroutines.test.runTest
import ru.vitrina.sdk.cache.SubscriberCacheKey
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.http.VitrinaHttpResponse
import ru.vitrina.sdk.platform.VitrinaClientPlatform
import ru.vitrina.sdk.platform.currentClientPlatform
import ru.vitrina.sdk.platform.headerValue
import ru.vitrina.sdk.purchase.SubscriberScope
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * One hosted provider shop takes payments from every platform, so the API can only tell them apart
 * by the platform each request names. Every call names it, purchase starts and checkouts included.
 */
class ClientPlatformHeaderTest {

    @Test
    fun wireValuesAreTheSerialNames() {
        assertEquals(
            listOf("ios", "android", "macos", "windows", "linux"),
            VitrinaClientPlatform.entries.map { platform -> platform.headerValue },
        )
    }

    @Test
    fun purchaseStartAndCheckoutCarryThePlatformOfTheRunningTarget() = runTest {
        val expected = assertNotNull(currentClientPlatform(), "every target the tests run on names a platform")
        val http = RecordingHttpClient()
        val client = VitrinaClient(
            config = VitrinaConfig(publishableKey = "pk_test", installationId = "installation-1"),
            httpClient = http,
        )

        client.startPurchase(
            scope = SubscriberScope(
                cacheKey = SubscriberCacheKey("production", "subscriber-1", "opaque-session"),
                sessionToken = "opaque-session",
            ),
            placementId = "main",
            productReference = "premium_monthly",
            capability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
            idempotencyKey = "start-1",
        )
        client.createCheckoutSession(
            request = CheckoutSessionRequest(
                placementKey = "main",
                productReference = "premium_monthly",
                returnUrl = "vitrina://done",
            ),
            externalUserId = "user-1",
            subscriberSession = null,
            idempotencyKey = "checkout-1",
        )

        assertEquals(
            listOf("/api/v1/purchase-attempts", "/api/v1/checkout/sessions"),
            http.requests.map { request -> request.path },
        )
        http.requests.forEach { request ->
            assertEquals(expected.headerValue, request.headers["X-Vitrina-Client-Platform"], request.path)
        }
    }

    private class RecordingHttpClient : VitrinaHttpClient {
        val requests = mutableListOf<VitrinaHttpRequest>()

        override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
            requests += request
            return VitrinaHttpResponse(statusCode = HttpStatusServerError, body = "{}")
        }
    }

    private companion object {
        const val HttpStatusServerError = 500
    }
}
