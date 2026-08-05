package ru.vitrina.sdk.purchase

/**
 * Marks the low-level boundary implemented by official VitrinaKit provider adapter artifacts.
 *
 * Application feature code should use [ru.vitrina.sdk.VitrinaKit.purchase] instead.
 */
@RequiresOptIn(
    message = "This API is for VitrinaKit provider adapter artifacts, not application feature code.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class VitrinaKitPurchaseAdapterApi

/**
 * Presentation and local-recovery boundary implemented by official provider adapter artifacts.
 */
interface VitrinaKitPurchaseAdapter {
    /** Capability packaged by this adapter. */
    val capability: VitrinaKitPurchaseCapability

    /** Presents the server-issued instruction through the provider SDK. */
    @VitrinaKitPurchaseAdapterApi
    suspend fun present(instruction: VitrinaKitPurchaseInstruction): VitrinaKitAdapterPurchaseResult

    /** Queries purchases visible to the current provider account for restore. */
    @VitrinaKitPurchaseAdapterApi
    suspend fun queryRestorablePurchases(): List<VitrinaKitRestorablePurchase>

    /** Resumes an interrupted presentation using only opaque adapter state. */
    @VitrinaKitPurchaseAdapterApi
    suspend fun resume(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData,
    ): VitrinaKitAdapterPurchaseResult

    /**
     * Releases provider resources and cancels adapter-owned recovery work.
     *
     * The adapter must support lazy reinitialization if the SDK identifies a subscriber after logout.
     */
    fun close()
}

/**
 * Server-issued provider presentation instruction.
 *
 * @property attemptReference Opaque VitrinaKit attempt reference.
 * @property productId Provider product identifier selected by the server.
 * @property priceId Optional provider price or offer identifier selected by the server.
 * @property packageName Provider application package name.
 * @property accountBinding Opaque account-binding value supplied to the provider.
 * @property expiresAt Attempt expiry timestamp in ISO-8601 UTC format.
 */
@VitrinaKitPurchaseAdapterApi
data class VitrinaKitPurchaseInstruction(
    val attemptReference: String,
    val productId: String,
    val priceId: String?,
    val packageName: String,
    val accountBinding: String,
    val expiresAt: String,
) {
    /** Returns presentation metadata while redacting the account-binding value. */
    override fun toString(): String =
        "VitrinaKitPurchaseInstruction(attemptReference=$attemptReference, productId=$productId, " +
            "priceId=$priceId, packageName=$packageName, accountBinding=<redacted>, expiresAt=$expiresAt)"
}

/** Provider proof wrapper whose textual representation is always redacted. */
@VitrinaKitPurchaseAdapterApi
class VitrinaKitProviderProof(value: String) {
    internal val value: String = value

    /** Returns a representation that never includes provider proof. */
    override fun toString(): String = "VitrinaKitProviderProof(value=<redacted>)"
}

/**
 * Opaque provider resume data whose textual representation is always redacted.
 *
 * @property value Opaque value consumed only by the provider adapter that created it.
 */
@VitrinaKitPurchaseAdapterApi
class VitrinaKitPurchaseResumeData(val value: String) {
    /** Returns a representation that never includes the opaque resume value. */
    override fun toString(): String = "VitrinaKitPurchaseResumeData(value=<redacted>)"
}

/**
 * Safe adapter failure without provider payloads.
 *
 * @property message Redacted diagnostic message.
 */
@VitrinaKitPurchaseAdapterApi
data class VitrinaKitAdapterError(val message: String)

/** Outcome returned from provider presentation or resume. */
@VitrinaKitPurchaseAdapterApi
sealed interface VitrinaKitAdapterPurchaseResult {
    /**
     * Provider proof is ready for server confirmation.
     *
     * @property proof Proof passed directly to the core confirmation boundary.
     */
    data class ProofReady(
        val proof: VitrinaKitProviderProof,
    ) : VitrinaKitAdapterPurchaseResult {
        /** Returns a representation that never includes provider proof. */
        override fun toString(): String = "ProofReady(proof=<redacted>)"
    }

    /**
     * Provider operation remains pending with opaque resume state.
     *
     * @property resumeData Opaque state consumed only by the originating adapter.
     */
    data class Pending(
        val resumeData: VitrinaKitPurchaseResumeData,
    ) : VitrinaKitAdapterPurchaseResult {
        /** Returns a representation that never includes opaque resume state. */
        override fun toString(): String = "Pending(resumeData=<redacted>)"
    }

    /** User cancelled provider presentation. */
    data object Cancelled : VitrinaKitAdapterPurchaseResult

    /**
     * Provider adapter failed safely.
     *
     * @property error Safe adapter failure.
     */
    data class Failure(val error: VitrinaKitAdapterError) : VitrinaKitAdapterPurchaseResult
}

/**
 * Provider purchase eligible for restore.
 *
 * @property placementId Placement used to resolve the published offering.
 * @property productReference Stable product key or identifier returned by a paywall.
 * @property proof Provider proof submitted directly to VitrinaKit.
 */
@VitrinaKitPurchaseAdapterApi
data class VitrinaKitRestorablePurchase(
    val placementId: String,
    val productReference: String,
    val proof: VitrinaKitProviderProof,
) {
    /** Returns restore metadata while always redacting provider proof. */
    override fun toString(): String =
        "VitrinaKitRestorablePurchase(placementId=$placementId, productReference=$productReference, proof=<redacted>)"
}
