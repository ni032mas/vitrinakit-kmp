@file:OptIn(VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.purchase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.vitrina.sdk.model.VitrinaKitProfile

/** Purchase capability packaged by a provider adapter. */
@Serializable
enum class VitrinaKitPurchaseCapability {
    /** Google Play Billing capability. */
    @SerialName("google_play")
    GOOGLE_PLAY,

    /** RuStore Pay capability. */
    @SerialName("rustore")
    RUSTORE,
}

/** Durable status of a provider-neutral purchase attempt. */
@Serializable
enum class VitrinaKitPurchaseAttemptStatus {
    /** Attempt record was created. */
    @SerialName("created")
    CREATED,

    /** Provider presentation data is ready. */
    @SerialName("provider_ready")
    PROVIDER_READY,

    /** Provider UI was presented. */
    @SerialName("presented")
    PRESENTED,

    /** Provider proof was received. */
    @SerialName("proof_received")
    PROOF_RECEIVED,

    /** Provider proof is being validated. */
    @SerialName("validating")
    VALIDATING,

    /** Provider purchase remains pending. */
    @SerialName("pending")
    PENDING,

    /** Purchase completed successfully. */
    @SerialName("succeeded")
    SUCCEEDED,

    /** Purchase was cancelled. */
    @SerialName("cancelled")
    CANCELLED,

    /** Purchase failed. */
    @SerialName("failed")
    FAILED,

    /** Purchase was valid but existing access remains authoritative. */
    @SerialName("duplicate_coverage")
    DUPLICATE_COVERAGE,
}

/** Stable purchase error code safe for application handling. */
@Serializable
enum class VitrinaKitPurchaseErrorCode {
    /** SDK activation is required. */
    @SerialName("not_activated")
    NOT_ACTIVATED,

    /** Subscriber identity is required. */
    @SerialName("subscriber_auth_required")
    SUBSCRIBER_AUTH_REQUIRED,

    /** Identity policy does not allow this operation. */
    @SerialName("identity_policy_mismatch")
    IDENTITY_POLICY_MISMATCH,

    /** Customer token is invalid. */
    @SerialName("identity_token_invalid")
    IDENTITY_TOKEN_INVALID,

    /** Customer token has expired. */
    @SerialName("identity_token_expired")
    IDENTITY_TOKEN_EXPIRED,

    /** Customer token was already exchanged. */
    @SerialName("identity_replayed")
    IDENTITY_REPLAYED,

    /** Opaque subscriber session is invalid. */
    @SerialName("identity_session_invalid")
    IDENTITY_SESSION_INVALID,

    /** Opaque subscriber session has expired. */
    @SerialName("identity_session_expired")
    IDENTITY_SESSION_EXPIRED,

    /** Request fields or payload are invalid. */
    @SerialName("invalid_request")
    INVALID_REQUEST,

    /** Another purchase presentation is already active. */
    @SerialName("purchase_in_progress")
    PURCHASE_IN_PROGRESS,

    /** Provider purchase is still pending. */
    @SerialName("purchase_pending")
    PURCHASE_PENDING,

    /** Existing active access prevents another purchase. */
    @SerialName("active_entitlement_exists")
    ACTIVE_ENTITLEMENT_EXISTS,

    /** Checkout requires a receipt delivery email address. */
    @SerialName("receipt_email_required")
    RECEIPT_EMAIL_REQUIRED,

    /** Receipt email is present but invalid. */
    @SerialName("invalid_receipt_email")
    INVALID_RECEIPT_EMAIL,

    /** Checkout requires a verified email before it can proceed. */
    @SerialName("email_verification_required")
    EMAIL_VERIFICATION_REQUIRED,

    /** No unique ready sales channel is available. */
    @SerialName("sales_channel_unavailable")
    SALES_CHANNEL_UNAVAILABLE,

    /** Server-selected capability is absent from this build. */
    @SerialName("provider_not_supported_by_build")
    PROVIDER_NOT_SUPPORTED_BY_BUILD,

    /** Provider proof could not be verified. */
    @SerialName("provider_proof_invalid")
    PROVIDER_PROOF_INVALID,

    /** Provider validation is temporarily unavailable. */
    @SerialName("provider_validation_unavailable")
    PROVIDER_VALIDATION_UNAVAILABLE,

    /** Purchase proof belongs to another subscriber. */
    @SerialName("purchase_owned_by_different_user")
    PURCHASE_OWNED_BY_DIFFERENT_USER,

    /** Purchase attempt expired before confirmation. */
    @SerialName("purchase_attempt_expired")
    PURCHASE_ATTEMPT_EXPIRED,

    /** Idempotency key was reused for a different operation. */
    @SerialName("idempotency_key_reused")
    IDEMPOTENCY_KEY_REUSED,

    /** Purchase attempt could not be found. */
    @SerialName("purchase_attempt_not_found")
    PURCHASE_ATTEMPT_NOT_FOUND,

    /** Provider adapter could not complete presentation. */
    @SerialName("adapter_failure")
    ADAPTER_FAILURE,

    /** Network transport failed. */
    @SerialName("network_error")
    NETWORK_ERROR,

    /** Server response did not contain authoritative purchase state. */
    @SerialName("server_validation_failed")
    SERVER_VALIDATION_FAILED,

    /** Server failed to complete the request. */
    @SerialName("internal_error")
    INTERNAL_ERROR,

    /** A newer server code that this SDK version does not recognize. */
    @SerialName("unknown")
    UNKNOWN,
}

/**
 * Safe typed purchase failure derived from an RFC 9457 Problem Details response or a local SDK failure.
 *
 * @property code Stable error category.
 * @property message Safe diagnostic message without credentials or provider proof.
 * @property retryable Whether retrying later may succeed.
 * @property supportReference Opaque request reference suitable for support.
 */
data class VitrinaKitPurchaseError(
    val code: VitrinaKitPurchaseErrorCode,
    val message: String,
    val retryable: Boolean,
    val supportReference: String?,
)

/** Provider-neutral purchase outcome. */
sealed interface VitrinaKitPurchaseResult {
    /**
     * Server-confirmed purchase with authoritative subscriber state.
     *
     * @property purchaseReference Opaque VitrinaKit purchase reference.
     * @property profile Authoritative subscriber profile returned after confirmation.
     */
    data class Success(
        val purchaseReference: String,
        val profile: VitrinaKitProfile,
    ) : VitrinaKitPurchaseResult

    /**
     * Purchase still awaiting provider or server completion.
     *
     * @property attemptReference Opaque resumable attempt reference.
     * @property profile Last authoritative profile, when available.
     */
    data class Pending(
        val attemptReference: String,
        val profile: VitrinaKitProfile?,
    ) : VitrinaKitPurchaseResult

    /** User cancelled provider presentation. */
    data object Cancelled : VitrinaKitPurchaseResult

    /**
     * Purchase failed without exposing provider proof.
     *
     * @property error Safe typed failure.
     */
    data class Failure(val error: VitrinaKitPurchaseError) : VitrinaKitPurchaseResult
}

/**
 * Safe result item returned by restore.
 *
 * @property purchaseReference Opaque VitrinaKit purchase reference.
 * @property status Server-authoritative restore status.
 * @property replayed Whether an existing restore result was reused.
 */
data class VitrinaKitRestoredPurchase(
    val purchaseReference: String,
    val status: VitrinaKitPurchaseAttemptStatus,
    val replayed: Boolean,
)

/** Provider-neutral restore outcome. */
sealed interface VitrinaKitRestoreResult {
    /**
     * One or more provider purchases were reconciled.
     *
     * @property profile Authoritative subscriber profile after restore.
     * @property purchases Safe restored purchase references.
     */
    data class Success(
        val profile: VitrinaKitProfile,
        val purchases: List<VitrinaKitRestoredPurchase>,
    ) : VitrinaKitRestoreResult

    /**
     * No restorable provider purchases were found; existing server access is preserved.
     *
     * @property profile Current authoritative subscriber profile.
     */
    data class NoPurchases(val profile: VitrinaKitProfile) : VitrinaKitRestoreResult

    /**
     * Restore failed.
     *
     * @property error Safe typed failure.
     */
    data class Failure(val error: VitrinaKitPurchaseError) : VitrinaKitRestoreResult
}

internal sealed interface PurchaseApiResult<out T> {
    data class Success<T>(val value: T) : PurchaseApiResult<T>
    data class Failure(val error: VitrinaKitPurchaseError) : PurchaseApiResult<Nothing>
}

internal data class PurchaseAttempt(
    val reference: String,
    val status: VitrinaKitPurchaseAttemptStatus,
    val reason: String?,
    val expiresAt: String,
    val capability: VitrinaKitPurchaseCapability,
    val instruction: VitrinaKitPurchaseInstruction,
)

internal data class PurchaseConfirmation(
    val attempt: PurchaseAttempt,
    val pending: Boolean,
    val profile: VitrinaKitProfile?,
)

internal data class PurchaseRestoreResponse(
    val purchases: List<VitrinaKitRestoredPurchase>,
    val profile: VitrinaKitProfile,
)

internal data class SubscriberScope(
    val cacheKey: ru.vitrina.sdk.cache.SubscriberCacheKey,
    val subscriberId: String? = null,
    val sessionToken: String? = null,
    val purchaseAttemptGeneration: Long = 0,
) {
    override fun toString(): String =
        "SubscriberScope(cacheKey=$cacheKey, subscriberId=<redacted>, sessionToken=<redacted>)"
}
