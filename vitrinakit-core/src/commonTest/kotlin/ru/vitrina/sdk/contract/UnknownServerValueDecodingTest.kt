@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.contract

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.vitrina.sdk.VitrinaClient
import ru.vitrina.sdk.VitrinaConfig
import ru.vitrina.sdk.cache.SubscriberCacheKey
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.http.VitrinaHttpResponse
import ru.vitrina.sdk.model.BillingIntervalUnit
import ru.vitrina.sdk.model.EntitlementSource
import ru.vitrina.sdk.model.Paywall
import ru.vitrina.sdk.model.PaywallProduct
import ru.vitrina.sdk.model.SubscriberEntitlementState
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.model.SubscriptionStatus
import ru.vitrina.sdk.model.VitrinaResult
import ru.vitrina.sdk.purchase.PurchaseApiResult
import ru.vitrina.sdk.purchase.PurchaseAttempt
import ru.vitrina.sdk.purchase.PurchaseRestoreResponse
import ru.vitrina.sdk.purchase.SubscriberScope
import ru.vitrina.sdk.purchase.VitrinaKitProviderProof
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAttemptStatus
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseCapability
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A value a newer server sends that this SDK version does not know degrades that one field to
 * `UNKNOWN`; it never fails the whole response the field arrived in.
 */
class UnknownServerValueDecodingTest {

    @Test
    fun unknownStoreCapabilityDecodesAsUnknown() = runTest {
        val body = ServerContractFixtures.PAYWALL_MAIN_WITH_STORE_PRODUCTS
            .replace("\"google_play\"", "\"future_store\"")
        val paywall = assertIs<VitrinaResult.Success<Paywall>>(
            client(body).fetchPaywall(
                placementKey = "main",
                externalUserId = "customer-1",
                subscriberSession = null,
            ),
        ).value

        assertEquals(VitrinaKitPurchaseCapability.UNKNOWN, paywall.products.single().storeProducts.first().capability)
        assertEquals("premium_monthly", paywall.products.single().productKey)
    }

    @Test
    fun profileWithAnUnrecognizedStatusAndSourceStillDecodes() = runTest {
        val state = assertIs<VitrinaResult.Success<SubscriberState>>(
            client(UnknownValuesProfileJson).refreshSubscriber(externalUserId = "customer-1", subscriberSession = null),
        ).value

        assertTrue(state.hasAccess)
        val (future, known) = state.entitlements
        assertEquals(SubscriptionStatus.UNKNOWN, future.status)
        assertEquals(EntitlementSource.UNKNOWN, future.source)
        assertTrue(future.hasAccess)
        assertEquals(SubscriptionStatus.ACTIVE, known.status)
        assertEquals(EntitlementSource.YOOKASSA, known.source)
        assertEquals(SubscriptionStatus.UNKNOWN, assertNotNull(state.subscription).status)
    }

    @Test
    fun paywallWithAnUnrecognizedIntervalUnitStillDecodes() = runTest {
        val paywall = assertIs<VitrinaResult.Success<Paywall>>(
            client(UnknownIntervalPaywallJson).fetchPaywall(
                placementKey = "main",
                externalUserId = "customer-1",
                subscriberSession = null,
            ),
        ).value

        val (future, monthly) = paywall.products
        assertEquals(BillingIntervalUnit.UNKNOWN, future.intervalUnit)
        assertEquals(BillingIntervalUnit.UNKNOWN, future.trialIntervalUnit)
        assertEquals(BillingIntervalUnit.MONTH, monthly.intervalUnit)
        assertNull(monthly.trialIntervalUnit)
    }

    @Test
    fun purchaseAttemptWithAnUnrecognizedStatusAndCapabilityStillDecodes() = runTest {
        listOf("future_channel", "").forEach { capability ->
            val attempt = assertIs<PurchaseApiResult.Success<PurchaseAttempt>>(
                client(attemptJson(status = "future_status", capability = capability))
                    .getPurchase(scope = scope(), attemptReference = AttemptReference),
                capability,
            ).value

            assertEquals(VitrinaKitPurchaseAttemptStatus.UNKNOWN, attempt.status, capability)
            assertEquals(VitrinaKitPurchaseCapability.UNKNOWN, attempt.capability, capability)
            assertEquals(AttemptReference, attempt.reference)
        }
    }

    @Test
    fun restoreWithAnUnrecognizedPurchaseStatusStillDecodes() = runTest {
        val restored = assertIs<PurchaseApiResult.Success<PurchaseRestoreResponse>>(
            client(UnknownStatusRestoreJson).restorePurchases(
                scope = scope(),
                purchases = listOf(
                    VitrinaKitRestorablePurchase(
                        providerProductId = "premium.monthly",
                        proof = VitrinaKitProviderProof("restore-proof"),
                    ),
                ),
                capability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
            ),
        ).value

        assertEquals(VitrinaKitPurchaseAttemptStatus.UNKNOWN, restored.purchases.single().status)
        assertEquals("restored-1", restored.purchases.single().purchaseReference)
    }

    @Test
    fun anAttemptInAnUnrecognizedStatusStaysTrackedAsOpen() = runTest {
        val client = client(attemptJson(status = "future_status", capability = "google_play"))

        assertIs<PurchaseApiResult.Success<PurchaseAttempt>>(
            client.getPurchase(scope = scope(), attemptReference = AttemptReference),
        )

        assertEquals(1, client.trackedPurchaseAttemptCount)
    }

    @Test
    fun recognizedValuesKeepTheirWireNamesAndUnknownRoundTrips() {
        val entitlement = SubscriberEntitlementState(
            key = "premium",
            status = SubscriptionStatus.GRACE_PERIOD,
            hasAccess = true,
            source = EntitlementSource.RUSTORE,
            autoRenewEnabled = true,
        )
        val product = PaywallProduct(
            productId = "product-1",
            productKey = "premium_yearly",
            productName = "Premium",
            planId = "plan-1",
            planKey = "premium",
            planName = "Premium",
            priceId = "price-1",
            amountMinor = 299_000,
            currency = "RUB",
            intervalUnit = BillingIntervalUnit.YEAR,
            intervalCount = 1,
            trialIntervalUnit = BillingIntervalUnit.DAY,
            trialIntervalCount = 7,
            highlighted = false,
            sortOrder = 0,
        )

        val encodedEntitlement = Json.parseToJsonElement(Json.encodeToString(entitlement)).jsonObject
        val encodedProduct = Json.parseToJsonElement(Json.encodeToString(product)).jsonObject

        assertEquals("grace_period", encodedEntitlement.getValue("status").jsonPrimitive.content)
        assertEquals("rustore", encodedEntitlement.getValue("source").jsonPrimitive.content)
        assertEquals("year", encodedProduct.getValue("interval_unit").jsonPrimitive.content)
        assertEquals("day", encodedProduct.getValue("trial_interval_unit").jsonPrimitive.content)
        val unknown = entitlement.copy(status = SubscriptionStatus.UNKNOWN, source = EntitlementSource.UNKNOWN)
        assertEquals(unknown, Json.decodeFromString<SubscriberEntitlementState>(Json.encodeToString(unknown)))
    }

    private fun scope() = SubscriberScope(
        cacheKey = SubscriberCacheKey("production", "app-1", "subscriber-1"),
        sessionToken = "opaque-session",
    )

    private fun client(body: String) = VitrinaClient(
        config = VitrinaConfig(publishableKey = "pk_test", installationId = "installation-1"),
        httpClient = object : VitrinaHttpClient {
            override suspend fun send(request: VitrinaHttpRequest) = VitrinaHttpResponse(HttpStatusOk, body)
        },
    )

    private fun attemptJson(status: String, capability: String): String =
        """{"reference":"$AttemptReference","status":"$status","expires_at":"2026-10-14T12:15:00Z",""" +
            """"capability":"$capability","presentation":{"product_id":"premium.monthly",""" +
            """"package_name":"com.example.app","account_binding":"opaque-binding"}}"""

    private companion object {
        const val HttpStatusOk = 200
        const val AttemptReference = "33333333-3333-3333-3333-333333333333"

        const val UnknownValuesProfileJson = """
{
  "entitlements": [
    {
      "auto_renew_enabled": true,
      "expires_at": "2026-11-14T12:00:00Z",
      "has_access": true,
      "key": "premium",
      "product_key": "premium_monthly",
      "source": "future_store",
      "status": "future_status"
    },
    {
      "auto_renew_enabled": true,
      "expires_at": "2026-11-14T12:00:00Z",
      "has_access": true,
      "key": "extras",
      "product_key": "extras_monthly",
      "source": "yookassa",
      "status": "active"
    }
  ],
  "external_user_id": "customer-1",
  "has_access": true,
  "subscription": {
    "auto_renew_enabled": true,
    "current_period_end": "2026-11-14T12:00:00Z",
    "status": "future_status"
  }
}
"""

        const val UnknownIntervalPaywallJson = """
{
  "placement_key": "main",
  "products": [
    {
      "amount_minor": 29900,
      "currency": "RUB",
      "highlighted": false,
      "interval_count": 1,
      "interval_unit": "future_unit",
      "plan_id": "plan-1",
      "plan_key": "premium",
      "plan_name": "Premium",
      "price_id": "price-1",
      "product_id": "product-1",
      "product_key": "premium_future",
      "product_name": "Premium",
      "sort_order": 10,
      "trial_interval_count": 2,
      "trial_interval_unit": "future_unit"
    },
    {
      "amount_minor": 29900,
      "currency": "RUB",
      "highlighted": true,
      "interval_count": 1,
      "interval_unit": "month",
      "plan_id": "plan-1",
      "plan_key": "premium",
      "plan_name": "Premium",
      "price_id": "price-2",
      "product_id": "product-2",
      "product_key": "premium_monthly",
      "product_name": "Premium",
      "sort_order": 20
    }
  ]
}
"""

        const val UnknownStatusRestoreJson =
            """{"purchases":[{"reference":"restored-1","status":"future_status","replayed":false}],""" +
                """"profile":{"external_user_id":"customer-1","has_access":false,"entitlements":[]}}"""
    }
}
