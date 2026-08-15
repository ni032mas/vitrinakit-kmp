package ru.vitrina.sdk.installation

import java.util.prefs.Preferences

internal actual fun defaultInstallationIdStorage(): VitrinaKitInstallationIdStorage =
    object : VitrinaKitInstallationIdStorage {
        private val preferences = Preferences.userRoot().node(PreferencesNode)

        override fun read(): String? = preferences.get(InstallationIdKey, null)

        override fun write(installationId: String) {
            preferences.put(InstallationIdKey, installationId)
            preferences.flush()
        }
    }

private const val PreferencesNode = "ru/vitrina/sdk"
private const val InstallationIdKey = "installation_id"
