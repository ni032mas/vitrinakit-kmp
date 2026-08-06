# Provider-neutral purchases

Application feature code uses one API regardless of the packaged provider:

```kotlin
val paywall = when (val result = VitrinaKit.getPaywall("main")) {
    is VitrinaKitResult.Success -> result.value
    is VitrinaKitResult.Failure -> return showPaywallFailure(result.error)
}
val purchase = VitrinaKit.purchase(paywall.products.first())
val restore = VitrinaKit.restorePurchases()
```

Install the core plus exactly one provider adapter in each application artifact.
Provider selection belongs to build variants and composition roots. Runtime
country, locale, SIM, IP address, or device language must never switch billing.

## Result handling

Handle every purchase outcome:

```kotlin
when (val result = VitrinaKit.purchase(selectedProduct)) {
    is VitrinaKitPurchaseResult.Success -> renderAccess(result.profile)
    is VitrinaKitPurchaseResult.Pending -> showPending(result.attemptReference)
    VitrinaKitPurchaseResult.Cancelled -> keepCurrentScreen()
    is VitrinaKitPurchaseResult.Failure -> {
        if (result.error.retryable) showRetry() else showFailure()
        reportSafeSupportReference(result.error.supportReference)
    }
}
```

`purchaseReference`, `attemptReference`, and `supportReference` are opaque
VitrinaKit references. They are not provider evidence. `Pending` never grants
access. `Cancelled` sends no fabricated failure. `Success` is returned only
after server confirmation supplies an authoritative profile.

Restore returns `Success`, `NoPurchases`, or `Failure`. It never transfers a
purchase between identified application accounts. After returning to the
foreground, call `VitrinaKit.onForeground()` so a native adapter can reconcile a
missed callback without reopening provider UI.

## Google Play Billing

Use `vitrinakit-googleplay`. RC 0.1.0-rc.7 integrates Google Play Billing
Library 9.1.0 and requires Android API 23 or newer.

Construct the adapter in the Android composition root. Supply a resumed
`Activity` only at launch time and explicitly map store product IDs to public
catalog references for fresh-install restore:

```kotlin
val adapter = GooglePlayPurchaseAdapter(
    context = applicationContext,
    activityProvider = GooglePlayActivityProvider { resumedActivityOrNull() },
    restoreReferenceResolver = GooglePlayRestoreReferenceResolver { productId ->
        restoreCatalog[productId]?.let { item ->
            GooglePlayRestoreResolution.Mapped(
                placementId = item.placementId,
                productReference = item.productReference,
            )
        } ?: GooglePlayRestoreResolution.Skip
    },
)

val config = VitrinaKitConfig.Builder("PUBLIC_API_KEY")
    .withPurchaseAdapter(adapter)
    .build()
```

Configure subscriptions, base plans, offers, package name, and account policy in
Google Play Console and VitrinaKit before sandbox testing. The adapter resolves
fresh product details immediately before presentation. It does not validate,
acknowledge, consume, or grant access on device. See the official
[Google Play Billing integration guide](https://developer.android.com/google/play/billing/integrate).

## Hosted checkout

Use `vitrinakit-hosted`. Supply a browser launcher, receipt-address callback,
allowlisted return scheme, and secure resume-state store in the composition
root:

```kotlin
val adapter = HostedCheckoutAdapter(
    HostedCheckoutConfiguration(
        launcher = hostedLauncher,
        receiptEmail = { currentAccountReceiptEmail() },
        returnUrl = "myapp://subscription/return",
        allowedReturnSchemes = setOf("myapp"),
        resumeStateStore = encryptedResumeStateStore,
    ),
)

val config = VitrinaKitConfig.Builder("PUBLIC_API_KEY")
    .withHostedCheckoutAdapter(adapter)
    .build()
```

Open only the `HostedCheckoutLaunchRequest.confirmationUrl` supplied to the
launcher. A return/deep link is only a signal to refresh authoritative state;
navigation never proves payment. Persist only `HostedCheckoutResumeState` using
platform-protected storage. Do not persist checkout URLs or receipt addresses.

## RuStore Pay

Use `vitrinakit-rustore`. RC 0.1.0-rc.7 integrates RuStore Pay 11.0.0 through
the official BOM 2026.07.01 and requires Android API 23 or newer.

```kotlin
val adapter = RuStorePurchaseAdapter(
    context = applicationContext,
    activityProvider = RuStoreActivityProvider { resumedActivityOrNull() },
    restoreReferenceResolver = RuStoreRestoreReferenceResolver { productId ->
        restoreCatalog[productId]?.let { item ->
            RuStoreRestoreResolution.Mapped(
                placementId = item.placementId,
                productReference = item.productReference,
            )
        } ?: RuStoreRestoreResolution.Skip
    },
)

val config = VitrinaKitConfig.Builder("PUBLIC_API_KEY")
    .withPurchaseAdapter(adapter)
    .build()
```

Configure the official Pay SDK manifest metadata resources
`console_app_id_value` and `sdk_pay_scheme_value`, a matching deep-link intent
filter, and `singleTop` on the receiving activity. Forward both the initial and
every new payment-return intent immediately:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ruStoreAdapter.handlePaymentIntent(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    ruStoreAdapter.handlePaymentIntent(intent)
}
```

The adapter uses one-step purchase only. Confirmation, cancellation, refund,
revocation, validation, and entitlement decisions remain server/operator
responsibilities. Follow the official
[RuStore Pay Kotlin/Java setup guide](https://www.rustore.ru/help/en/sdk/pay/kotlin-java)
for current console and manifest steps.

## Build variants

The executable [Android flavor sample](../samples/android-flavors) demonstrates:

- `global`: core plus Google Play;
- `ru`: core plus hosted checkout;
- shared feature code importing core packages only.

RuStore is intentionally documented as a separate distribution composition and
is absent from both sample flavors. The root `verifyAndroidFlavorSample` task
compiles both variants, resolves their runtime graphs, rejects forbidden
provider modules, and enforces exactly one adapter registration per factory.

## Sandbox acceptance checklist

- The artifact contains core plus one adapter and activation succeeds once.
- Identity is fetched from the application backend and replaced on account
  switch.
- Published product, placement, package, base plan/offer, and return settings
  match the selected distribution artifact.
- Success is visible only after the refreshed profile grants the selected
  entitlement.
- Pending, cancellation, offline retry, foreground recovery, and restore have
  been exercised.
- Restore ownership errors reveal no other account details.
- Logs and crash reports contain no identity values, provider evidence,
  checkout URLs, receipt addresses, or complete request payloads.
