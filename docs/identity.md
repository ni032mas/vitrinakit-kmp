# Identity and account lifecycle

VitrinaKit always has an installation identity. Activation loads or creates a
persistent installation ID, returns immediately, and starts store restoration
in the background. The first profile can therefore report
`accessResolution == CHECKING`; applications may wait for a resolved profile
when an access-sensitive screen cannot show the temporary signed-out state.

## Application users

Associate the installation with an application's stable user ID after sign-in:

```kotlin
when (val result = VitrinaKit.identify(currentUser.id)) {
    is VitrinaKitResult.Success -> {
        val mergeOccurred = result.value.merged
        val profile = result.value.profile
        reloadPaywall()
    }
    is VitrinaKitResult.Failure -> showSignInRetry()
}
```

Identification can merge subscriber records and change the subscriber ID used
for paywall assignment. Always reload an already displayed paywall after a
successful call.

An application backend may instead mint an opaque subscriber session. Bind it
with `VitrinaKit.setSubscriberSession(session)`. The SDK keeps the session in
memory and uses it as the sole authorization authority for subscriber requests.

## Email recovery

`requestEmailVerification(email)` requests a one-time code and returns the same
public result whether or not the address owns access. Confirm the code with
`confirmEmailVerification(email, code)`; success binds the returned subscriber
session and profile.

## Switching users and logout

Call `identify` or `setSubscriberSession` again when the application account
changes. The SDK clears the previous subscriber cache and pending purchase state
before binding the replacement.

`logout()` clears subscriber state, closes purchase resources, and generates a
new installation ID. The prior installation is never reused, so a later user on
the same device cannot recover the previous user's access through that link.
This deliberately resets installation-scoped paywall experiments and analytics.

Store purchases remain recoverable after a reinstall or installation-ID change;
the server remains authoritative for ownership and access.

## Safe diagnostics

Log only stable error categories, retryability, and opaque support references.
Never log user IDs, subscriber sessions, verification codes, provider evidence,
receipt addresses, return URLs, or complete SDK requests and responses.
