@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.googleplay

import ru.vitrina.sdk.purchase.VitrinaKitProviderProof
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase

/**
 * Resolves a Google Play product ID to public catalog references during restore composition.
 *
 * This mapping belongs in the application composition root. It must not be inferred from prices,
 * base-plan IDs, or other store metadata.
 */
fun interface GooglePlayRestoreReferenceResolver {
    /** Returns an explicit catalog mapping or an explicit skip policy for [providerProductId]. */
    fun resolve(providerProductId: String): GooglePlayRestoreResolution
}

/** Explicit restore policy for a Google Play product visible to the current Play account. */
sealed interface GooglePlayRestoreResolution {
    /**
     * Maps the provider product to the logical catalog references required by restore.
     *
     * @property placementId Public placement used to load the offering.
     * @property productReference Stable logical product reference from that offering.
     */
    data class Mapped(
        val placementId: String,
        val productReference: String,
    ) : GooglePlayRestoreResolution

    /** Explicitly ignores a product that this application does not map into its Vitrina catalog. */
    data object Skip : GooglePlayRestoreResolution
}

internal fun BillingPurchase.toProof(): VitrinaKitProviderProof =
    VitrinaKitProviderProof(purchaseToken)

internal fun BillingPurchase.toRestorablePurchase(
    mapping: GooglePlayRestoreResolution.Mapped,
    proof: VitrinaKitProviderProof,
): VitrinaKitRestorablePurchase = VitrinaKitRestorablePurchase(
    placementId = mapping.placementId,
    productReference = mapping.productReference,
    proof = proof,
)
