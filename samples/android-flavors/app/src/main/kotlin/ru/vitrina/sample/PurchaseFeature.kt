package ru.vitrina.sample

import ru.vitrina.sdk.VitrinaKit
import ru.vitrina.sdk.VitrinaKitConfig
import ru.vitrina.sdk.model.VitrinaKitPaywall
import ru.vitrina.sdk.model.VitrinaKitPaywallProduct
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitRestoreResult

class PurchaseFeature {
    fun activate(config: VitrinaKitConfig): VitrinaKitResult<Unit> = VitrinaKit.activate(config)

    suspend fun identifyAndLoadPaywall(
        userId: String,
        placementId: String,
    ): VitrinaKitResult<VitrinaKitPaywall> {
        return when (val identified = VitrinaKit.identify(userId)) {
            is VitrinaKitResult.Success -> VitrinaKit.getPaywall(placementId)
            is VitrinaKitResult.Failure -> identified
        }
    }

    suspend fun purchase(product: VitrinaKitPaywallProduct): VitrinaKitPurchaseResult =
        VitrinaKit.purchase(product)

    suspend fun restore(): VitrinaKitRestoreResult = VitrinaKit.restorePurchases()

    suspend fun onForeground(): VitrinaKitPurchaseResult? = VitrinaKit.onForeground()

    fun logout(): VitrinaKitResult<Unit> = VitrinaKit.logout()
}
