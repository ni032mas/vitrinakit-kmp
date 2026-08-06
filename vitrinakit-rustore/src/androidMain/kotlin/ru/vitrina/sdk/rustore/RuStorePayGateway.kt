package ru.vitrina.sdk.rustore

import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean
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
    private val activeTasks = RuStoreActiveTaskTracker()

    override suspend fun getProducts(productId: String): List<RuStoreProduct> =
        runProviderQuery {
            client.getProductInteractor()
                .getProducts(listOf(ProductId(productId)))
                .awaitTracked(activeTasks)
                .map { product -> RuStoreProduct(productId = product.productId.value) }
        }

    override suspend fun getPurchases(): List<RuStorePurchase> =
        runProviderQuery {
            client.getPurchaseInteractor()
                .getPurchases()
                .awaitTracked(activeTasks)
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
        val trackedTask = activeTasks.track { task.cancel() }
        try {
            task.addOnSuccessListener { result ->
                if (trackedTask.complete()) {
                    callback(result.toDomain())
                }
            }.addOnFailureListener { throwable ->
                if (trackedTask.complete()) {
                    callback(throwable.toPurchaseOutcome())
                }
            }
        } catch (exception: CancellationException) {
            trackedTask.cancel()
            throw exception
        } catch (exception: Throwable) {
            trackedTask.cancel()
            throw exception.toSafeRuStoreGatewayException()
        }
        return RuStorePurchaseOperation {
            trackedTask.cancel()
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
        activeTasks.close()
    }
}

internal class RuStoreActiveTaskTracker {
    private val lock = Any()
    private val tasks = mutableSetOf<TrackedTask>()
    private var closed = false

    internal fun track(cancelProvider: () -> Unit): TrackedTask {
        val trackedTask = TrackedTask(tracker = this, cancelProvider = cancelProvider)
        val accepted = synchronized(lock) {
            if (closed) {
                false
            } else {
                tasks += trackedTask
                true
            }
        }
        if (!accepted) {
            trackedTask.cancelProviderOnce()
        }
        return trackedTask
    }

    internal suspend fun <T> await(
        cancelProvider: () -> Unit,
        registerListeners: (
            onSuccess: (T) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) -> Unit,
    ): T = suspendCancellableCoroutine { continuation ->
        val trackedTask = track {
            try {
                continuation.cancel(CancellationException("RuStore Pay task was cancelled."))
            } finally {
                cancelProvider()
            }
        }
        continuation.invokeOnCancellation { trackedTask.cancel() }
        if (!trackedTask.isActive()) {
            return@suspendCancellableCoroutine
        }
        try {
            registerListeners(
                { value ->
                    if (trackedTask.complete()) {
                        continuation.resumeIfActive(value)
                    }
                },
                { throwable ->
                    if (trackedTask.complete()) {
                        continuation.resumeExceptionIfActive(throwable)
                    }
                },
            )
        } catch (exception: Throwable) {
            if (trackedTask.complete()) {
                continuation.resumeExceptionIfActive(exception)
                trackedTask.cancelProviderOnce()
            }
        }
    }

    internal fun close() {
        val trackedTasks = synchronized(lock) {
            closed = true
            tasks.toList().also { tasks.clear() }
        }
        trackedTasks.forEach(TrackedTask::cancelProviderOnce)
    }

    private fun complete(trackedTask: TrackedTask): Boolean =
        synchronized(lock) { tasks.remove(trackedTask) }

    private fun cancel(trackedTask: TrackedTask) {
        if (synchronized(lock) { tasks.remove(trackedTask) }) {
            trackedTask.cancelProviderOnce()
        }
    }

    private fun isActive(trackedTask: TrackedTask): Boolean =
        synchronized(lock) { trackedTask in tasks }

    internal class TrackedTask internal constructor(
        private val tracker: RuStoreActiveTaskTracker,
        private val cancelProvider: () -> Unit,
    ) {
        private val providerCancellationStarted = AtomicBoolean(false)

        internal fun complete(): Boolean = tracker.complete(this)

        internal fun cancel() {
            tracker.cancel(this)
        }

        internal fun isActive(): Boolean = tracker.isActive(this)

        internal fun cancelProviderOnce() {
            if (providerCancellationStarted.compareAndSet(false, true)) {
                runCatching(cancelProvider)
            }
        }
    }
}

private suspend fun <T> Task<T>.awaitTracked(tracker: RuStoreActiveTaskTracker): T =
    tracker.await(
        cancelProvider = { cancel() },
        registerListeners = { onSuccess, onFailure ->
            addOnSuccessListener(onSuccess).addOnFailureListener(onFailure)
        },
    )

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
