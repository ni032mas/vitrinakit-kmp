@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.rustore

import ru.vitrina.sdk.purchase.VitrinaKitProviderProof
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase

internal fun RuStorePurchase.toProof(): VitrinaKitProviderProof =
    VitrinaKitProviderProof(purchaseId)

/**
 * Builds the restorable purchase the server resolves into a placement and catalog product.
 *
 * The subscription id is read directly off this purchase, never inferred or supplied by the
 * application. RuStore's API cannot be queried without it, so it is always present here.
 */
internal fun RuStorePurchase.toRestorablePurchase(
    proof: VitrinaKitProviderProof,
): VitrinaKitRestorablePurchase = VitrinaKitRestorablePurchase(
    providerProductId = productId,
    proof = proof,
)
