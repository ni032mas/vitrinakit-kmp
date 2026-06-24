# VitrinaKit Adapty-Style SDK Facade Design

## Context

VitrinaKit KMP SDK currently exposes serializable models, a low-level `VitrinaClient`, and a host-provided `VitrinaHttpClient` transport contract. LitoFit therefore implements its own Ktor transport and gateway wrapper around the SDK. That makes the SDK feel like a model package rather than a complete mobile integration library.

Adapty's mobile SDK shape is the target pattern: activate the SDK once with a public key, then call domain methods from a stable SDK facade. The app should not build SDK HTTP requests or own the SDK transport.

## Goals

- Provide a static/singleton facade named `VitrinaKit`.
- Make the common integration path require only a public API key.
- Keep coroutine-first methods for Kotlin callers.
- Add blocking wrappers for JVM/Android call sites that need synchronous calls.
- Rename public SDK-facing models to `VitrinaKit*` names.
- Move the default HTTP transport into the SDK while preserving injected transports for tests and advanced integrations.
- Update LitoFit to consume the facade instead of maintaining `KtorVitrinaHttpClient`.

## Non-Goals

- Do not implement native Google Play Billing, App Store, or RuStore purchase flows in this iteration. `makePurchase` creates the VitrinaKit hosted checkout session and returns the confirmation URL, matching the current VitrinaKit server contract.
- Do not expose `withEnvironment` or `withApiUrl` on the default public happy path. The public key is the source of truth for app and environment. Test-only or advanced transport/config hooks may still exist outside the main quickstart.
- Do not bump the SDK version except in an explicit release task.

## Public API

The intended Kotlin happy path is:

```kotlin
VitrinaKit.activate(
    VitrinaKitConfig.Builder("PUBLIC_API_KEY").build(),
)

val paywall = VitrinaKit.getPaywall(
    placementId = "main",
    userId = externalUserId,
)
val products = VitrinaKit.getPaywallProducts(paywall)
val purchase = VitrinaKit.makePurchase(
    product = products.first(),
    userId = externalUserId,
    returnUrl = "litofit://subscription/return",
)
val profile = VitrinaKit.getProfile(userId = externalUserId)
```

Blocking wrappers mirror the coroutine names with a `Blocking` suffix:

```kotlin
val paywall = VitrinaKit.getPaywallBlocking(
    placementId = "main",
    userId = externalUserId,
)
```

## Model Names

Public SDK models use `VitrinaKit*` names:

- `VitrinaKitConfig`
- `VitrinaKitEnvironment`
- `VitrinaKitPaywall`
- `VitrinaKitPaywallProduct`
- `VitrinaKitPurchase`
- `VitrinaKitProfile`
- `VitrinaKitAccessLevel`
- `VitrinaKitResult`
- `VitrinaKitError`

Compatibility type aliases may remain for the existing `Paywall`, `PaywallProduct`, `CheckoutSession`, `SubscriberState`, `VitrinaResult`, and `VitrinaError` names during the migration window.

## Configuration

`VitrinaKitConfig.Builder(publicApiKey)` is the main entrypoint. The initial config contains:

- `publicApiKey`: required, SDK-safe key used in the `Authorization: PublishableKey ...` header.
- `appId`: optional advanced compatibility value for current `/api/v1/*` headers if the server still requires `X-Vitrina-App-Id`.
- `httpClient`: optional advanced/test transport override.

The SDK default base URL is internal to the transport. Public dev/staging URL overrides are intentionally excluded from the main API.

## Transport

The SDK provides a default Ktor-backed transport. Common code uses Ktor client APIs; platform variants provide or inherit the concrete Ktor engine dependency. Tests and advanced consumers can still construct lower-level clients with `VitrinaHttpClient`.

The old host-provided transport contract remains useful as a boundary:

- SDK facade owns default transport creation.
- `VitrinaClient` remains the low-level request/response client.
- LitoFit stops defining `KtorVitrinaHttpClient`.

## Error Handling

SDK methods return `VitrinaKitResult<T>`, not exceptions, for network/API outcomes. Blocking wrappers return the same result shape. Configuration failures, missing activation, HTTP failures, and decoding failures map to `VitrinaKitError`.

## LitoFit Integration

LitoFit should:

- Initialize `VitrinaKit` from the existing public key config.
- Remove `features/subscription/data/vitrina/KtorVitrinaHttpClient.kt`.
- Replace DI bindings for `VitrinaHttpClient` and direct `VitrinaClient` construction with the SDK facade or an injected facade-compatible gateway.
- Keep `VitrinaSubscriptionGateway` only for LitoFit-specific mapping from SDK models to existing subscription domain models.
- Keep feature flag behavior from issue `ni032mas/LitoFit#804`.

## Verification

SDK verification:

- Add JVM tests for activation, missing activation, injected transport request mapping, model aliases, and blocking wrappers.
- Run `./gradlew verifySdk`.

LitFit verification:

- Update subscription tests that construct `VitrinaClient` or fake `VitrinaHttpClient`.
- Run `./gradlew :features:subscription:allTests`.
- Run `./gradlew detekt` if Kotlin production files changed.
