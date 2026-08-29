@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.googleplay

import ru.vitrina.sdk.purchase.VitrinaKitProviderProof
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase

internal fun BillingPurchase.toProof(): VitrinaKitProviderProof =
    VitrinaKitProviderProof(purchaseToken)

/**
 * Builds the restorable purchase the server resolves into a placement and catalog product.
 *
 * The provider product ID is read directly off this purchase, never inferred or supplied by the
 * application. It is `null` only when Google Play reports more than one product ID for a single
 * purchase, which the self-describing purchase token still lets the server resolve.
 */
internal fun BillingPurchase.toRestorablePurchase(
    proof: VitrinaKitProviderProof,
): VitrinaKitRestorablePurchase = VitrinaKitRestorablePurchase(
    providerProductId = productIds.singleOrNull(),
    proof = proof,
)
