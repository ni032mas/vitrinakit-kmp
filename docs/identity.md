# Identity and account lifecycle

VitrinaKit binds paywalls, purchases, restore, profiles, caches, and resumable
state to one identified application account. Identity is explicit so one user
cannot observe or resume another user's subscription flow on a shared device.

## Recommended trusted-token flow

1. Authenticate the user with the application backend.
2. Send the existing application session to that backend over the normal secure
   channel.
3. The application backend obtains a short-lived trusted subscriber token and
   returns it to the authenticated app session.
4. Pass the token directly to `VitrinaKit.identify`.
5. Discard the token after the call. Do not log, persist, analyze, or attach it
   to crash reports.

```kotlin
val token = customerBackend.fetchTrustedSubscriberToken()

when (val result = VitrinaKit.identify(VitrinaKitIdentity.TrustedToken(token))) {
    is VitrinaKitResult.Success -> renderProfile(result.value)
    is VitrinaKitResult.Failure -> showSignInRetry()
}
```

The SDK exchanges the short-lived token for an opaque subscriber session and
does not retain the original token. Account-required native purchase and restore
calls require this trusted identity.

`VitrinaKitIdentity.ExternalUserId` is available only for applications whose
VitrinaKit policy explicitly permits client-supplied stable IDs. Do not use it
as a fallback when trusted identity is required.

## Switching users

Call `identify` again after the application account changes. Before the new
identity becomes visible, the SDK closes purchase resources, clears prior
subscriber caches and open attempts, and binds subsequent operations to the new
subscriber session.

Call `logout()` when the application account signs out:

```kotlin
when (VitrinaKit.logout()) {
    is VitrinaKitResult.Success -> showSignedOutUi()
    is VitrinaKitResult.Failure -> showSdkLifecycleError()
}
```

Logout clears subscriber-scoped cache and resumable state and closes adapter
resources. Provider accounts are not linked, aliased, or transferred locally.

## Ownership and privacy

- Native restore queries purchases visible to the current store session, but
  the server decides whether each purchase belongs to the identified account.
- A purchase already bound to a different account returns
  `PURCHASE_OWNED_BY_DIFFERENT_USER` without revealing that account.
- Existing access for the identified account can remain active even if the
  current device store session has no matching purchase.
- Hosted restore refreshes the identified server profile because a browser
  checkout is not owned by a device store account.

## Safe diagnostics

Identity and purchase failures expose stable categories. Log only the category,
`retryable`, and `supportReference` when present:

```kotlin
fun report(error: VitrinaKitPurchaseError) {
    safeLogger.record(
        code = error.code.name,
        retryable = error.retryable,
        supportReference = error.supportReference,
    )
}
```

Never log identity values, subscriber sessions, provider evidence, receipt
addresses, return URLs, or complete SDK request/response objects.
