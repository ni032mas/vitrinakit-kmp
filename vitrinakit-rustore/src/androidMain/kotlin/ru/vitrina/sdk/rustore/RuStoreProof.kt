@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.rustore

import ru.vitrina.sdk.purchase.VitrinaKitProviderProof
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase

/**
 * Resolves a RuStore product ID to public catalog references during restore composition.
 *
 * This mapping belongs in the application composition root and is never inferred from provider
 * prices, titles, or other store metadata.
 */
fun interface RuStoreRestoreReferenceResolver {
    /** Returns an explicit catalog mapping or skip policy for [providerProductId]. */
    fun resolve(providerProductId: String): RuStoreRestoreResolution
}

/** Explicit restore policy for a purchase visible to the current RuStore session. */
sealed interface RuStoreRestoreResolution {
    /**
     * Maps a provider product to the logical catalog references required by restore.
     *
     * @property placementId Public placement used to load the offering.
     * @property productReference Stable logical product reference from that offering.
     */
    data class Mapped(
        val placementId: String,
        val productReference: String,
    ) : RuStoreRestoreResolution

    /** Explicitly ignores a provider product not mapped into this application's catalog. */
    data object Skip : RuStoreRestoreResolution
}

internal fun RuStorePurchase.toProof(): VitrinaKitProviderProof =
    VitrinaKitProviderProof(purchaseId)

internal fun RuStorePurchase.toRestorablePurchase(
    mapping: RuStoreRestoreResolution.Mapped,
    proof: VitrinaKitProviderProof,
): VitrinaKitRestorablePurchase = VitrinaKitRestorablePurchase(
    placementId = mapping.placementId,
    productReference = mapping.productReference,
    proof = proof,
)
