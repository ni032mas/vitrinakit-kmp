# VitrinaKit KMP SDK

Kotlin Multiplatform SDK for identity-bound mobile subscriptions with hosted
checkout, Google Play Billing, and RuStore Pay adapters.

## Release candidate

Current release candidate: `0.1.0-rc.7`. The source and local release artifacts
are ready for validation; publishing remains a separate, explicitly approved
release operation.

## Requirements

Published artifacts target JVM bytecode level 17 (class file major version 61).
Build and run consuming applications on JDK 17 or newer. The target is pinned
explicitly in the build (`kotlin { jvmToolchain(17) }` per module) and checked
against the compiled class files of every published JVM and Android artifact
in `make verify`, so it cannot silently change with the JDK that runs a
release.

## Install

Add the VitrinaKit GitHub Packages repository. Keep package credentials outside
source control and mobile binaries.

```kotlin
repositories {
    maven("https://maven.pkg.github.com/ni032mas/vitrinakit-kmp") {
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
            password = providers.gradleProperty("gpr.key").orNull
        }
    }
}
```

Every application artifact installs the core plus exactly one adapter. For a
single-provider Android application, use one valid dependency block such as:

```kotlin
dependencies {
    implementation("ru.vitrina:vitrinakit-kmp-sdk:0.1.0-rc.7")
    implementation("ru.vitrina:vitrinakit-googleplay:0.1.0-rc.7")
}
```

Use `vitrinakit-rustore` instead of the hosted or Google Play artifact in a
build that is distributed through RuStore. Do not package multiple adapters in
one artifact. Development coordinates append `-dev` to each artifact ID and
use the development VitrinaKit API compiled into the SDK.

For separate Google Play and hosted application variants, declare the flavors
before using their generated dependency configurations:

```kotlin
android {
    flavorDimensions += "billing"
    productFlavors {
        create("global") { dimension = "billing" }
        create("ru") { dimension = "billing" }
    }
}

dependencies {
    implementation("ru.vitrina:vitrinakit-kmp-sdk:0.1.0-rc.7")
    "globalImplementation"("ru.vitrina:vitrinakit-googleplay:0.1.0-rc.7")
    "ruImplementation"("ru.vitrina:vitrinakit-hosted:0.1.0-rc.7")
}
```

The [executable flavor sample](samples/android-flavors) contains the complete
Android module and provider factories.

## Quickstart

Create the provider adapter in the application composition root, then activate
the SDK once. Feature code receives the finished `VitrinaKitConfig`; it does not
import provider packages.

```kotlin
val config = VitrinaKitConfig.Builder("PUBLIC_API_KEY")
    .withPurchaseAdapter(createArtifactPurchaseAdapter())
    .build()

when (VitrinaKit.activate(config)) {
    is VitrinaKitResult.Success -> Unit
    is VitrinaKitResult.Failure -> showConfigurationError()
}
```

Hosted checkout uses `withHostedCheckoutAdapter(...)` instead. Activation
rejects a blank publishable key and any configuration with zero or multiple
adapters.

The SDK starts in installation scope and immediately begins a non-blocking
store restore. Use `profile.accessResolution` when a screen must distinguish
confirmed access from the initial `CHECKING` state.

After the application user signs in, associate the installation with the
application's stable user ID. Because a merge can change paywall assignment,
load the paywall again after `identify` succeeds.

```kotlin
when (VitrinaKit.identify(userId = currentUser.id)) {
    is VitrinaKitResult.Success -> Unit
    is VitrinaKitResult.Failure -> showIdentityError()
}

val paywall = when (val result = VitrinaKit.getPaywall("main")) {
    is VitrinaKitResult.Success -> result.value
    is VitrinaKitResult.Failure -> return showPaywallError()
}

when (val result = VitrinaKit.purchase(paywall.products.first())) {
    is VitrinaKitPurchaseResult.Success -> renderAccess(result.profile)
    is VitrinaKitPurchaseResult.Pending -> showPendingState()
    VitrinaKitPurchaseResult.Cancelled -> keepCurrentScreen()
    is VitrinaKitPurchaseResult.Failure -> showPurchaseError(result.error)
}
```

`Success` always contains a server-confirmed profile. A store callback, hosted
redirect, or cached state never grants access by itself.

Restore and foreground recovery are provider-neutral:

```kotlin
val restore = VitrinaKit.restorePurchases()

// Call from the app's foreground lifecycle callback.
val recoveredPurchase = VitrinaKit.onForeground()

// Call when the application account signs out.
VitrinaKit.logout()
```

If the application backend mints opaque VitrinaKit subscriber sessions, bind
one with `VitrinaKit.setSubscriberSession(session)`. Session-authenticated
requests use that bearer session as their only authorization authority. Email
recovery is available through `requestEmailVerification` and
`confirmEmailVerification`; the request result never reveals whether an
address owns access. Logout clears account state and creates a new installation
ID, so the previous installation cannot restore another person's access.

Only log stable error code, retryability, and opaque support reference. Do not
log identity values, provider evidence, checkout URLs, or provider payloads.

## Guides

- [Identity and account lifecycle](docs/identity.md)
- [Purchase adapters, restore, and provider setup](docs/purchases.md)
- [Migration from hosted `makePurchase`](docs/migration-provider-neutral-purchases.md)
- [RC 0.1.0-rc.7 API compatibility report](docs/api-compatibility-0.1.0-rc.7.md)
- [Executable Android flavor sample](samples/android-flavors)

Runtime country, locale, SIM, IP address, or device language never changes the
billing provider. Provider selection is a build/composition decision.

## Verify

```bash
./gradlew verifySdk verifyReleaseArtifacts assembleVitrinaKitXCFramework
make verify
```

The gates run all SDK tests, compile both sample flavors, generate Dokka with
warnings as failures, inspect local Maven metadata and provider isolation, build
the core and hosted XCFrameworks, and scan for secrets.

Production dry-run artifacts are written below `build/repository`. For example,
the core root publication is
`build/repository/ru/vitrina/vitrinakit-kmp-sdk/0.1.0-rc.7/`. Run with
`-PvitrinaKitPublication=development` to verify the `-dev` artifact family.

## Development workflow

`dev` is the integration branch and `main` is release-only. Work on a feature
branch, run `make verify`, and open a pull request into `dev`.

```bash
make install-git-hooks
```

## Publishing

Publishing is intentionally separate from RC preparation. Use the manual
`Publish KMP SDK` workflow only after release approval. Local publishing needs
package credentials supplied through the environment; never put them in source,
documentation, logs, or mobile code.

## iOS

Core and hosted checkout expose `iosArm64` and `iosSimulatorArm64` frameworks:

```bash
./gradlew assembleVitrinaKitXCFramework
```

The command produces:

- `vitrinakit-core/build/XCFrameworks/release/VitrinaKit.xcframework` for a
  custom adapter or core-only integration;
- `vitrinakit-hosted/build/XCFrameworks/release/VitrinaKitHosted.xcframework`
  for hosted checkout.

The hosted framework exports the core public API. Link it by itself and use
`import VitrinaKitHosted` for hosted checkout; do not also link the core
framework into the same app target. Google Play and RuStore adapters are
Android-only.
