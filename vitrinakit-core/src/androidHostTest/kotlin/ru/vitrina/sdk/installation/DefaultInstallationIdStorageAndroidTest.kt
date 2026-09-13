package ru.vitrina.sdk.installation

import android.content.Context
import androidx.startup.AppInitializer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Exercises the real Android default storage (SharedPreferences via the androidx.startup
 * initializer), not a fake: this is exactly the platform path issue #29 found broken in
 * production, because commonTest only ever exercised a MemoryInstallationIdStorage.
 *
 * Robolectric's host-test JVM does not eagerly instantiate manifest-declared content providers
 * the way a real device process start does, so [AppInitializer] is driven explicitly here — the
 * androidx.startup-documented entry point for this exact gap. The manifest wiring itself (the
 * provider and its meta-data, which is what a real device relies on) is verified separately by
 * inspecting the merged manifest; what this test proves is that [VitrinaKitInstallationIdInitializer]
 * correctly captures the context and that the resulting storage persists.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultInstallationIdStorageAndroidTest {
    @BeforeTest
    fun runStartupInitializerAndClearPriorState() {
        val application = RuntimeEnvironment.getApplication()
        AppInitializer.getInstance(application)
            .initializeComponent(VitrinaKitInstallationIdInitializer::class.java)
        // Robolectric backs SharedPreferences with a real file that is not guaranteed to reset
        // between test classes in the same sandbox, so start each test from a clean slate.
        application.getSharedPreferences(PreferencesFileName, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun idWrittenOnFirstActivationIsReadBackByAFreshStorageInstance() {
        val firstStorage = defaultInstallationIdStorage()
        val installationId = loadOrCreateInstallationId(firstStorage).getOrThrow()

        // A new storage instance, backed by the same named SharedPreferences file, is what a
        // subsequent process (or a subsequent VitrinaKit.activate() call) would construct.
        val secondStorage = defaultInstallationIdStorage()
        assertEquals(installationId, secondStorage.read())
    }
}
