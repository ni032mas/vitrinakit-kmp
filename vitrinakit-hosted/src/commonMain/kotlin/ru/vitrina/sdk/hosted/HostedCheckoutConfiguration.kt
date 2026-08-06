package ru.vitrina.sdk.hosted

import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * Bounded authoritative profile polling policy.
 *
 * @property maxRefreshAttempts Maximum profile refresh calls made after a non-terminal browser signal.
 * @property intervalMilliseconds Delay between unsuccessful refresh calls.
 */
data class HostedCheckoutPollingPolicy(
    val maxRefreshAttempts: Int = DefaultMaxRefreshAttempts,
    val intervalMilliseconds: Long = DefaultPollingIntervalMilliseconds,
)

/** Injectable polling delay used by hosts and deterministic tests. */
fun interface HostedCheckoutDelay {
    /** Suspends for [milliseconds] before the next authoritative refresh. */
    suspend fun wait(milliseconds: Long)
}

/** Injectable wall clock used to reject expired resume state. */
fun interface HostedCheckoutClock {
    /** Returns current Unix epoch time in milliseconds. */
    fun nowEpochMilliseconds(): Long
}

/**
 * Opaque durable state allowed across application restarts.
 *
 * @property checkoutReference Opaque hosted checkout/session reference.
 * @property expiresAtEpochMilliseconds Server expiry converted to Unix epoch milliseconds.
 */
data class HostedCheckoutResumeState(
    val checkoutReference: String,
    val expiresAtEpochMilliseconds: Long,
) {
    /** Redacts the checkout reference from diagnostics. */
    override fun toString(): String =
        "HostedCheckoutResumeState(checkoutReference=<redacted>, " +
            "expiresAtEpochMilliseconds=$expiresAtEpochMilliseconds)"
}

/**
 * Host-provided secure persistence boundary for opaque hosted resume state.
 *
 * Implementations must encrypt or otherwise protect persisted state according to platform guidance.
 */
interface HostedCheckoutResumeStateStore {
    /** Loads the last opaque hosted resume state, if present. */
    suspend fun load(): HostedCheckoutResumeState?

    /** Atomically saves [state]. */
    suspend fun save(state: HostedCheckoutResumeState)

    /** Clears any saved hosted resume state. */
    fun clear()
}

/**
 * Composition-root configuration for hosted checkout presentation.
 *
 * @property launcher Host browser integration.
 * @property receiptEmail Callback that supplies the current receipt delivery address.
 * @property returnUrl Application return URL sent through the core-owned checkout operation.
 * @property allowedReturnSchemes Exact URI schemes accepted from browser return signals.
 * @property pollingPolicy Bounded authoritative refresh policy.
 * @property pollingDelay Delay implementation used between refresh attempts.
 * @property resumeStateStore Secure opaque resume-state storage.
 * @property clock Clock used to discard expired state.
 */
class HostedCheckoutConfiguration(
    val launcher: HostedCheckoutLauncher,
    val receiptEmail: suspend () -> String,
    val returnUrl: String,
    val allowedReturnSchemes: Set<String>,
    val pollingPolicy: HostedCheckoutPollingPolicy = HostedCheckoutPollingPolicy(),
    val pollingDelay: HostedCheckoutDelay = HostedCheckoutDelay { milliseconds -> delay(milliseconds) },
    val resumeStateStore: HostedCheckoutResumeStateStore = NoHostedCheckoutResumeStateStore,
    val clock: HostedCheckoutClock = HostedCheckoutClock { Clock.System.now().toEpochMilliseconds() },
) {
    /** Redacts receipt and URL-producing configuration from diagnostics. */
    override fun toString(): String =
        "HostedCheckoutConfiguration(launcher=<redacted>, receiptEmail=<redacted>, " +
            "returnUrl=<redacted>, allowedReturnSchemes=<redacted>)"
}

private data object NoHostedCheckoutResumeStateStore : HostedCheckoutResumeStateStore {
    override suspend fun load(): HostedCheckoutResumeState? = null

    override suspend fun save(state: HostedCheckoutResumeState) = Unit

    override fun clear() = Unit
}

private const val DefaultMaxRefreshAttempts = 3
private const val DefaultPollingIntervalMilliseconds = 1_000L
