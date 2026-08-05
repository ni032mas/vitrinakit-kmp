package ru.vitrina.sdk.identity

/** Subscriber identity bound to subsequent VitrinaKit operations. */
sealed interface VitrinaKitIdentity {
    /**
     * Short-lived customer token minted by the integrating application's trusted backend.
     *
     * @property value Token exchanged for an opaque subscriber session.
     */
    data class TrustedToken(val value: String) : VitrinaKitIdentity {
        /** Returns a representation that never includes the trusted token. */
        override fun toString(): String = "TrustedToken(value=<redacted>)"
    }

    /**
     * Stable application user identifier for apps configured for client-supplied identity.
     *
     * @property value Stable external user identifier.
     */
    data class ExternalUserId(val value: String) : VitrinaKitIdentity
}
