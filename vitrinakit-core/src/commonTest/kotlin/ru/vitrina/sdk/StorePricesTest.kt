@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import ru.vitrina.sdk.contract.ServerContractFixtures
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpResponse
import ru.vitrina.sdk.installation.VitrinaKitInstallationIdStorage
import ru.vitrina.sdk.model.PaywallStoreProduct
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitPaywall
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.purchase.VitrinaKitAdapterError
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationRequest
import ru.vitrina.sdk.purchase.VitrinaKitHostedPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitHostedPurchaseRequest
import ru.vitrina.sdk.purchase.VitrinaKitHostedRestoreRequest
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseCapability
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseInstruction
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResumeData
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase
import ru.vitrina.sdk.purchase.VitrinaKitStorePrice
import ru.vitrina.sdk.purchase.VitrinaKitStorePriceQuery
import ru.vitrina.sdk.purchase.VitrinaKitStoreProductRef
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class StorePricesTest {
    @AfterTest
    fun tearDown() = VitrinaKit.resetForTesting()

    @Test
    fun storePricesAreKeyedByProductKey() = runTest {
        activate(PriceAdapter())
        val original = paywall().products.single()
        val paywall = paywall().copy(products = listOf(
            original,
            original.copy(productKey = "second_catalog_option"),
            original.copy(productKey = "unpriced", storeProducts = listOf(
                PaywallStoreProduct(VitrinaKitPurchaseCapability.GOOGLE_PLAY, "missing", "monthly"),
            )),
        ))

        val prices = assertIs<VitrinaKitResult.Success<Map<String, VitrinaKitStorePrice>>>(
            VitrinaKit.getStorePrices(paywall),
        ).value

        assertEquals(mapOf("premium_monthly" to price(), "second_catalog_option" to price()), prices)
    }

    @Test
    fun storeProductsOfOtherCapabilitiesAreIgnored() = runTest {
        val adapter = PriceAdapter()
        activate(adapter)
        val original = paywall().products.single()
        val paywall = paywall().copy(products = listOf(original.copy(storeProducts = listOf(
            PaywallStoreProduct(VitrinaKitPurchaseCapability.RUSTORE, "premium_monthly", "monthly"),
            PaywallStoreProduct(VitrinaKitPurchaseCapability.UNKNOWN, "premium_monthly", "monthly"),
        ))))

        assertEquals(VitrinaKitResult.Success(emptyMap()), VitrinaKit.getStorePrices(paywall))
        assertEquals(0, adapter.queryCount)
    }

    @Test
    fun hostedBuildReturnsNoStorePrices() = runTest {
        val hosted = object : VitrinaKitHostedPurchaseAdapter {
            override suspend fun purchase(request: VitrinaKitHostedPurchaseRequest) = error("Not used")
            override suspend fun restore(request: VitrinaKitHostedRestoreRequest) = error("Not used")
            override suspend fun purchaseForBoundIdentity(request: VitrinaKitHostedMigrationRequest) = error("Not used")
            override fun close() = Unit
        }
        assertIs<VitrinaKitResult.Success<Unit>>(
            VitrinaKit.activate(builder().withHostedCheckoutAdapter(hosted).build()),
        )

        assertEquals(VitrinaKitPurchaseCapability.YOOKASSA_HOSTED, VitrinaKit.purchaseCapability())
        assertEquals(VitrinaKitResult.Success(emptyMap()), VitrinaKit.getStorePrices(paywall()))
    }

    @Test
    fun adapterFailureSurfacesAsFailure() = runTest {
        activate(PriceAdapter(query = {
            VitrinaKitStorePriceQuery.Failure(VitrinaKitAdapterError("Billing unavailable", retryable = true))
        }))

        assertIs<VitrinaKitError.Provider>(
            assertIs<VitrinaKitResult.Failure>(VitrinaKit.getStorePrices(paywall())).error,
        )
    }

    @Test
    fun adapterExceptionSurfacesAsFailureAndCancellationPropagates() = runTest {
        activate(PriceAdapter(query = { throw IllegalStateException("private provider payload") }))
        val error = assertIs<VitrinaKitError.Provider>(
            assertIs<VitrinaKitResult.Failure>(VitrinaKit.getStorePrices(paywall())).error,
        )
        kotlin.test.assertFalse(error.message.contains("private provider payload"))
        VitrinaKit.resetForTesting()
        activate(PriceAdapter(query = { throw CancellationException("cancel") }))
        assertFailsWith<CancellationException> { VitrinaKit.getStorePrices(paywall()) }
    }

    @Test
    fun notActivatedReturnsNotActivated() = runTest {
        assertNull(VitrinaKit.purchaseCapability())
        assertIs<VitrinaKitError.Configuration>(
            assertIs<VitrinaKitResult.Failure>(VitrinaKit.getStorePrices(paywall())).error,
        )
    }

    @Test
    fun purchaseCapabilityReflectsConfiguredAdapter() {
        assertNull(VitrinaKit.purchaseCapability())
        activate(PriceAdapter())
        assertEquals(VitrinaKitPurchaseCapability.GOOGLE_PLAY, VitrinaKit.purchaseCapability())
        VitrinaKit.resetForTesting()
        activate(PriceAdapter(capability = VitrinaKitPurchaseCapability.RUSTORE))
        assertEquals(VitrinaKitPurchaseCapability.RUSTORE, VitrinaKit.purchaseCapability())
    }

    @Test
    fun unsupportedQueryAndProductsWithoutReferencesReturnNoPrices() = runTest {
        val adapter = PriceAdapter(query = { VitrinaKitStorePriceQuery.Unsupported })
        activate(adapter)
        assertEquals(VitrinaKitResult.Success(emptyMap()), VitrinaKit.getStorePrices(paywall()))
        val original = paywall().products.single()
        assertEquals(VitrinaKitResult.Success(emptyMap()), VitrinaKit.getStorePrices(
            paywall().copy(products = listOf(original.copy(storeProducts = emptyList()))),
        ))
        assertEquals(1, adapter.queryCount)
    }

    private fun activate(adapter: PriceAdapter) {
        assertIs<VitrinaKitResult.Success<Unit>>(
            VitrinaKit.activate(builder().withPurchaseAdapter(adapter).build()),
        )
    }

    private fun builder() = VitrinaKitConfig.Builder("pk_test")
        .withInstallationIdStorage(object : VitrinaKitInstallationIdStorage {
            override fun read() = "installation-prices"
            override fun write(installationId: String) = Unit
        })
        .withHttpClient(object : VitrinaHttpClient {
            override suspend fun send(request: ru.vitrina.sdk.http.VitrinaHttpRequest) =
                VitrinaHttpResponse(200, """{"has_access":false,"entitlements":[]}""")
        })

    private fun paywall(): VitrinaKitPaywall = Json.decodeFromString(
        ServerContractFixtures.PAYWALL_MAIN_WITH_STORE_PRODUCTS,
    )

    private class PriceAdapter(
        override val capability: VitrinaKitPurchaseCapability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
        private val query: suspend () -> VitrinaKitStorePriceQuery = {
            VitrinaKitStorePriceQuery.Priced(mapOf(VitrinaKitStoreProductRef("premium_monthly", "monthly") to price()))
        },
    ) : VitrinaKitPurchaseAdapter {
        var queryCount = 0
        override suspend fun queryStorePrices(products: List<VitrinaKitStoreProductRef>): VitrinaKitStorePriceQuery {
            queryCount += 1
            return query()
        }
        override suspend fun present(instruction: VitrinaKitPurchaseInstruction) = error("Not used")
        override suspend fun resume(instruction: VitrinaKitPurchaseInstruction, resumeData: VitrinaKitPurchaseResumeData) =
            error("Not used")
        override suspend fun queryRestorablePurchases() = emptyList<VitrinaKitRestorablePurchase>()
        override fun close() = Unit
    }

    private companion object {
        fun price() = VitrinaKitStorePrice("$4.99", 4_990_000L, "USD")
    }
}
