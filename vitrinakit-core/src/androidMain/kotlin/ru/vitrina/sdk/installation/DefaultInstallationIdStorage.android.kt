package ru.vitrina.sdk.installation

import android.content.Context
import android.content.SharedPreferences

internal actual fun defaultInstallationIdStorage(): VitrinaKitInstallationIdStorage =
    object : VitrinaKitInstallationIdStorage {
        private val preferences: SharedPreferences by lazy {
            VitrinaKitInstallationIdInitializer.applicationContext
                .getSharedPreferences(PreferencesFileName, Context.MODE_PRIVATE)
        }

        override fun read(): String? = preferences.getString(InstallationIdKey, null)

        override fun write(installationId: String) {
            preferences.edit().putString(InstallationIdKey, installationId).apply()
        }
    }

internal const val PreferencesFileName = "ru.vitrina.sdk.installation"
private const val InstallationIdKey = "installation_id"
