package ru.vitrina.sdk.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JVM artifact runs on desktops, so its platform is the host OS as the JVM names it in
 * `os.name`. An OS the API has no value for names no platform rather than a wrong one.
 */
class ClientPlatformJvmTest {

    @Test
    fun osNameMapsToItsPlatform() {
        mapOf(
            "Mac OS X" to VitrinaClientPlatform.MACOS,
            "Windows 11" to VitrinaClientPlatform.WINDOWS,
            "Windows Server 2022" to VitrinaClientPlatform.WINDOWS,
            "Linux" to VitrinaClientPlatform.LINUX,
            "FreeBSD" to null,
            "" to null,
            null to null,
        ).forEach { (osName, expected) ->
            assertEquals(expected, clientPlatformForOsName(osName), "os.name = $osName")
        }
    }

    @Test
    fun theRunningJvmReportsItsOwnOs() {
        assertEquals(clientPlatformForOsName(System.getProperty("os.name")), currentClientPlatform())
    }
}
