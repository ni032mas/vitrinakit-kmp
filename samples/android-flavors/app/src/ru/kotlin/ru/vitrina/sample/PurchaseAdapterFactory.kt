@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sample

import ru.vitrina.sdk.VitrinaKitConfig
import ru.vitrina.sdk.hosted.HostedCheckoutAdapter
import ru.vitrina.sdk.hosted.HostedCheckoutConfiguration
import ru.vitrina.sdk.hosted.HostedCheckoutLauncher
import ru.vitrina.sdk.hosted.HostedCheckoutResumeStateStore

fun createArtifactPurchaseConfig(
    publicApiKey: String,
    launcher: HostedCheckoutLauncher,
    receiptEmail: suspend () -> String,
    returnUrl: String,
    allowedReturnSchemes: Set<String>,
    resumeStateStore: HostedCheckoutResumeStateStore,
): VitrinaKitConfig {
    val adapter = HostedCheckoutAdapter(
        HostedCheckoutConfiguration(
            launcher = launcher,
            receiptEmail = receiptEmail,
            returnUrl = returnUrl,
            allowedReturnSchemes = allowedReturnSchemes,
            resumeStateStore = resumeStateStore,
        ),
    )
    return VitrinaKitConfig.Builder(publicApiKey)
        .withHostedCheckoutAdapter(adapter)
        .build()
}
