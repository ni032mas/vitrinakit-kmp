@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk

import android.content.Context
import androidx.startup.AppInitializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ru.vitrina.sdk.installation.PreferencesFileName
import ru.vitrina.sdk.installation.VitrinaKitInstallationIdInitializer
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.purchase.VitrinaKitAdapterPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseCapability
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseInstruction
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResumeData
import ru.vitrina.sdk.purchase.VitrinaKitProviderProof
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase

/**
 * Proves the fix that motivated issue #29: on Android, `activate()` must succeed without the
 * integrator supplying an installation id storage. Before the Android target existed, the JVM
 * default ran on Android and `activate()` always failed here.
 *
 * Robolectric's host-test JVM does not eagerly instantiate manifest-declared content providers the
 * way a real device process start does, so [AppInitializer] is driven explicitly — see
 * [ru.vitrina.sdk.installation.DefaultInstallationIdStorageAndroidTest] for why that is the
 * androidx.startup-documented way to close that gap, not a substitute for the real wiring.
 */
@RunWith(RobolectricTestRunner::class)
class VitrinaKitAndroidActivationTest {
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

    @AfterTest
    fun tearDown() {
        VitrinaKit.resetForTesting()
    }

    @Test
    fun activationSucceedsWithoutACustomInstallationIdStorage() {
        VitrinaKit.resetForTesting()

        val result = VitrinaKit.activate(
            VitrinaKitConfig.Builder("pk_test")
                .withPurchaseAdapter(NoopPurchaseAdapter())
                .build(),
        )

        assertIs<VitrinaKitResult.Success<Unit>>(result)
    }
}

private class NoopPurchaseAdapter : VitrinaKitPurchaseAdapter {
    override val capability: VitrinaKitPurchaseCapability = VitrinaKitPurchaseCapability.GOOGLE_PLAY

    override suspend fun present(
        instruction: VitrinaKitPurchaseInstruction,
    ): VitrinaKitAdapterPurchaseResult = VitrinaKitAdapterPurchaseResult.Cancelled

    override suspend fun queryRestorablePurchases(): List<VitrinaKitRestorablePurchase> = emptyList()

    override suspend fun resume(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData,
    ): VitrinaKitAdapterPurchaseResult = VitrinaKitAdapterPurchaseResult.Cancelled

    override suspend fun recover(
        instruction: VitrinaKitPurchaseInstruction,
        resumeData: VitrinaKitPurchaseResumeData?,
    ): VitrinaKitAdapterPurchaseResult? = null

    override fun onProofAccepted(proof: VitrinaKitProviderProof) = Unit

    override fun close() = Unit
}
