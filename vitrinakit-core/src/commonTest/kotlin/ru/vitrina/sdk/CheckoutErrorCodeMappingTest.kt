package ru.vitrina.sdk

import kotlinx.coroutines.test.runTest
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.http.VitrinaHttpResponse
import ru.vitrina.sdk.model.VitrinaCheckoutErrorCode
import ru.vitrina.sdk.model.VitrinaError
import ru.vitrina.sdk.model.VitrinaResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A checkout refusal from `POST /api/v1/checkout/sessions` reaches the app as a typed checkout
 * error, never as a failure to open the payment page.
 *
 * Bodies are RFC 9457 Problem Details, the way the API sends them; the older `{"error": ...}` form
 * the client also reads is covered in [VitrinaClientTest].
 */
class CheckoutErrorCodeMappingTest {

    @Test
    fun eachKnownCheckoutCodeMapsToItsTypedCodeAndKeepsTheServerCode() = runTest {
        val cases = listOf(
            Triple(HttpStatusBadRequest, "receipt_email_required", VitrinaCheckoutErrorCode.RECEIPT_EMAIL_REQUIRED),
            Triple(HttpStatusBadRequest, "invalid_receipt_email", VitrinaCheckoutErrorCode.INVALID_RECEIPT_EMAIL),
            Triple(
                HttpStatusConflict,
                "checkout_active_subscription_exists",
                VitrinaCheckoutErrorCode.ACTIVE_SUBSCRIPTION_EXISTS,
            ),
            Triple(
                HttpStatusConflict,
                "email_verification_required",
                VitrinaCheckoutErrorCode.EMAIL_VERIFICATION_REQUIRED,
            ),
            Triple(HttpStatusConflict, "purchase_in_progress", VitrinaCheckoutErrorCode.PURCHASE_IN_PROGRESS),
        )

        cases.forEach { (status, wireCode, expected) ->
            val error = checkoutError(statusCode = status, body = problem(status = status, code = wireCode))

            assertEquals(expected, error.code, wireCode)
            assertEquals(wireCode, error.rawCode, wireCode)
        }
    }

    @Test
    fun purchaseInProgressIsTypedWhetherOrNotTheServerNamesTheBlockingPurchase() = runTest {
        val bodies = listOf(
            problem(
                status = HttpStatusConflict,
                code = "purchase_in_progress",
                meta = """{"blocking_billing_mode":"recurring","blocking_until":"2026-10-15T12:15:00Z"}""",
            ),
            problem(status = HttpStatusConflict, code = "purchase_in_progress"),
        )

        bodies.forEach { body ->
            val error = checkoutError(statusCode = HttpStatusConflict, body = body)

            assertEquals(VitrinaCheckoutErrorCode.PURCHASE_IN_PROGRESS, error.code)
            assertEquals("purchase_in_progress", error.rawCode)
        }
    }

    @Test
    fun anUnrecognizedCheckoutCodeIsUnknownAndKeepsTheRawCode() = runTest {
        listOf(HttpStatusBadRequest, HttpStatusConflict).forEach { status ->
            val error = checkoutError(statusCode = status, body = problem(status = status, code = "future_checkout_code"))

            assertEquals(VitrinaCheckoutErrorCode.UNKNOWN, error.code)
            assertEquals("future_checkout_code", error.rawCode)
        }
    }

    @Test
    fun aRefusalWithoutAReadableCodeStaysAProviderErrorWithTheServerBody() = runTest {
        val bodies = listOf(
            """{"detail":"No code was supplied."}""",
            """{"code":"","error":"   "}""",
            """{"code":{"nested":true}}""",
            "not json",
        )

        bodies.forEach { body ->
            val failure = assertIs<VitrinaResult.Failure>(createCheckout(statusCode = HttpStatusConflict, body = body))

            assertEquals(VitrinaError.Provider(body), failure.error, body)
        }
    }

    private suspend fun checkoutError(statusCode: Int, body: String): VitrinaError.Checkout =
        assertIs<VitrinaError.Checkout>(
            assertIs<VitrinaResult.Failure>(createCheckout(statusCode = statusCode, body = body)).error,
        )

    private suspend fun createCheckout(statusCode: Int, body: String) = VitrinaClient(
        config = VitrinaConfig(publishableKey = "pk_test", installationId = "installation-1"),
        httpClient = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest) = VitrinaHttpResponse(statusCode, body)
        },
    ).createCheckoutSession(
        request = CheckoutSessionRequest(
            placementKey = "main",
            productReference = "premium_monthly",
            returnUrl = "app://return",
        ),
        externalUserId = "customer-1",
        subscriberSession = null,
        idempotencyKey = "key-1",
    )

    private fun problem(status: Int, code: String, meta: String? = null): String = buildString {
        append("""{"type":"https://api.vitrinakit.ru/problems/$code","title":"Checkout refused",""")
        append(""""status":$status,"detail":"The checkout was refused.","instance":"/api/v1/checkout/sessions",""")
        append(""""code":"$code","request_id":"request-1"""")
        if (meta != null) append(""","meta":$meta""")
        append("}")
    }

    private companion object {
        const val HttpStatusBadRequest = 400
        const val HttpStatusConflict = 409
    }
}
