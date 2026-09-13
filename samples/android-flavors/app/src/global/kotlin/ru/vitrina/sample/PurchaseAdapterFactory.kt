@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sample

import android.content.Context
import ru.vitrina.sdk.VitrinaKitConfig
import ru.vitrina.sdk.googleplay.GooglePlayActivityProvider
import ru.vitrina.sdk.googleplay.GooglePlayPurchaseAdapter

fun createArtifactPurchaseConfig(
    context: Context,
    publicApiKey: String,
    activityProvider: GooglePlayActivityProvider,
): VitrinaKitConfig {
    val adapter = GooglePlayPurchaseAdapter(
        context = context,
        activityProvider = activityProvider,
    )
    return VitrinaKitConfig.Builder(publicApiKey)
        .withPurchaseAdapter(adapter)
        .build()
}
