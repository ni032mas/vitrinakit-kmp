package ru.vitrina.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * VitrinaKit API environment used by the SDK.
 */
@Serializable
enum class VitrinaEnvironment {
    /** Sandbox environment for tests and non-production integrations. */
    @SerialName("sandbox")
    SANDBOX,

    /** Production environment for live integrations. */
    @SerialName("production")
    PRODUCTION,
}

/**
 * Billing interval unit for recurring and lifetime prices.
 */
@Serializable
enum class BillingIntervalUnit {
    /** Daily billing interval. */
    @SerialName("day")
    DAY,

    /** Monthly billing interval. */
    @SerialName("month")
    MONTH,

    /** Yearly billing interval. */
    @SerialName("year")
    YEAR,

    /** One-time lifetime access interval. */
    @SerialName("lifetime")
    LIFETIME,
}

/**
 * Normalized subscription lifecycle status exposed by VitrinaKit.
 */
@Serializable
enum class SubscriptionStatus {
    /** Subscription checkout was started but is not active yet. */
    @SerialName("incomplete")
    INCOMPLETE,

    /** Subscription is active and should grant entitlements. */
    @SerialName("active")
    ACTIVE,

    /** Subscription is temporarily active during a provider grace period. */
    @SerialName("grace_period")
    GRACE_PERIOD,

    /** Subscription payment is overdue. */
    @SerialName("past_due")
    PAST_DUE,

    /** Subscription was canceled. */
    @SerialName("canceled")
    CANCELED,

    /** Provider is retrying failed billing. */
    @SerialName("billing_retry")
    BILLING_RETRY,

    /** Subscription is paused. */
    @SerialName("paused")
    PAUSED,

    /** Subscription expired. */
    @SerialName("expired")
    EXPIRED,

    /** Subscription payment was refunded. */
    @SerialName("refunded")
    REFUNDED,

    /** Subscription access was revoked. */
    @SerialName("revoked")
    REVOKED,
}

/**
 * Access right granted by a product or subscription.
 *
 * @property key Stable entitlement key used by the integrating app.
 * @property name Human-readable entitlement name.
 */
@Serializable
data class Entitlement(
    /** Stable entitlement key used by the integrating app. */
    val key: String,
    /** Human-readable entitlement name. */
    val name: String,
)

/**
 * Product and price option rendered inside a paywall.
 *
 * @property productId Server-owned product identifier.
 * @property productKey Stable product key.
 * @property productName Human-readable product name.
 * @property planId Server-owned plan identifier.
 * @property planKey Stable plan key.
 * @property planName Human-readable plan name.
 * @property priceId Server-owned price identifier.
 * @property amountMinor Price amount in minor currency units.
 * @property currency ISO 4217 currency code.
 * @property intervalUnit Billing interval unit.
 * @property intervalCount Number of interval units per billing period.
 * @property trialIntervalUnit Optional trial interval unit.
 * @property trialIntervalCount Number of trial interval units.
 * @property highlighted Whether this option should be visually highlighted.
 * @property sortOrder Server-defined display order.
 * @property entitlements Entitlements granted by this option.
 */
@Serializable
data class PaywallProduct(
    /** Server-owned product identifier. */
    @SerialName("product_id")
    val productId: String,
    /** Stable product key. */
    @SerialName("product_key")
    val productKey: String,
    /** Human-readable product name. */
    @SerialName("product_name")
    val productName: String,
    /** Server-owned plan identifier. */
    @SerialName("plan_id")
    val planId: String,
    /** Stable plan key. */
    @SerialName("plan_key")
    val planKey: String,
    /** Human-readable plan name. */
    @SerialName("plan_name")
    val planName: String,
    /** Server-owned price identifier. */
    @SerialName("price_id")
    val priceId: String,
    /** Price amount in minor currency units. */
    @SerialName("amount_minor")
    val amountMinor: Long,
    /** ISO 4217 currency code. */
    val currency: String,
    /** Billing interval unit. */
    @SerialName("interval_unit")
    val intervalUnit: BillingIntervalUnit,
    /** Number of interval units per billing period. */
    @SerialName("interval_count")
    val intervalCount: Int,
    /** Optional trial interval unit. */
    @SerialName("trial_interval_unit")
    val trialIntervalUnit: BillingIntervalUnit? = null,
    /** Number of trial interval units. */
    @SerialName("trial_interval_count")
    val trialIntervalCount: Int = 0,
    /** Whether this option should be visually highlighted. */
    val highlighted: Boolean,
    /** Server-defined display order. */
    @SerialName("sort_order")
    val sortOrder: Int,
    /** Entitlements granted by this option. */
    val entitlements: List<Entitlement> = emptyList(),
)

/**
 * Paywall payload returned for a placement.
 *
 * @property placementKey Placement key requested by the app.
 * @property products Product and price options available for purchase.
 */
@Serializable
data class Paywall(
    /** Placement key requested by the app. */
    @SerialName("placement_key")
    val placementKey: String,
    /** Product and price options available for purchase. */
    val products: List<PaywallProduct>,
)

/**
 * Hosted checkout session created by VitrinaKit.
 *
 * @property id Checkout session identifier.
 * @property paymentId VitrinaKit payment identifier.
 * @property providerPaymentId Payment provider identifier.
 * @property confirmationUrl URL that opens provider-hosted payment confirmation.
 * @property status Current checkout session status.
 * @property expiresAt Expiration timestamp in ISO-8601 UTC format.
 * @property purchaseAttemptReference Purchase attempt this checkout belongs to.
 * @property reused Whether the session reused an existing open checkout.
 */
@Serializable
data class CheckoutSession(
    /** Checkout session identifier. */
    val id: String,
    /**
     * Purchase attempt this checkout belongs to.
     *
     * Every purchase-attempt route addresses the attempt, not the session, so this — never [id] —
     * is what reads the attempt's status or ends it.
     */
    @SerialName("purchase_attempt_reference")
    val purchaseAttemptReference: String,
    /** VitrinaKit payment identifier. */
    @SerialName("payment_id")
    val paymentId: String,
    /** Payment provider identifier. */
    @SerialName("provider_payment_id")
    val providerPaymentId: String,
    /** URL that opens provider-hosted payment confirmation. */
    @SerialName("confirmation_url")
    val confirmationUrl: String,
    /** Current checkout session status. */
    val status: String,
    /** Expiration timestamp in ISO-8601 UTC format. */
    @SerialName("expires_at")
    val expiresAt: String,
    /** Whether the session reused an existing open checkout. */
    val reused: Boolean,
)

/**
 * Provider or store source for subscriber access state.
 */
@Serializable
enum class EntitlementSource {
    /** Apple App Store subscription source. */
    @SerialName("apple")
    APPLE,

    /** Google Play subscription source. */
    @SerialName("google")
    GOOGLE,

    /** RuStore subscription source. */
    @SerialName("rustore")
    RUSTORE,

    /** YooKassa hosted checkout source. */
    @SerialName("yookassa")
    YOOKASSA,
}

/**
 * State of one entitlement attached to a subscriber.
 *
 * @property key Stable entitlement key used by the integrating app.
 * @property status Current subscription status backing this entitlement.
 * @property hasAccess Whether this entitlement currently grants access.
 * @property expiresAt Expiration timestamp in ISO-8601 UTC format.
 * @property planKey Stable plan key that granted this entitlement.
 * @property productKey Stable product key that granted this entitlement.
 * @property source Provider or store source for this access state.
 * @property autoRenewEnabled Whether provider-side renewal is enabled.
 * @property inactiveReason Stable reason when the entitlement does not grant access.
 */
@Serializable
data class SubscriberEntitlementState(
    /** Stable entitlement key used by the integrating app. */
    val key: String,
    /** Current subscription status backing this entitlement. */
    val status: SubscriptionStatus,
    /** Whether this entitlement currently grants access. */
    @SerialName("has_access")
    val hasAccess: Boolean,
    /** Expiration timestamp in ISO-8601 UTC format. */
    @SerialName("expires_at")
    val expiresAt: String? = null,
    /** Stable plan key that granted this entitlement. */
    @SerialName("plan_key")
    val planKey: String? = null,
    /** Stable product key that granted this entitlement. */
    @SerialName("product_key")
    val productKey: String? = null,
    /** Provider or store source for this access state. */
    val source: EntitlementSource,
    /** Whether provider-side renewal is enabled. */
    @SerialName("auto_renew_enabled")
    val autoRenewEnabled: Boolean,
    /** Stable reason when the entitlement does not grant access. */
    @SerialName("inactive_reason")
    val inactiveReason: String? = null,
)

/**
 * Aggregated access state for one external user.
 *
 * @property externalUserId Integrating app user identifier.
 * @property hasAccess Whether the subscriber currently has paid access.
 * @property entitlements Entitlements known for the subscriber.
 */
@Serializable
data class SubscriberState(
    /** Integrating app user identifier. */
    @SerialName("external_user_id")
    val externalUserId: String,
    /** Whether the subscriber currently has paid access. */
    @SerialName("has_access")
    val hasAccess: Boolean,
    /** Entitlements known for the subscriber. */
    val entitlements: List<SubscriberEntitlementState>,
    /** Whether startup store restoration is still resolving the authoritative access state. */
    @Transient
    val accessResolution: VitrinaKitAccessResolution = VitrinaKitAccessResolution.CURRENT,
)

/** Startup resolution state for the access information exposed by [VitrinaKitProfile]. */
enum class VitrinaKitAccessResolution {
    /** The SDK is querying the store and access may still change. */
    CHECKING,

    /** The profile reflects the latest server-confirmed store restoration or explicit refresh. */
    CURRENT,

    /** Automatic restoration could not complete, so the last known access state may be incomplete. */
    UNAVAILABLE,
}

/**
 * Result of associating the current installation with an application user identifier.
 *
 * @property merged Whether installation-scoped purchase ownership was merged into the subscriber.
 * @property profile Authoritative subscriber profile after identification.
 */
data class VitrinaKitIdentifyResult(
    /** Whether installation-scoped purchase ownership was merged into the subscriber. */
    val merged: Boolean,
    /** Authoritative subscriber profile after identification. */
    val profile: VitrinaKitProfile,
)

/**
 * Result wrapper used by SDK operations.
 */
sealed class VitrinaResult<out T> {
    /**
     * Successful SDK operation.
     *
     * @property value Decoded operation value.
     */
    data class Success<T>(
        /** Decoded operation value. */
        val value: T,
    ) : VitrinaResult<T>()

    /**
     * Failed SDK operation.
     *
     * @property error Normalized SDK error.
     */
    data class Failure(
        /** Normalized SDK error. */
        val error: VitrinaError,
    ) : VitrinaResult<Nothing>()
}

/**
 * Normalized SDK error categories.
 */
sealed class VitrinaError {
    /**
     * Authentication or authorization failure.
     *
     * @property message Error message returned by the SDK or API.
     */
    data class Auth(
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaError()

    /**
     * Network, transport, or response decoding failure.
     *
     * @property message Error message returned by the SDK or API.
     */
    data class Network(
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaError()

    /**
     * Payment provider failure.
     *
     * @property message Error message returned by the SDK or API.
     */
    data class Provider(
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaError()

    /**
     * Checkout validation or state failure.
     *
     * @property code Public checkout error code returned by the API.
     * @property message Error message returned by the SDK or API.
     */
    data class Checkout(
        /** Public checkout error code returned by the API. */
        val code: VitrinaCheckoutErrorCode,
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaError()

    /**
     * SDK or server-side configuration failure.
     *
     * @property message Error message returned by the SDK or API.
     */
    data class Configuration(
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaError()

    /**
     * Subscriber or subscription lookup failure.
     *
     * @property message Error message returned by the SDK or API.
     */
    data class Subscription(
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaError()
}

/**
 * Public checkout error codes that consumer apps can handle explicitly.
 */
@Serializable
enum class VitrinaCheckoutErrorCode {
    /** Checkout requires a receipt delivery email address. */
    @SerialName("receipt_email_required")
    RECEIPT_EMAIL_REQUIRED,

    /** Receipt email is present but invalid. */
    @SerialName("invalid_receipt_email")
    INVALID_RECEIPT_EMAIL,

    /** User already has an active subscription and should not start another checkout. */
    @SerialName("checkout_active_subscription_exists")
    ACTIVE_SUBSCRIPTION_EXISTS,

    /** Checkout requires a verified email before it can proceed. */
    @SerialName("email_verification_required")
    EMAIL_VERIFICATION_REQUIRED,
}

/** VitrinaKit SDK environment alias used by the public facade. */
typealias VitrinaKitEnvironment = VitrinaEnvironment

/** VitrinaKit paywall payload returned by the public facade. */
typealias VitrinaKitPaywall = Paywall

/** VitrinaKit product option returned for a paywall. */
typealias VitrinaKitPaywallProduct = PaywallProduct

/** VitrinaKit hosted purchase or checkout session returned by the public facade. */
typealias VitrinaKitPurchase = CheckoutSession

/** VitrinaKit subscriber profile returned by the public facade. */
typealias VitrinaKitProfile = SubscriberState

/** VitrinaKit access level state returned inside a profile. */
typealias VitrinaKitAccessLevel = SubscriberEntitlementState

/**
 * Result wrapper returned by high-level VitrinaKit facade operations.
 */
sealed class VitrinaKitResult<out T> {
    /**
     * Successful SDK operation.
     *
     * @property value Decoded operation value.
     */
    data class Success<T>(
        /** Decoded operation value. */
        val value: T,
    ) : VitrinaKitResult<T>()

    /**
     * Failed SDK operation.
     *
     * @property error Normalized SDK error.
     */
    data class Failure(
        /** Normalized SDK error. */
        val error: VitrinaKitError,
    ) : VitrinaKitResult<Nothing>()
}

/**
 * Normalized error returned by high-level VitrinaKit facade operations.
 */
sealed class VitrinaKitError {
    /**
     * Authentication or authorization failure.
     *
     * @property message Error message returned by the SDK or API.
     */
    data class Auth(
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaKitError()

    /**
     * Network, transport, or response decoding failure.
     *
     * @property message Error message returned by the SDK or API.
     */
    data class Network(
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaKitError()

    /**
     * Payment provider failure.
     *
     * @property message Error message returned by the SDK or API.
     */
    data class Provider(
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaKitError()

    /**
     * Checkout validation or state failure.
     *
     * @property code Public checkout error code returned by the API.
     * @property message Error message returned by the SDK or API.
     */
    data class Checkout(
        /** Public checkout error code returned by the API. */
        val code: VitrinaCheckoutErrorCode,
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaKitError()

    /**
     * SDK or server-side configuration failure.
     *
     * @property message Error message returned by the SDK or API.
     */
    data class Configuration(
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaKitError()

    /**
     * Subscriber or subscription lookup failure.
     *
     * @property message Error message returned by the SDK or API.
     */
    data class Subscription(
        /** Error message returned by the SDK or API. */
        val message: String,
    ) : VitrinaKitError()
}
