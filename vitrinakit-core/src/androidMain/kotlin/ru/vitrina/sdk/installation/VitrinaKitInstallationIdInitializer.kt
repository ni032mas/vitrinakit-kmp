package ru.vitrina.sdk.installation

import android.content.Context
import androidx.startup.Initializer

/**
 * Captures the application [Context] through `androidx.startup` so [defaultInstallationIdStorage]
 * works on Android without the integrator passing a context. `androidx.startup` runs this before
 * any app code, via a manifest-declared content provider, so the context is available by the time
 * the SDK is configured.
 */
internal class VitrinaKitInstallationIdInitializer : Initializer<Context> {
    override fun create(context: Context): Context {
        applicationContext = context.applicationContext
        return applicationContext
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    internal companion object {
        lateinit var applicationContext: Context
            private set
    }
}
