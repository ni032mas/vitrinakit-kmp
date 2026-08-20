# VitrinaKit KMP SDK Changelog

## Unreleased

## 0.1.0-rc.11

Paywall response shape release candidate. Every paywall load failed to parse
in production despite a successful HTTP call.

### Fixed

- `Paywall` no longer declares `paywall_id`, `config`, or `fallback_config`.
  The API sends only `placement_key` and `products`; the three removed
  properties were non-nullable with no defaults, so `kotlinx.serialization`
  threw on every real response and `getPaywall()` returned
  `VitrinaKitResult.Failure` after a `200 OK`. The model now matches the
  documented integration contract, which states the response carries no
  paywall configuration, experiment, variant, or fallback fields.

## 0.1.0-rc.10

Android target release candidate. An Android integrator on an earlier RC gets a
completely inert SDK; upgrading is the only fix.

### Fixed

- The SDK now publishes an Android target, `vitrinakit-kmp-sdk-android`.
  Previously `vitrinakit-core` declared only `jvm()` and iOS targets, so an
  Android consumer resolved `vitrinakit-kmp-sdk-jvm` and ran the JVM build on a
  phone. Its default installation-id storage persists through
  `java.util.prefs`, which has no working backing store on Android: `activate()`
  failed, every later call short-circuited to `notActivated()` without a network
  request, and the integration was inert while looking configured. The Android
  default storage is backed by `SharedPreferences` and is discovered through an
  `androidx.startup` initializer, so an integrator supplies nothing.
- `VitrinaKitConfig.Builder` no longer constructs the default storage eagerly.
  A caller that supplied its own storage still triggered the JVM default, which
  logged `java.util.prefs` warnings on every Android launch even when unused.
- Published Android artifacts now ship consumer ProGuard rules; they previously
  contained none, leaving every integrator with `isMinifyEnabled = true` to
  discover the keep rules themselves.
- Checkout refused for a missing verified email now surfaces
  `VitrinaCheckoutErrorCode.EMAIL_VERIFICATION_REQUIRED` instead of a generic
  server failure. `HostedCheckoutAdapter.purchase()` no longer collapses every
  checkout condition into `INVALID_REQUEST` with a placeholder message —
  `RECEIPT_EMAIL_REQUIRED`, `INVALID_RECEIPT_EMAIL`,
  `ACTIVE_SUBSCRIPTION_EXISTS`, and `EMAIL_VERIFICATION_REQUIRED` each reach
  `purchase()` as their own `VitrinaKitPurchaseErrorCode` with the server's
  explanatory message attached.

## 0.1.0-rc.9

JVM target pinning release candidate.

### Fixed

- Every published JVM and Android artifact now declares an explicit JVM
  target (17) instead of inheriting whatever JDK happened to build it. Prior
  RCs silently shipped Java 21 bytecode because CI built on JDK 21, which
  broke consumers on JDK 17 with `UnsupportedClassVersionError`. JDK 17 is now
  a stated, verified requirement instead of an accident of the release
  machine's JDK version.

### Added

- `make verify` now reads the compiled class files of every published JVM and
  Android artifact and fails if their bytecode level does not match the
  declared JVM target, so this cannot regress by changing the CI JDK again.

## 0.1.0-rc.8

Subscriber identity tiers and recovery release candidate.

### Added

- Persistent installation identities on JVM and iOS, with automatic
  non-blocking store restoration and explicit access-resolution state.
- Subscriber association through `identify(userId)`, opaque backend sessions
  via `setSubscriberSession(session)`, and privacy-safe email verification
  recovery through `requestEmailVerification` and `confirmEmailVerification`.

### Changed

- Publishable-key, subscriber-ID, and bearer-session requests now follow three
  explicit authorization tiers while always sending the installation ID.
- `logout()` rotates the installation ID so a shared device cannot reconnect a
  later user to the previous subscriber.

### Removed

- The signed client subscriber-token identity path. Update integrations to call
  `identify(userId)` for application-user association, or bind an opaque
  backend-minted session with `setSubscriberSession(session)`; do not supply a
  signed subscriber token to the SDK.
- The separate application ID from client configuration. Activation now uses
  the publishable key and the SDK-managed installation ID.

## 0.1.0-rc.7

Provider-neutral purchase adapter release candidate.

### Added

- Identity-bound core purchase and restore APIs with server-authoritative
  success, pending, cancellation, and privacy-safe failures.
- Isolated hosted checkout, Google Play Billing 9.1.0, and RuStore Pay 11.0.0
  adapter artifacts.
- Hosted checkout iOS device and simulator publications, plus a
  `VitrinaKitHosted.xcframework` that exports the core API and hosted adapter.
- Foreground query recovery, explicit restore catalog mappings, and
  subscriber-scoped lifecycle cleanup.
- Executable Android flavor sample for Google Play and hosted compositions.
- Public identity, purchase, migration, provider setup, and sandbox guides.

### Changed

- Feature code now calls `identify`, `purchase(product)`, and
  `restorePurchases()` without provider-specific purchase parameters.
- Hosted-shaped `makePurchase` overloads are deprecated for the 0.1 RC
  migration line.
- Release verification now enforces complete publication topology, provider
  dependency isolation, Apple target dependencies, sample adapter cardinality,
  and flavor graph purity.

### Release Channel

- Core production coordinate:
  `ru.vitrina:vitrinakit-kmp-sdk:0.1.0-rc.7`.
- Provider production coordinates:
  `ru.vitrina:vitrinakit-hosted:0.1.0-rc.7`,
  `ru.vitrina:vitrinakit-googleplay:0.1.0-rc.7`, and
  `ru.vitrina:vitrinakit-rustore:0.1.0-rc.7`.
- Development artifacts append `-dev` to every artifact ID.
- Publishing is not part of this source RC commit.

## 0.1.0-rc.6

Checkout receipt and open-session reuse release candidate.

### Added

- Checkout requests now require `receiptEmail`, serialized as
  `receipt_email`, for receipt delivery.
- Checkout responses expose `reused` so consumer apps can identify an existing
  open checkout session.
- Public `VitrinaCheckoutErrorCode` checkout error codes for consumer handling:
  `receipt_email_required`, `invalid_receipt_email`, and
  `checkout_active_subscription_exists`.
- Typed checkout failures through `VitrinaError.Checkout` and
  `VitrinaKitError.Checkout`.

### Release Channel

- Production coordinate:
  `ru.vitrina:vitrinakit-kmp-sdk:0.1.0-rc.6`.
- Development coordinate:
  `ru.vitrina:vitrinakit-kmp-sdk-dev:0.1.0-rc.6`.

## 0.1.0-rc.4

Development endpoint correction release candidate.

### Fixed

- Development SDK artifacts now compile the API base URL as
  `https://api.dev.vitrinakit.ru`.

### Release Channel

- Production coordinate:
  `ru.vitrina:vitrinakit-kmp-sdk:0.1.0-rc.4`.
- Development coordinate:
  `ru.vitrina:vitrinakit-kmp-sdk-dev:0.1.0-rc.4`.

## 0.1.0-rc.3

Release candidate for the Adapty-style VitrinaKit mobile SDK facade.

### Added

- Static `VitrinaKit` facade with activation, paywall fetch, product listing,
  hosted purchase session creation, profile refresh, and blocking JVM helpers.
- SDK-owned Ktor transport for JVM and iOS targets.
- Development publication channel:
  `ru.vitrina:vitrinakit-kmp-sdk-dev:0.1.0-rc.3`.

### Changed

- API endpoint and environment selection are compiled into the published SDK
  artifact instead of being configured by mobile application code.
- Manual publish workflow now verifies and publishes both production and
  development SDK artifacts for the requested version.

### Release Channel

- Production coordinate:
  `ru.vitrina:vitrinakit-kmp-sdk:0.1.0-rc.3`.
- Development coordinate:
  `ru.vitrina:vitrinakit-kmp-sdk-dev:0.1.0-rc.3`.

## 0.1.0-rc.2

Compatibility release candidate for LitoFit mobile integration.

### Changed

- Build SDK artifacts with Kotlin `2.3.20` so LitoFit's current Kotlin/Native
  compiler can consume iOS KLIB artifacts without ABI incompatibility.
- Align coroutine dependencies with the LitoFit mobile stack.

### Release Channel

- Installation coordinate:
  `ru.vitrina:vitrinakit-kmp-sdk:0.1.0-rc.2`.

## 0.1.0-rc.1

First LitoFit release candidate.

### Added

- Placement paywall fetch API for SDK-safe paywall rendering.
- Hosted checkout session API for VitrinaKit-managed YooKassa payment flows.
- Subscriber access refresh API with `external_user_id`, `has_access`, and
  entitlement states.
- Entitlement state fields for `premium_access`, lifecycle status, expiration,
  plan key, product key, provider source, renewal flag, and inactive reason.
- Dokka API documentation generation.
- Local Maven/KMP release dry-run via `./gradlew verifyReleaseArtifacts`.

### Release Channel

- First private channel: GitHub Packages repository
  `https://maven.pkg.github.com/ni032mas/vitrinakit-kmp`.
- Installation coordinate:
  `ru.vitrina:vitrinakit-kmp-sdk:0.1.0-rc.1`.

### Verification

- Run `./gradlew verifyReleaseArtifacts` before publishing SDK artifacts.
- Publication to GitHub Packages runs `verifyReleaseArtifacts` before pushing
  artifacts.
