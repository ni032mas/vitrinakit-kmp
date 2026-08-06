package ru.vitrina.sdk.purchase

import ru.vitrina.sdk.model.VitrinaKitPaywallProduct
import ru.vitrina.sdk.model.VitrinaKitProfile
import ru.vitrina.sdk.model.VitrinaKitPurchase
import ru.vitrina.sdk.model.VitrinaKitResult

/**
 * Core-owned checkout operation for the subscriber identity already bound to [ru.vitrina.sdk.VitrinaKit].
 *
 * A hosted adapter supplies its configured receipt and return values without receiving an API key,
 * subscriber session token, or transport implementation.
 */
@VitrinaKitPurchaseAdapterApi
fun interface VitrinaKitHostedCheckoutOperation {
    /** Creates a hosted checkout for the bound identity and selected product. */
    suspend fun create(
        receiptEmail: String,
        returnUrl: String,
    ): VitrinaKitResult<VitrinaKitPurchase>
}

/** Core-owned authoritative profile refresh for the currently bound subscriber identity. */
@VitrinaKitPurchaseAdapterApi
fun interface VitrinaKitHostedProfileOperation {
    /** Refreshes and caches the authoritative profile for the bound identity. */
    suspend fun refresh(): VitrinaKitResult<VitrinaKitProfile>
}

/**
 * Hosted purchase request whose network operations remain owned by the SDK core.
 *
 * @property product Product selected from the current subscriber's VitrinaKit paywall.
 * @property checkout Bound checkout creation operation that does not expose transport credentials.
 * @property profile Bound authoritative profile refresh operation.
 */
@VitrinaKitPurchaseAdapterApi
data class VitrinaKitHostedPurchaseRequest(
    val product: VitrinaKitPaywallProduct,
    val checkout: VitrinaKitHostedCheckoutOperation,
    val profile: VitrinaKitHostedProfileOperation,
) {
    /** Returns safe metadata while redacting core-owned operations. */
    override fun toString(): String =
        "VitrinaKitHostedPurchaseRequest(productKey=${product.productKey}, " +
            "checkout=<redacted>, profile=<redacted>)"
}

/**
 * Hosted restore request whose profile operation is bound to the current subscriber identity.
 *
 * @property profile Bound authoritative profile refresh operation.
 */
@VitrinaKitPurchaseAdapterApi
data class VitrinaKitHostedRestoreRequest(
    val profile: VitrinaKitHostedProfileOperation,
) {
    /** Returns a representation that does not expose the core callback. */
    override fun toString(): String = "VitrinaKitHostedRestoreRequest(profile=<redacted>)"
}

/**
 * Provider-neutral hosted purchase boundary implemented by the official hosted adapter artifact.
 *
 * Hosted checkout deliberately remains separate from native purchase capabilities because its
 * server endpoint does not serialize a native provider purchase-attempt capability.
 */
@VitrinaKitPurchaseAdapterApi
interface VitrinaKitHostedPurchaseAdapter : VitrinaKitHostedMigrationAdapter {
    /** Presents and reconciles one hosted checkout for the bound identity. */
    suspend fun purchase(request: VitrinaKitHostedPurchaseRequest): VitrinaKitPurchaseResult

    /** Refreshes hosted purchase access for the bound identity. */
    suspend fun restore(request: VitrinaKitHostedRestoreRequest): VitrinaKitRestoreResult
}

/**
 * Safe legacy migration request delivered only to a configured hosted adapter.
 *
 * @property product Product selected by the application from a VitrinaKit paywall.
 * @property externalUserId External user ID from the current SDK identity, never from the legacy call.
 * @property checkout Core-owned operation that creates checkout for that bound identity and product.
 */
@VitrinaKitPurchaseAdapterApi
data class VitrinaKitHostedMigrationRequest(
    val product: VitrinaKitPaywallProduct,
    val externalUserId: String,
    val checkout: VitrinaKitHostedCheckoutOperation,
) {
    /** Returns migration metadata without logging subscriber identity or operation internals. */
    override fun toString(): String =
        "VitrinaKitHostedMigrationRequest(productKey=${product.productKey}, " +
            "externalUserId=<redacted>, checkout=<redacted>)"
}

/**
 * Optional boundary used only by the deprecated hosted-shaped facade during one-line migration.
 *
 * Task-specific hosted adapters keep receipt and return configuration internally and invoke the
 * core-owned checkout operation from [VitrinaKitHostedMigrationRequest].
 */
@VitrinaKitPurchaseAdapterApi
interface VitrinaKitHostedMigrationAdapter {
    /** Delegates legacy hosted purchase through the currently bound SDK identity. */
    suspend fun purchaseForBoundIdentity(
        request: VitrinaKitHostedMigrationRequest,
    ): VitrinaKitResult<VitrinaKitPurchase>

    /** Releases adapter-owned resources; a later identify may lazily initialize them again. */
    fun close()
}
