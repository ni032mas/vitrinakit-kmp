package ru.vitrina.sdk.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The response body documented in `obsidian/docs/architecture/integration-contract.md`
 * under "Paywall Retrieval", byte-for-byte. It has no `paywall_id`, `config`, or
 * `fallback_config` fields: "The response has no paywall configuration, experiment,
 * variant, or fallback fields."
 */
private const val DocumentedPaywallResponseJson = """
{
  "placement_key": "main",
  "products": [
    {
      "product_id": "uuid",
      "product_key": "premium_monthly",
      "product_name": "Premium Monthly",
      "plan_id": "uuid",
      "plan_key": "premium",
      "plan_name": "Premium",
      "price_id": "uuid",
      "amount_minor": 19900,
      "currency": "RUB",
      "interval_unit": "month",
      "interval_count": 1,
      "highlighted": true,
      "sort_order": 0,
      "entitlements": [
        {"key": "premium_access", "name": "Premium access"}
      ]
    }
  ]
}
"""

class PaywallContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun documentedPaywallResponseDeserializesIntoPaywall() {
        val paywall = json.decodeFromString<Paywall>(DocumentedPaywallResponseJson)

        assertEquals("main", paywall.placementKey)
        val product = paywall.products.single()
        assertEquals("uuid", product.productId)
        assertEquals("premium_monthly", product.productKey)
        assertEquals("Premium Monthly", product.productName)
        assertEquals("premium", product.planKey)
        assertEquals("Premium", product.planName)
        assertEquals(19900L, product.amountMinor)
        assertEquals("RUB", product.currency)
        assertEquals(BillingIntervalUnit.MONTH, product.intervalUnit)
        assertEquals(1, product.intervalCount)
        assertTrue(product.highlighted)
        assertEquals(0, product.sortOrder)
        assertEquals(
            listOf(Entitlement(key = "premium_access", name = "Premium access")),
            product.entitlements,
        )
    }
}
