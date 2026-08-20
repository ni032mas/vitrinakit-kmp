package ru.vitrina.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The request body documented in `obsidian/docs/architecture/integration-contract.md`
 * under "Checkout", byte-for-byte. It has no `external_user_id` or `receipt_email` fields:
 * subscriber identity comes from the `X-Vitrina-Subscriber-Id` header or the bearer session,
 * and the receipt address is the server-side verified one.
 */
private const val DocumentedCheckoutSessionRequestJson = """
{
  "placement_key": "main",
  "product_reference": "premium_monthly",
  "return_url": "litofit://subscription/return"
}
"""

class CheckoutSessionContractTest {
    private val json = Json

    @Test
    fun serializedRequestMatchesDocumentedContract() {
        val request = CheckoutSessionRequest(
            placementKey = "main",
            productReference = "premium_monthly",
            returnUrl = "litofit://subscription/return",
        )

        val serialized = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val documented = json.parseToJsonElement(DocumentedCheckoutSessionRequestJson).jsonObject

        assertEquals(documented, serialized)
    }
}
