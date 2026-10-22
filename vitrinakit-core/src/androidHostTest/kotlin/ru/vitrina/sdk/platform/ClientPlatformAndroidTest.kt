package ru.vitrina.sdk.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Android artifact always names Android, although the JVM underneath reports `os.name` as
 * Linux: the JVM artifact's mapping must never decide an Android app's platform.
 */
class ClientPlatformAndroidTest {

    @Test
    fun androidArtifactNamesAndroid() {
        assertEquals(VitrinaClientPlatform.ANDROID, currentClientPlatform())
    }
}
