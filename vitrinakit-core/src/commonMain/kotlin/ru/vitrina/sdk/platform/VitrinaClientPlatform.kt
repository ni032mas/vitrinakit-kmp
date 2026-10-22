package ru.vitrina.sdk.platform

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Platform every API call names in `X-Vitrina-Client-Platform`.
 *
 * A hosted provider shop takes payments from every platform at once, so this header is the only
 * thing that lets the API tell an iOS payment from an Android one. The server records a value it
 * does not know as `unknown` and never refuses a request over it.
 */
@Serializable
internal enum class VitrinaClientPlatform {
    @SerialName("ios")
    IOS,

    @SerialName("android")
    ANDROID,

    @SerialName("macos")
    MACOS,

    @SerialName("windows")
    WINDOWS,

    @SerialName("linux")
    LINUX,
}

/** The header value: the serializer's own name for the entry, so there is no second spelling. */
internal val VitrinaClientPlatform.headerValue: String
    get() = VitrinaClientPlatform.serializer().descriptor.getElementName(ordinal)

/** Platform of the running target, or `null` when it is one the API has no value for. */
internal expect fun currentClientPlatform(): VitrinaClientPlatform?
