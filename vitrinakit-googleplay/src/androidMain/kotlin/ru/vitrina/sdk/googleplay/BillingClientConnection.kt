package ru.vitrina.sdk.googleplay

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal enum class BillingResponseCode(val providerCode: Int) {
    SERVICE_TIMEOUT(-3),
    FEATURE_NOT_SUPPORTED(-2),
    SERVICE_DISCONNECTED(-1),
    OK(0),
    USER_CANCELED(1),
    SERVICE_UNAVAILABLE(2),
    BILLING_UNAVAILABLE(3),
    ITEM_UNAVAILABLE(4),
    DEVELOPER_ERROR(5),
    ERROR(6),
    ITEM_ALREADY_OWNED(7),
    ITEM_NOT_OWNED(8),
    NETWORK_ERROR(12),
    UNKNOWN(Int.MIN_VALUE),
}

internal enum class BillingPurchaseState {
    PURCHASED,
    PENDING,
    UNSPECIFIED,
}

internal data class BillingOffer(
    val basePlanId: String,
    val offerId: String?,
    val offerToken: String,
) {
    override fun toString(): String =
        "BillingOffer(basePlanId=$basePlanId, offerId=$offerId, offerToken=<redacted>)"
}

internal data class BillingProduct(
    val productId: String,
    val offers: List<BillingOffer>,
)

internal class BillingPurchase(
    val productIds: List<String>,
    val purchaseToken: String,
    val state: BillingPurchaseState,
) {
    override fun toString(): String =
        "BillingPurchase(productIds=$productIds, purchaseToken=<redacted>, state=$state)"
}

internal data class BillingPurchaseUpdate(
    val responseCode: BillingResponseCode,
    val purchases: List<BillingPurchase>,
)

internal data class BillingQueryResult<T>(
    val responseCode: BillingResponseCode,
    val values: List<T>,
)

internal data class BillingActivityHandle(val value: Any)

internal data class BillingFlowRequest(
    val productId: String,
    val offerToken: String,
    val obfuscatedAccountId: String,
    val obfuscatedProfileId: String,
) {
    override fun toString(): String =
        "BillingFlowRequest(productId=$productId, offerToken=<redacted>, " +
            "obfuscatedAccountId=<redacted>, obfuscatedProfileId=<redacted>)"
}

internal interface GooglePlayBillingGateway {
    fun setPurchaseUpdateListener(listener: ((BillingPurchaseUpdate) -> Unit)?)

    suspend fun connect(): BillingResponseCode

    suspend fun queryProductDetails(productId: String): BillingQueryResult<BillingProduct>

    suspend fun queryPurchases(): BillingQueryResult<BillingPurchase>

    fun launch(activity: BillingActivityHandle, request: BillingFlowRequest): BillingResponseCode

    fun close()
}

internal class BillingClientConnection(
    private val gateway: GooglePlayBillingGateway,
    private val retryDelaysMillis: List<Long> = DefaultRetryDelaysMillis,
    private val delayOperation: suspend (Long) -> Unit = { duration -> delay(duration) },
) {
    private val connectionMutex = Mutex()

    suspend fun connect(): BillingResponseCode = connectionMutex.withLock {
        var response = gateway.connect()
        retryDelaysMillis.forEach { retryDelay ->
            if (response == BillingResponseCode.OK || !response.isConnectionFailure()) {
                return@withLock response
            }
            delayOperation(retryDelay)
            response = gateway.connect()
        }
        response
    }

    suspend fun queryProductDetails(productId: String): BillingQueryResult<BillingProduct> =
        queryWithReconnect(emptyList()) { gateway.queryProductDetails(productId = productId) }

    suspend fun queryPurchases(): BillingQueryResult<BillingPurchase> =
        queryWithReconnect(emptyList()) { gateway.queryPurchases() }

    fun launch(activity: BillingActivityHandle, request: BillingFlowRequest): BillingResponseCode =
        gateway.launch(activity = activity, request = request)

    fun close() {
        gateway.close()
    }

    private suspend fun <T> queryWithReconnect(
        empty: List<T>,
        query: suspend () -> BillingQueryResult<T>,
    ): BillingQueryResult<T> {
        val connectionCode = connect()
        if (connectionCode != BillingResponseCode.OK) {
            return BillingQueryResult(responseCode = connectionCode, values = empty)
        }
        val first = query()
        if (!first.responseCode.isConnectionFailure()) {
            return first
        }
        val reconnectCode = connect()
        return if (reconnectCode == BillingResponseCode.OK) {
            query()
        } else {
            BillingQueryResult(responseCode = reconnectCode, values = empty)
        }
    }

    private companion object {
        val DefaultRetryDelaysMillis = listOf(100L, 250L, 500L)
    }
}

internal class RealGooglePlayBillingGateway(context: Context) : GooglePlayBillingGateway {
    private var purchaseUpdateListener: ((BillingPurchaseUpdate) -> Unit)? = null
    private val productDetailsById = mutableMapOf<String, ProductDetails>()
    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(
            PurchasesUpdatedListener { result, purchases ->
                purchaseUpdateListener?.invoke(
                    BillingPurchaseUpdate(
                        responseCode = result.responseCode.toDomain(),
                        purchases = purchases.orEmpty().map(Purchase::toDomain),
                    ),
                )
            },
        )
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build(),
        )
        .build()

    override fun setPurchaseUpdateListener(listener: ((BillingPurchaseUpdate) -> Unit)?) {
        purchaseUpdateListener = listener
    }

    override suspend fun connect(): BillingResponseCode {
        if (billingClient.isReady) {
            return BillingResponseCode.OK
        }
        return suspendCancellableCoroutine { continuation ->
            billingClient.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        continuation.resumeIfActive(billingResult.responseCode.toDomain())
                    }

                    override fun onBillingServiceDisconnected() {
                        purchaseUpdateListener?.invoke(
                            BillingPurchaseUpdate(
                                responseCode = BillingResponseCode.SERVICE_DISCONNECTED,
                                purchases = emptyList(),
                            ),
                        )
                        continuation.resumeIfActive(BillingResponseCode.SERVICE_DISCONNECTED)
                    }
                },
            )
        }
    }

    override suspend fun queryProductDetails(productId: String): BillingQueryResult<BillingProduct> =
        suspendCancellableCoroutine { continuation ->
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()
            billingClient.queryProductDetailsAsync(params) { result, detailsResult ->
                val details = detailsResult.productDetailsList
                details.forEach { productDetails ->
                    productDetailsById[productDetails.productId] = productDetails
                }
                continuation.resumeIfActive(
                    BillingQueryResult(
                        responseCode = result.responseCode.toDomain(),
                        values = details.map(ProductDetails::toDomain),
                    ),
                )
            }
        }

    override suspend fun queryPurchases(): BillingQueryResult<BillingPurchase> =
        suspendCancellableCoroutine { continuation ->
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            billingClient.queryPurchasesAsync(params) { result, purchases ->
                continuation.resumeIfActive(
                    BillingQueryResult(
                        responseCode = result.responseCode.toDomain(),
                        values = purchases.map(Purchase::toDomain),
                    ),
                )
            }
        }

    override fun launch(
        activity: BillingActivityHandle,
        request: BillingFlowRequest,
    ): BillingResponseCode {
        val androidActivity = activity.value as? Activity ?: return BillingResponseCode.DEVELOPER_ERROR
        val productDetails = productDetailsById[request.productId]
            ?: return BillingResponseCode.ITEM_UNAVAILABLE
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(request.offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(request.obfuscatedAccountId)
            .setObfuscatedProfileId(request.obfuscatedProfileId)
            .build()
        return billingClient.launchBillingFlow(androidActivity, flowParams).responseCode.toDomain()
    }

    override fun close() {
        purchaseUpdateListener = null
        productDetailsById.clear()
        billingClient.endConnection()
    }
}

private fun BillingResponseCode.isConnectionFailure(): Boolean = when (this) {
    BillingResponseCode.SERVICE_TIMEOUT,
    BillingResponseCode.SERVICE_DISCONNECTED,
    BillingResponseCode.SERVICE_UNAVAILABLE,
    BillingResponseCode.NETWORK_ERROR,
    -> true

    else -> false
}

@Suppress("DEPRECATION")
private fun Int.toDomain(): BillingResponseCode = when (this) {
    BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> BillingResponseCode.SERVICE_TIMEOUT
    BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> BillingResponseCode.FEATURE_NOT_SUPPORTED
    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> BillingResponseCode.SERVICE_DISCONNECTED
    BillingClient.BillingResponseCode.OK -> BillingResponseCode.OK
    BillingClient.BillingResponseCode.USER_CANCELED -> BillingResponseCode.USER_CANCELED
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> BillingResponseCode.SERVICE_UNAVAILABLE
    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> BillingResponseCode.BILLING_UNAVAILABLE
    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> BillingResponseCode.ITEM_UNAVAILABLE
    BillingClient.BillingResponseCode.DEVELOPER_ERROR -> BillingResponseCode.DEVELOPER_ERROR
    BillingClient.BillingResponseCode.ERROR -> BillingResponseCode.ERROR
    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> BillingResponseCode.ITEM_ALREADY_OWNED
    BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> BillingResponseCode.ITEM_NOT_OWNED
    BillingClient.BillingResponseCode.NETWORK_ERROR -> BillingResponseCode.NETWORK_ERROR
    else -> BillingResponseCode.UNKNOWN
}

private fun ProductDetails.toDomain(): BillingProduct = BillingProduct(
    productId = productId,
    offers = subscriptionOfferDetails.orEmpty().map { offer ->
        BillingOffer(
            basePlanId = offer.basePlanId,
            offerId = offer.offerId,
            offerToken = offer.offerToken,
        )
    },
)

private fun Purchase.toDomain(): BillingPurchase = BillingPurchase(
    productIds = products,
    purchaseToken = purchaseToken,
    state = when (purchaseState) {
        Purchase.PurchaseState.PURCHASED -> BillingPurchaseState.PURCHASED
        Purchase.PurchaseState.PENDING -> BillingPurchaseState.PENDING
        else -> BillingPurchaseState.UNSPECIFIED
    },
)

private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
    if (isActive) {
        resume(value)
    }
}
