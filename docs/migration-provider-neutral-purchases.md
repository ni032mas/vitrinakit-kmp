# Migrating to provider-neutral purchases

RC 0.1.0-rc.7 replaces identity-per-call hosted checkout with an explicit
identity lifecycle and one provider-neutral purchase API.

## Before

```kotlin
val checkout = VitrinaKit.makePurchase(
    product = selectedProduct,
    userId = externalUserId,
    receiptEmail = currentEmail,
    returnUrl = "myapp://subscription/return",
)
```

This shape couples feature code to hosted checkout and allows account data to
be repeated on every purchase call.

## After

1. Install core plus exactly one adapter.
2. Move provider construction and provider-only values to the application
   composition root.
3. Call `identify(userId)` once for the active application account, or bind an
   opaque backend session with `setSubscriberSession(session)`.
5. Call `purchase(product)` and `restorePurchases()` from feature code.

```kotlin
VitrinaKit.activate(providerSpecificConfig)
VitrinaKit.identify(currentUser.id)

val paywall = when (val result = VitrinaKit.getPaywall("main")) {
    is VitrinaKitResult.Success -> result.value
    is VitrinaKitResult.Failure -> return showPaywallFailure(result.error)
}
when (val result = VitrinaKit.purchase(paywall.products.first())) {
    is VitrinaKitPurchaseResult.Success -> renderAccess(result.profile)
    is VitrinaKitPurchaseResult.Pending -> showPendingState()
    VitrinaKitPurchaseResult.Cancelled -> keepCurrentScreen()
    is VitrinaKitPurchaseResult.Failure -> showPurchaseFailure(result.error)
}
```

For hosted checkout, move `receiptEmail` and `returnUrl` into
`HostedCheckoutConfiguration`. For native stores, neither value belongs in
feature code.

## Behavior changes

- `userId` is no longer accepted as purchase truth. The currently identified
  subscriber owns all subsequent operations.
- Store/launcher completion does not grant access. Only a server-confirmed
  profile can produce `Success`.
- Pending and cancellation are first-class results rather than checkout-session
  inspection or generic errors.
- Restore is provider-neutral. Native adapters query the current store session;
  hosted restore refreshes the identified profile.
- Restore never transfers ownership between application accounts. Ownership
  failures are typed and privacy-safe.
- `onForeground()` performs query-only native recovery and never presents a new
  provider flow.
- `logout()` clears subscriber caches and resumable state, closes adapter
  resources, and rotates the installation ID before returning to installation scope.

## Deprecation window

The hosted-shaped `makePurchase(product, userId, receiptEmail, returnUrl)` and
blocking counterpart are deprecated in 0.1.0-rc.7. They remain for the 0.1 RC
line as a source-migration bridge and require the hosted adapter. Per-call
identity and hosted values are ignored; adapter configuration and the bound
identity are authoritative. Removal will occur only in a separately announced
breaking release.

New integrations must not call the deprecated overloads. Migrate before adding
Google Play or RuStore because native adapters cannot satisfy hosted-only
parameters.

## Migration verification

- No feature source imports `googleplay`, `hosted`, or `rustore` packages.
- Each application artifact registers exactly one adapter.
- Account switch calls `identify`; account sign-out calls `logout`.
- Purchase UI handles success, pending, cancellation, and failure separately.
- Restore and foreground recovery are wired and do not grant local access.
- Diagnostics retain only safe error categories and opaque support references.
