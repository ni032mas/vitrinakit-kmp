@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sample

import android.content.Context
import ru.vitrina.sdk.VitrinaKitConfig
import ru.vitrina.sdk.googleplay.GooglePlayActivityProvider
import ru.vitrina.sdk.googleplay.GooglePlayPurchaseAdapter
import ru.vitrina.sdk.googleplay.GooglePlayRestoreReferenceResolver
import ru.vitrina.sdk.googleplay.GooglePlayRestoreResolution

fun createArtifactPurchaseConfig(
    context: Context,
    publicApiKey: String,
    activityProvider: GooglePlayActivityProvider,
    restoreCatalog: Map<String, Pair<String, String>>,
): VitrinaKitConfig {
    val adapter = GooglePlayPurchaseAdapter(
        context = context,
        activityProvider = activityProvider,
        restoreReferenceResolver = GooglePlayRestoreReferenceResolver { providerProductId ->
            restoreCatalog[providerProductId]?.let { mapping ->
                GooglePlayRestoreResolution.Mapped(
                    placementId = mapping.first,
                    productReference = mapping.second,
                )
            } ?: GooglePlayRestoreResolution.Skip
        },
    )
    return VitrinaKitConfig.Builder(publicApiKey)
        .withPurchaseAdapter(adapter)
        .build()
}
