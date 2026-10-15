@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk

import kotlinx.coroutines.test.runTest
import ru.vitrina.sdk.contract.ServerContractFixtures
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.http.VitrinaHttpResponse
import ru.vitrina.sdk.installation.VitrinaKitInstallationIdStorage
import ru.vitrina.sdk.model.VitrinaCheckoutErrorCode
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitPaywall
import ru.vitrina.sdk.model.VitrinaKitPurchase
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.purchase.VitrinaKitHostedCancellation
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationRequest
import ru.vitrina.sdk.purchase.VitrinaKitHostedPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitHostedPurchaseRequest
import ru.vitrina.sdk.purchase.VitrinaKitHostedRestoreRequest
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseError
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseErrorCode
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitRestoreResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the core-owned hosted operations hand a hosted adapter, driven through the public facade
 * against an in-memory transport.
 */
class HostedCheckoutFacadeTest {
    @AfterTest
    fun tearDown() {
        VitrinaKit.resetForTesting()
    }

    @Test
    fun checkoutRefusalReachesTheAdapterAsATypedCheckoutErrorWithTheRawCode() = runTest {
        val cases = listOf(
            "purchase_in_progress" to VitrinaCheckoutErrorCode.PURCHASE_IN_PROGRESS,
            "future_checkout_code" to VitrinaCheckoutErrorCode.UNKNOWN,
        )

        cases.forEach { (wireCode, expected) ->
            val adapter = CapturingHostedAdapter()
            purchaseThrough(
                adapter = adapter,
                http = HostedRoutes(checkout = VitrinaHttpResponse(HttpStatusConflict, problem(code = wireCode))),
            )

            val error = assertIs<VitrinaKitError.Checkout>(
                assertIs<VitrinaKitResult.Failure>(adapter.checkout, wireCode).error,
            )
            assertEquals(expected, error.code, wireCode)
            assertEquals(wireCode, error.rawCode, wireCode)
        }
    }

    @Test
    fun cancellingAHostedCheckoutReadsTheHostedAttemptTheServerReturns() = runTest {
        val adapter = CapturingHostedAdapter()
        val http = HostedRoutes(
            checkout = VitrinaHttpResponse(HttpStatusCreated, ServerContractFixtures.CHECKOUT_SESSION_HOSTED),
        )

        purchaseThrough(adapter = adapter, http = http)

        assertEquals(
            VitrinaKitResult.Success(VitrinaKitHostedCancellation(cancelled = true, reason = "client_cancelled")),
            adapter.cancellation,
        )
        assertEquals("/api/v1/purchase-attempts/$AttemptReference/cancel", http.requests.last().path)
    }

    private suspend fun purchaseThrough(adapter: CapturingHostedAdapter, http: HostedRoutes) {
        VitrinaKit.resetForTesting()
        assertIs<VitrinaKitResult.Success<Unit>>(
            VitrinaKit.activate(
                VitrinaKitConfig.Builder("pk_test")
                    .withInstallationIdStorage(FixedInstallationIdStorage())
                    .withHttpClient(http)
                    .withHostedCheckoutAdapter(adapter)
                    .build(),
            ),
        )
        assertIs<VitrinaKitResult.Success<*>>(VitrinaKit.setSubscriberSession("opaque-session"))
        val product = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(VitrinaKit.getPaywall("main"))
            .value.products.single()

        assertIs<VitrinaKitPurchaseResult.Cancelled>(VitrinaKit.purchase(product))
    }

    private fun problem(code: String): String =
        """{"type":"https://api.vitrinakit.ru/problems/$code","title":"Checkout refused","status":409,""" +
            """"detail":"The checkout was refused.","instance":"/api/v1/checkout/sessions","code":"$code",""" +
            """"request_id":"request-1"}"""

    private companion object {
        const val HttpStatusOk = 200
        const val HttpStatusCreated = 201
        const val HttpStatusConflict = 409
        const val AttemptReference = "33333333-3333-3333-3333-333333333333"
        const val InactiveProfileJson = """{"external_user_id":"customer-1","has_access":false,"entitlements":[]}"""
    }

    private class HostedRoutes(private val checkout: VitrinaHttpResponse) : VitrinaHttpClient {
        val requests = mutableListOf<VitrinaHttpRequest>()

        override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
            requests += request
            return when (request.path) {
                "/api/v1/subscriber/me" -> VitrinaHttpResponse(HttpStatusOk, InactiveProfileJson)
                "/api/v1/paywall/main" -> VitrinaHttpResponse(HttpStatusOk, ServerContractFixtures.PAYWALL_MAIN)
                "/api/v1/checkout/sessions" -> checkout
                "/api/v1/purchase-attempts/$AttemptReference/cancel" -> VitrinaHttpResponse(
                    HttpStatusOk,
                    ServerContractFixtures.PURCHASE_ATTEMPT_VIEW_HOSTED_CANCELLED,
                )
                else -> error("Unexpected request path: ${request.path}")
            }
        }
    }
}

/** Runs the core-owned checkout and cancel operations once and keeps what they returned. */
private class CapturingHostedAdapter : VitrinaKitHostedPurchaseAdapter {
    var checkout: VitrinaKitResult<VitrinaKitPurchase>? = null
        private set
    var cancellation: VitrinaKitResult<VitrinaKitHostedCancellation>? = null
        private set

    override suspend fun purchase(request: VitrinaKitHostedPurchaseRequest): VitrinaKitPurchaseResult {
        checkout = request.checkout.create(receiptEmail = "buyer@example.com", returnUrl = "app://return")
        cancellation = request.cancel.cancel(attemptReference = "33333333-3333-3333-3333-333333333333")
        return VitrinaKitPurchaseResult.Cancelled
    }

    override suspend fun restore(request: VitrinaKitHostedRestoreRequest): VitrinaKitRestoreResult =
        VitrinaKitRestoreResult.Failure(
            VitrinaKitPurchaseError(
                code = VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE,
                message = "Restore is not exercised here.",
                retryable = false,
                supportReference = null,
            ),
        )

    override suspend fun purchaseForBoundIdentity(
        request: VitrinaKitHostedMigrationRequest,
    ): VitrinaKitResult<VitrinaKitPurchase> =
        request.checkout.create(receiptEmail = "buyer@example.com", returnUrl = "app://return")

    override fun close() = Unit
}

private class FixedInstallationIdStorage : VitrinaKitInstallationIdStorage {
    private var value: String? = "installation-1"

    override fun read(): String? = value

    override fun write(installationId: String) {
        value = installationId
    }
}
