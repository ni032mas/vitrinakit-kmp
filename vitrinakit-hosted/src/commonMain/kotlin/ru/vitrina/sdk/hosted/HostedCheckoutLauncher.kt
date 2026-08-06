package ru.vitrina.sdk.hosted

/** Opens provider-hosted checkout and reports one lifecycle signal without deciding payment state. */
fun interface HostedCheckoutLauncher {
    /** Opens [request] and suspends until a lifecycle signal can be reported. */
    suspend fun launch(request: HostedCheckoutLaunchRequest): HostedCheckoutLauncherSignal
}

/**
 * Browser launch request containing only the server-provided confirmation URL.
 *
 * @property confirmationUrl Absolute HTTPS confirmation URL returned by VitrinaKit.
 */
data class HostedCheckoutLaunchRequest(
    val confirmationUrl: String,
) {
    /** Redacts the URL because provider URLs may contain sensitive opaque values. */
    override fun toString(): String = "HostedCheckoutLaunchRequest(confirmationUrl=<redacted>)"
}

/**
 * Return URI reported by the browser integration.
 *
 * @property value URI received by the application return/deep-link handler.
 */
class HostedCheckoutReturnUri(val value: String) {
    /** Redacts the URI because query and fragment values are not safe diagnostics. */
    override fun toString(): String = "HostedCheckoutReturnUri(value=<redacted>)"
}

/** Stable browser-launch failure categories safe for application wiring. */
enum class HostedCheckoutLauncherErrorCode {
    /** No compatible browser or presentation context is available. */
    BROWSER_UNAVAILABLE,

    /** The browser integration failed while opening checkout. */
    OPEN_FAILED,
}

/**
 * Safe launcher failure without provider URLs or platform exception text.
 *
 * @property code Stable failure category.
 */
data class HostedCheckoutLauncherError(
    val code: HostedCheckoutLauncherErrorCode,
)

/** Browser lifecycle signal; none of these values proves that payment completed. */
sealed interface HostedCheckoutLauncherSignal {
    /**
     * Application received a configured return/deep link.
     *
     * @property uri Returned URI, validated by the hosted adapter before any status refresh.
     */
    data class Returned(val uri: HostedCheckoutReturnUri) : HostedCheckoutLauncherSignal {
        /** Redacts the returned URI. */
        override fun toString(): String = "Returned(uri=<redacted>)"
    }

    /** Browser presentation was deterministically dismissed by the user. */
    data object Dismissed : HostedCheckoutLauncherSignal

    /** Browser remained open or produced no deterministic completion signal before its deadline. */
    data object TimedOut : HostedCheckoutLauncherSignal

    /**
     * Browser presentation failed before a useful lifecycle signal was available.
     *
     * @property error Safe typed launcher failure.
     */
    data class Failed(val error: HostedCheckoutLauncherError) : HostedCheckoutLauncherSignal
}
