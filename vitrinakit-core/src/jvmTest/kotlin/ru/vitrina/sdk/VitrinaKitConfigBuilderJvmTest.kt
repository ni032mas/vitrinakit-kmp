package ru.vitrina.sdk

import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import ru.vitrina.sdk.installation.VitrinaKitInstallationIdStorage

/**
 * Regression coverage for the eager-construction defect reported against issue #29: the JVM
 * default touches `java.util.prefs.Preferences.userRoot()` at construction time (logging a warning
 * on platforms, like Android, where it is non-functional), even when the integrator supplies their
 * own storage. [Preferences.node] creates the node as soon as it is requested, so
 * [Preferences.nodeExists] on that path is a direct, real-platform-API signal of whether the
 * default was ever constructed.
 */
class VitrinaKitConfigBuilderJvmTest {
    @BeforeTest
    fun removeDefaultInstallationPreferencesNode() {
        removeDefaultInstallationPreferencesNodeIfPresent()
    }

    @AfterTest
    fun cleanUp() {
        removeDefaultInstallationPreferencesNodeIfPresent()
    }

    @Test
    fun suppliedStorageIsUsedWithoutConstructingTheDefaultPreferencesBackedStorage() {
        VitrinaKitConfig.Builder("pk_test")
            .withInstallationIdStorage(NoopInstallationIdStorage)
            .build()

        assertFalse(Preferences.userRoot().nodeExists(DefaultInstallationIdPreferencesNode))
    }
}

private fun removeDefaultInstallationPreferencesNodeIfPresent() {
    val root = Preferences.userRoot()
    if (root.nodeExists(DefaultInstallationIdPreferencesNode)) {
        root.node(DefaultInstallationIdPreferencesNode).removeNode()
        root.flush()
    }
}

private const val DefaultInstallationIdPreferencesNode = "ru/vitrina/sdk"

private object NoopInstallationIdStorage : VitrinaKitInstallationIdStorage {
    override fun read(): String? = null

    override fun write(installationId: String) = Unit
}
