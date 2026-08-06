@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.hosted

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitProfile
import ru.vitrina.sdk.model.VitrinaKitPurchase
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationAdapter
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationRequest
import ru.vitrina.sdk.purchase.VitrinaKitHostedPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitHostedPurchaseRequest
import ru.vitrina.sdk.purchase.VitrinaKitHostedRestoreRequest
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseError
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseErrorCode
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitRestoreResult
import kotlin.time.Instant

/**
 * Provider-neutral hosted checkout adapter.
 *
 * Checkout creation and profile refresh remain inside core-owned callbacks bound to the active
 * subscriber identity. Browser return is treated only as a signal to refresh authoritative state.
 */
class HostedCheckoutAdapter(
    private val configuration: HostedCheckoutConfiguration,
) : VitrinaKitHostedPurchaseAdapter {
    private val purchaseGuard = Mutex()

    /** Opens checkout and returns success only after an authoritative matching profile refresh. */
    override suspend fun purchase(request: VitrinaKitHostedPurchaseRequest): VitrinaKitPurchaseResult {
        if (!purchaseGuard.tryLock()) {
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.PURCHASE_IN_PROGRESS,
                message = "A hosted checkout is already in progress.",
            )
        }
        return try {
            purchaseGuarded(request = request)
        } finally {
            purchaseGuard.unlock()
        }
    }

    private suspend fun purchaseGuarded(request: VitrinaKitHostedPurchaseRequest): VitrinaKitPurchaseResult {
        val returnScheme = validConfiguredReturnScheme()
            ?: return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.INVALID_REQUEST,
                message = "Hosted checkout return configuration is invalid.",
            )
        if (!validPollingPolicy()) {
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.INVALID_REQUEST,
                message = "Hosted checkout polling configuration is invalid.",
            )
        }
        val storedState = runCatching { configuration.resumeStateStore.load() }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE,
                message = "Hosted checkout resume state is unavailable.",
            )
        }
        val resumableState = storedState?.takeIf { state ->
            state.expiresAtEpochMilliseconds > configuration.clock.nowEpochMilliseconds()
        }
        if (storedState != null && resumableState == null) {
            configuration.resumeStateStore.clear()
        }
        val receiptEmail = runCatching { configuration.receiptEmail() }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE,
                message = "Hosted checkout receipt configuration is unavailable.",
            )
        }
        val checkoutResult = request.checkout.create(
            receiptEmail = receiptEmail,
            returnUrl = configuration.returnUrl,
        )
        val checkout = when (checkoutResult) {
            is VitrinaKitResult.Success -> checkoutResult.value
            is VitrinaKitResult.Failure -> return checkoutResult.error.toPurchaseFailure()
        }
        if (!isAbsoluteHttpsUrl(checkout.confirmationUrl)) {
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED,
                message = "Hosted checkout confirmation URL is invalid.",
            )
        }
        val expiresAt = runCatching { Instant.parse(checkout.expiresAt).toEpochMilliseconds() }.getOrNull()
            ?: return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED,
                message = "Hosted checkout expiry is invalid.",
            )
        val state = HostedCheckoutResumeState(
            checkoutReference = checkout.id,
            expiresAtEpochMilliseconds = expiresAt,
        )
        configuration.resumeStateStore.save(state)
        val isResume = checkout.reused && resumableState?.checkoutReference == checkout.id
        val signal = if (isResume) {
            HostedCheckoutLauncherSignal.TimedOut
        } else {
            runCatching {
                configuration.launcher.launch(
                    HostedCheckoutLaunchRequest(confirmationUrl = checkout.confirmationUrl),
                )
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                configuration.resumeStateStore.clear()
                return purchaseFailure(
                    code = VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE,
                    message = "Hosted checkout browser could not be opened.",
                )
            }
        }
        if (signal is HostedCheckoutLauncherSignal.Failed) {
            configuration.resumeStateStore.clear()
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE,
                message = "Hosted checkout browser could not be opened.",
            )
        }
        if (signal is HostedCheckoutLauncherSignal.Returned &&
            normalizedScheme(signal.uri.value) != returnScheme
        ) {
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.INVALID_REQUEST,
                message = "Hosted checkout returned through an unapproved URI scheme.",
            )
        }
        if (signal is HostedCheckoutLauncherSignal.Dismissed) {
            val profile = when (val refreshed = request.profile.refresh()) {
                is VitrinaKitResult.Success -> refreshed.value
                is VitrinaKitResult.Failure -> return refreshed.error.toPurchaseFailure()
            }
            configuration.resumeStateStore.clear()
            return if (profile.grantsAccessFor(request = request)) {
                VitrinaKitPurchaseResult.Success(purchaseReference = checkout.id, profile = profile)
            } else {
                VitrinaKitPurchaseResult.Cancelled
            }
        }
        var lastProfile: VitrinaKitProfile? = null
        repeat(configuration.pollingPolicy.maxRefreshAttempts) { attemptIndex ->
            val profile = when (val refreshed = request.profile.refresh()) {
                is VitrinaKitResult.Success -> refreshed.value
                is VitrinaKitResult.Failure -> return refreshed.error.toPurchaseFailure()
            }
            lastProfile = profile
            if (profile.grantsAccessFor(request = request)) {
                configuration.resumeStateStore.clear()
                return VitrinaKitPurchaseResult.Success(purchaseReference = checkout.id, profile = profile)
            }
            if (attemptIndex < configuration.pollingPolicy.maxRefreshAttempts - 1) {
                configuration.pollingDelay.wait(configuration.pollingPolicy.intervalMilliseconds)
            }
        }
        return VitrinaKitPurchaseResult.Pending(
            attemptReference = checkout.id,
            profile = lastProfile,
        )
    }

    /** Refreshes the bound subscriber because hosted checkout has no device-store restore proof. */
    override suspend fun restore(request: VitrinaKitHostedRestoreRequest): VitrinaKitRestoreResult {
        return when (val refreshed = request.profile.refresh()) {
            is VitrinaKitResult.Success -> {
                if (refreshed.value.hasAccess) {
                    VitrinaKitRestoreResult.Success(profile = refreshed.value, purchases = emptyList())
                } else {
                    VitrinaKitRestoreResult.NoPurchases(profile = refreshed.value)
                }
            }
            is VitrinaKitResult.Failure -> VitrinaKitRestoreResult.Failure(
                error = refreshed.error.toPurchaseError(),
            )
        }
    }

    /**
     * Preserves the deprecated checkout-session result while using adapter-owned configuration.
     */
    @Suppress("DEPRECATION")
    override suspend fun purchaseForBoundIdentity(
        request: VitrinaKitHostedMigrationRequest,
    ): VitrinaKitResult<VitrinaKitPurchase> {
        if (validConfiguredReturnScheme() == null) {
            return VitrinaKitResult.Failure(
                VitrinaKitError.Configuration("Hosted checkout return configuration is invalid."),
            )
        }
        val receiptEmail = runCatching { configuration.receiptEmail() }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            return VitrinaKitResult.Failure(
                VitrinaKitError.Provider("Hosted checkout receipt configuration is unavailable."),
            )
        }
        return request.checkout.create(
            receiptEmail = receiptEmail,
            returnUrl = configuration.returnUrl,
        )
    }

    /** Clears subscriber-scoped durable resume state; later calls lazily start fresh. */
    override fun close() {
        runCatching { configuration.resumeStateStore.clear() }
    }

    private fun validConfiguredReturnScheme(): String? {
        val scheme = normalizedScheme(configuration.returnUrl) ?: return null
        val allowed = configuration.allowedReturnSchemes.mapNotNull(::normalizedSchemeValue).toSet()
        return scheme.takeIf { it in allowed }
    }

    private fun validPollingPolicy(): Boolean =
        configuration.pollingPolicy.maxRefreshAttempts > 0 &&
            configuration.pollingPolicy.intervalMilliseconds >= 0

    private fun VitrinaKitProfile.grantsAccessFor(request: VitrinaKitHostedPurchaseRequest): Boolean {
        val entitlementKeys = request.product.entitlements.map { entitlement -> entitlement.key }.toSet()
        return hasAccess && entitlements.any { entitlement ->
            entitlement.hasAccess &&
                (entitlement.productKey == request.product.productKey || entitlement.key in entitlementKeys)
        }
    }
}

private fun normalizedScheme(uri: String): String? {
    val separator = uri.indexOf(':')
    if (separator <= 0) return null
    return normalizedSchemeValue(uri.substring(startIndex = 0, endIndex = separator))
}

private fun normalizedSchemeValue(value: String): String? {
    val normalized = value.trim().lowercase()
    return normalized.takeIf { SchemePattern.matches(it) }
}

private fun isAbsoluteHttpsUrl(value: String): Boolean {
    val parsed = runCatching { Url(value) }.getOrNull() ?: return false
    return parsed.protocol.name == HttpsScheme &&
        parsed.host.isNotBlank() &&
        parsed.user == null &&
        parsed.password == null
}

private fun VitrinaKitError.toPurchaseFailure(): VitrinaKitPurchaseResult.Failure =
    VitrinaKitPurchaseResult.Failure(error = toPurchaseError())

private fun VitrinaKitError.toPurchaseError(): VitrinaKitPurchaseError = VitrinaKitPurchaseError(
    code = when (this) {
        is VitrinaKitError.Auth -> VitrinaKitPurchaseErrorCode.SUBSCRIBER_AUTH_REQUIRED
        is VitrinaKitError.Network -> VitrinaKitPurchaseErrorCode.NETWORK_ERROR
        is VitrinaKitError.Checkout -> VitrinaKitPurchaseErrorCode.INVALID_REQUEST
        is VitrinaKitError.Configuration -> VitrinaKitPurchaseErrorCode.INVALID_REQUEST
        is VitrinaKitError.Provider -> VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE
        is VitrinaKitError.Subscription -> VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED
    },
    message = when (this) {
        is VitrinaKitError.Network -> "Hosted checkout network request failed."
        else -> "Hosted checkout request failed."
    },
    retryable = this is VitrinaKitError.Network,
    supportReference = null,
)

private fun purchaseFailure(
    code: VitrinaKitPurchaseErrorCode,
    message: String,
): VitrinaKitPurchaseResult.Failure = VitrinaKitPurchaseResult.Failure(
    error = VitrinaKitPurchaseError(
        code = code,
        message = message,
        retryable = false,
        supportReference = null,
    ),
)

private val SchemePattern = Regex("[a-z][a-z0-9+.-]*")
private const val HttpsScheme = "https"
