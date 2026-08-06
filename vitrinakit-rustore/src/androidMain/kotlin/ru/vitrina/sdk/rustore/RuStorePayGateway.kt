package ru.vitrina.sdk.rustore

import android.content.Intent
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.rustore.sdk.core.tasks.Task
import ru.rustore.sdk.pay.RuStorePayClient
import ru.rustore.sdk.pay.model.AppUserId
import ru.rustore.sdk.pay.model.PreferredPurchaseType
import ru.rustore.sdk.pay.model.ProductId
import ru.rustore.sdk.pay.model.ProductPurchase
import ru.rustore.sdk.pay.model.ProductPurchaseParams
import ru.rustore.sdk.pay.model.ProductPurchaseResult
import ru.rustore.sdk.pay.model.ProductPurchaseStatus
import ru.rustore.sdk.pay.model.RuStorePaymentException
import ru.rustore.sdk.pay.model.SubscriptionPurchase
import ru.rustore.sdk.pay.model.SubscriptionPurchaseStatus

internal data class RuStoreProduct(val productId: String)

internal enum class RuStorePurchaseState {
    PURCHASED,
    TERMINAL,
}

internal data class RuStorePurchase(
    val purchaseId: String,
    val productId: String,
    val state: RuStorePurchaseState,
) {
    override fun toString(): String =
        "RuStorePurchase(purchaseId=<redacted>, productId=$productId, state=$state)"
}

internal enum class RuStorePurchaseType {
    ONE_STEP,
}

internal data class RuStorePurchaseRequest(
    val productId: String,
    val appUserId: String,
    val purchaseType: RuStorePurchaseType,
) {
    override fun toString(): String =
        "RuStorePurchaseRequest(productId=$productId, appUserId=<redacted>, purchaseType=$purchaseType)"
}

internal enum class RuStoreFailureKind {
    NETWORK,
    UNAVAILABLE,
    CONFIGURATION,
    UNKNOWN,
}

internal class RuStoreGatewayException(val kind: RuStoreFailureKind) :
    Exception("RuStore Pay operation failed.")

internal sealed interface RuStorePurchaseOutcome {
    data class Success(val purchaseId: String, val productId: String) : RuStorePurchaseOutcome {
        override fun toString(): String =
            "Success(purchaseId=<redacted>, productId=$productId)"
    }

    data object Cancelled : RuStorePurchaseOutcome

    data class Failure(val kind: RuStoreFailureKind) : RuStorePurchaseOutcome
}

internal fun interface RuStorePurchaseOperation {
    fun cancel()
}

internal data class RuStoreActivityHandle(val value: Any)

internal interface RuStorePayGateway {
    suspend fun getProducts(productId: String): List<RuStoreProduct>

    suspend fun getPurchases(): List<RuStorePurchase>

    fun purchase(
        request: RuStorePurchaseRequest,
        callback: (RuStorePurchaseOutcome) -> Unit,
    ): RuStorePurchaseOperation

    fun proceedIntent(intent: Intent?)

    fun close()
}

internal class RealRuStorePayGateway(
    private val client: RuStorePayClient = RuStorePayClient.instance,
) : RuStorePayGateway {
    private val activeTasks = Collections.synchronizedSet(mutableSetOf<Task<*>>())

    override suspend fun getProducts(productId: String): List<RuStoreProduct> =
        runProviderQuery {
            client.getProductInteractor()
                .getProducts(listOf(ProductId(productId)))
                .awaitCancellable()
                .map { product -> RuStoreProduct(productId = product.productId.value) }
        }

    override suspend fun getPurchases(): List<RuStorePurchase> =
        runProviderQuery {
            client.getPurchaseInteractor()
                .getPurchases()
                .awaitCancellable()
                .mapNotNull { purchase ->
                    when (purchase) {
                        is ProductPurchase -> RuStorePurchase(
                            purchaseId = purchase.purchaseId.value,
                            productId = purchase.productId.value,
                            state = purchase.status.toDomain(),
                        )
                        is SubscriptionPurchase -> RuStorePurchase(
                            purchaseId = purchase.purchaseId.value,
                            productId = purchase.productId.value,
                            state = purchase.status.toDomain(),
                        )
                        else -> null
                    }
                }
        }

    override fun purchase(
        request: RuStorePurchaseRequest,
        callback: (RuStorePurchaseOutcome) -> Unit,
    ): RuStorePurchaseOperation {
        val task = try {
            val params = ProductPurchaseParams(
                productId = ProductId(request.productId),
                appUserId = AppUserId(request.appUserId),
            )
            client.getPurchaseInteractor().purchase(
                params = params,
                preferredPurchaseType = PreferredPurchaseType.ONE_STEP,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw exception.toSafeRuStoreGatewayException()
        }
        activeTasks += task
        task.addOnSuccessListener { result ->
            activeTasks -= task
            callback(result.toDomain())
        }.addOnFailureListener { throwable ->
            activeTasks -= task
            callback(throwable.toPurchaseOutcome())
        }
        return RuStorePurchaseOperation {
            activeTasks -= task
            task.cancel()
        }
    }

    override fun proceedIntent(intent: Intent?) {
        try {
            client.getIntentInteractor().proceedIntent(intent)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw exception.toSafeRuStoreGatewayException()
        }
    }

    override fun close() {
        val tasks = synchronized(activeTasks) { activeTasks.toList().also { activeTasks.clear() } }
        tasks.forEach(Task<*>::cancel)
    }
}

private suspend fun <T> Task<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> continuation.resumeIfActive(value) }
        .addOnFailureListener { throwable -> continuation.resumeExceptionIfActive(throwable) }
    continuation.invokeOnCancellation { cancel() }
}

private fun ProductPurchaseResult.toDomain(): RuStorePurchaseOutcome =
    RuStorePurchaseOutcome.Success(
        purchaseId = purchaseId.value,
        productId = productId.value,
    )

private fun Throwable.toPurchaseOutcome(): RuStorePurchaseOutcome = toSafeRuStorePurchaseOutcome()

internal fun Throwable.toSafeRuStorePurchaseOutcome(): RuStorePurchaseOutcome = when (this) {
    is RuStorePaymentException.ProductPurchaseCancelled -> RuStorePurchaseOutcome.Cancelled
    is RuStorePaymentException.ProductPurchaseException -> {
        val purchaseReference = purchaseId?.value
        val productReference = productId?.value
        if (!purchaseReference.isNullOrBlank() && !productReference.isNullOrBlank()) {
            RuStorePurchaseOutcome.Success(
                purchaseId = purchaseReference,
                productId = productReference,
            )
        } else {
            RuStorePurchaseOutcome.Failure(kind = toSafeRuStoreFailureKind())
        }
    }
    else -> RuStorePurchaseOutcome.Failure(kind = toSafeRuStoreFailureKind())
}

internal fun Throwable.toSafeRuStoreFailureKind(): RuStoreFailureKind = when (this) {
    is RuStorePaymentException.RuStorePaymentNetworkException -> RuStoreFailureKind.NETWORK
    is RuStorePaymentException.RuStorePayClientNotCreated,
    is RuStorePaymentException.RuStorePayInvalidActivePurchase,
    -> RuStoreFailureKind.UNAVAILABLE
    is RuStorePaymentException.ApplicationSchemeWasNotProvided,
    is RuStorePaymentException.RuStorePayInvalidConsoleAppId,
    is RuStorePaymentException.RuStorePaySignatureException,
    -> RuStoreFailureKind.CONFIGURATION
    is RuStorePaymentException.ProductPurchaseException -> cause.toSafeRuStoreFailureKind()
    else -> RuStoreFailureKind.UNKNOWN
}

internal fun Throwable.toSafeRuStoreGatewayException(): RuStoreGatewayException =
    RuStoreGatewayException(kind = toSafeRuStoreFailureKind())

private suspend fun <T> runProviderQuery(block: suspend () -> T): T = try {
    block()
} catch (exception: CancellationException) {
    throw exception
} catch (exception: Throwable) {
    throw exception.toSafeRuStoreGatewayException()
}

private fun ProductPurchaseStatus.toDomain(): RuStorePurchaseState = when (this) {
    ProductPurchaseStatus.PAID,
    ProductPurchaseStatus.CONFIRMED,
    -> RuStorePurchaseState.PURCHASED
    else -> RuStorePurchaseState.TERMINAL
}

private fun SubscriptionPurchaseStatus.toDomain(): RuStorePurchaseState = when (this) {
    SubscriptionPurchaseStatus.ACTIVE,
    SubscriptionPurchaseStatus.PAUSED,
    -> RuStorePurchaseState.PURCHASED
    else -> RuStorePurchaseState.TERMINAL
}

private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
    if (isActive) {
        resume(value)
    }
}

private fun <T> CancellableContinuation<T>.resumeExceptionIfActive(throwable: Throwable) {
    if (isActive) {
        resumeWithException(throwable)
    }
}
