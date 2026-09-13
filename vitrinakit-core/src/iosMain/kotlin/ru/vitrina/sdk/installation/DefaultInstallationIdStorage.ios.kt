package ru.vitrina.sdk.installation

import platform.Foundation.NSUserDefaults

internal actual fun defaultInstallationIdStorage(): VitrinaKitInstallationIdStorage =
    object : VitrinaKitInstallationIdStorage {
        private val defaults = NSUserDefaults.standardUserDefaults

        override fun read(): String? = defaults.stringForKey(InstallationIdKey)

        override fun write(installationId: String) {
            defaults.setObject(installationId, forKey = InstallationIdKey)
        }
    }

private const val InstallationIdKey = "ru.vitrina.sdk.installation_id"
