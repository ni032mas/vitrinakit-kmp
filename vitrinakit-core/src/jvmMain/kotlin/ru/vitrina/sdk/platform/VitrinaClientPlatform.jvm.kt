package ru.vitrina.sdk.platform

internal actual fun currentClientPlatform(): VitrinaClientPlatform? =
    clientPlatformForOsName(osName = runCatching { System.getProperty("os.name") }.getOrNull())

/**
 * Maps the JVM's `os.name` ("Mac OS X", "Windows 11", "Linux") to a platform. Any other OS names
 * none: no header is better than a wrong one.
 */
internal fun clientPlatformForOsName(osName: String?): VitrinaClientPlatform? = when {
    osName == null -> null
    osName.startsWith("Mac") -> VitrinaClientPlatform.MACOS
    osName.startsWith("Windows") -> VitrinaClientPlatform.WINDOWS
    osName.startsWith("Linux") -> VitrinaClientPlatform.LINUX
    else -> null
}
