# VitrinaKit Provider-Neutral Purchase Adapters Design

**Status:** Approved on 2026-08-05

## Context

The current public facade provides Adapty-style activation, paywall loading, a
subscriber profile, and `makePurchase`. The current purchase method creates a
hosted checkout session and requires hosted-provider parameters such as receipt
email and return URL. That shape cannot represent Google Play Billing, RuStore,
or another native store without provider branching in every integrating app.

The SDK needs a provider-neutral purchase contract. Provider-specific UI and
proof collection belong in installable adapter modules; purchase validation and
authoritative entitlement state remain on trusted Vitrina services.

## Goals

- One purchase call from application feature code.
- SDK-owned orchestration for native store and hosted checkout adapters.
- Explicit subscriber identity lifecycle.
- Common success, pending, cancellation, and failure results.
- Store-proof restore without exposing raw proof to application code.
- Build-variant examples that package only the intended provider adapter.
- A stable extension boundary for additional providers.
- Public APIs documented with KDoc and typed serializable wire values.

## Non-Goals

- Provider validation or entitlement decisions on the device.
- Runtime country detection or provider selection.
- Shipping provider credentials in the SDK or application.
- Returning raw purchase tokens, receipts, or provider payloads to application
  feature code.
- Automatically transferring purchases between identified application users.
- Changing package version outside an explicit release task.

## Public Integration Shape

An application installs the SDK core and exactly one purchase adapter per
artifact:

```kotlin
implementation("ru.vitrina:vitrinakit-core:<version>")
globalImplementation("ru.vitrina:vitrinakit-googleplay:<version>")
ruImplementation("ru.vitrina:vitrinakit-hosted:<version>")
```

Provider-aware construction stays in the application composition root:

```kotlin
val config = VitrinaKitConfig.Builder("PUBLIC_API_KEY")
    .withPurchaseAdapter(createArtifactPurchaseAdapter())
    .build()

VitrinaKit.activate(config)
```

Feature code is provider-neutral:

```kotlin
VitrinaKit.identify(VitrinaKitIdentity.TrustedToken(subscriberToken))

val paywall = VitrinaKit.getPaywall("main")
val result = VitrinaKit.purchase(paywall.value.products.first())
val restored = VitrinaKit.restorePurchases()
val profile = VitrinaKit.getProfile(forceRefresh = true)

VitrinaKit.logout()
```

Apps configured for client-supplied identities may identify with a stable
external user ID when enabled by their Vitrina app policy. Account-required
apps should use a short-lived trusted subscriber token from their backend.

## Configuration And Identity

`VitrinaKitConfig` contains:

- publishable API key;
- one purchase adapter;
- optional advanced/test HTTP transport;
- optional logging level that redacts proof and tokens;
- cache configuration with safe defaults.

The SDK rejects blank keys and zero/multiple adapters. It must not expose a
public base URL on the normal production quickstart.

Identity state is explicit:

- `identify(identity)` binds future calls and caches to one subscriber;
- changing identity clears prior subscriber-scoped state before completing;
- `logout()` clears subscriber-scoped caches and cancels resumable presentation
  state;
- purchase, restore, and profile requests fail with a typed configuration/auth
  error when identity is required but absent;
- no API accepts an arbitrary `userId` alongside every purchase after the new
  identity-bound facade is enabled.

## Public API

The target facade methods are:

```kotlin
fun activate(config: VitrinaKitConfig): VitrinaKitResult<Unit>

suspend fun identify(identity: VitrinaKitIdentity): VitrinaKitResult<VitrinaKitProfile>
fun logout(): VitrinaKitResult<Unit>

suspend fun getPaywall(placementId: String): VitrinaKitResult<VitrinaKitPaywall>
suspend fun purchase(product: VitrinaKitPaywallProduct): VitrinaKitPurchaseResult
suspend fun restorePurchases(): VitrinaKitRestoreResult
suspend fun getProfile(forceRefresh: Boolean = false): VitrinaKitResult<VitrinaKitProfile>
```

Blocking wrappers are not the main mobile path. If retained for compatibility,
they mirror the same identity-bound provider-neutral semantics and must not be
used from the Android main thread.

### Purchase Result

```kotlin
sealed interface VitrinaKitPurchaseResult {
    data class Success(
        val purchaseReference: String,
        val profile: VitrinaKitProfile,
    ) : VitrinaKitPurchaseResult

    data class Pending(
        val attemptReference: String,
        val profile: VitrinaKitProfile?,
    ) : VitrinaKitPurchaseResult

    data object Cancelled : VitrinaKitPurchaseResult

    data class Failure(
        val error: VitrinaKitPurchaseError,
    ) : VitrinaKitPurchaseResult
}
```

References are opaque Vitrina support references, not provider tokens.

### Restore Result

Restore returns the refreshed profile plus a typed outcome such as restored,
no purchases found, or failure. A purchase owned by another identified
application user is a typed failure and never reveals the owner's identity.

### Errors

Public errors expose stable typed codes, a safe fallback message, retryability,
and an optional opaque support reference. They parse Problem Details responses
and remain forward-compatible with unknown codes.

Important error categories include:

- not activated or not identified;
- purchase already in progress;
- active entitlement already exists;
- no compatible sales channel;
- provider unavailable;
- proof invalid;
- purchase owned by another user;
- network/retryable failure;
- server validation failure.

## Adapter Contract

The core owns attempt creation, confirmation, profile refresh, single-flight,
and idempotency. An adapter owns provider presentation and local recovery.

The public configuration boundary may expose a narrowly scoped
`VitrinaKitPurchaseAdapter` type, but provider proof types and server
instructions remain internal/opt-in implementation API. Integrating feature
code must never need to implement the adapter interface.

An adapter supports:

- a stable provider capability key;
- presenting a purchase instruction;
- returning proof-ready, pending, cancelled, or failed outcome;
- querying restorable purchases;
- resuming an interrupted provider/hosted flow;
- closing/disconnecting platform resources.

An adapter does not:

- choose logical product or entitlement;
- trust prices supplied by application UI;
- validate financial truth;
- grant or revoke access;
- decide whether a purchase can transfer between users.

## Core Purchase Sequence

1. Verify SDK activation and identified subscriber.
2. Acquire a single-flight guard.
3. Create/reuse a purchase attempt with a stable idempotency key.
4. Verify the server instruction matches the installed adapter capability.
5. Ask the adapter to present the provider flow.
6. For cancellation, return `Cancelled` without sending a fake failure.
7. For pending, persist resumable attempt metadata and return `Pending`.
8. For provider proof, submit it through the private transport boundary.
9. Return `Success` only after the server returns authoritative entitlement
   state.
10. Release presentation resources and the single-flight guard on every path.

Provider proof can be held in memory only as long as needed for submission and
must be excluded from `toString`, logs, analytics, exceptions, and public
results.

## Google Play Adapter

The Android Google Play adapter:

- manages BillingClient foreground connection;
- queries fresh ProductDetails and eligible offers;
- launches the billing flow from an activity provider;
- attaches opaque account/profile identifiers supplied by the purchase
  instruction;
- handles `PurchasesUpdatedListener`;
- queries purchases after connection/foreground to recover missed callbacks;
- returns pending without granting access;
- submits purchase proof to core and never logs the purchase token;
- disconnects BillingClient resources when appropriate.

Google recommends sending purchase tokens to a secure backend for verification
and calling `queryPurchasesAsync()` to recover purchases missed while the app
or connection was unavailable. See
[Google Play Billing integration](https://developer.android.com/google/play/billing/integrate.html).

## Hosted Checkout Adapter

The hosted adapter:

- opens only the server-provided confirmation URL;
- obtains provider-required receipt identity through a provider-specific
  composition callback, not the common purchase call;
- records only an opaque attempt reference for resume;
- treats return/deep link as a status-refresh trigger;
- polls the Vitrina attempt/profile until success, terminal failure, or a
  bounded pending result;
- maps user browser dismissal to cancellation when determinable;
- never treats navigation return as proof of payment.

## RuStore Adapter

The RuStore adapter follows the same public/core contract as Google Play:
provider product resolution, Pay SDK presentation, pending/cancellation/proof,
restore query, server confirmation, and resource cleanup. RuStore-specific IDs
remain below the adapter boundary.

## Restore Semantics

- Native store adapters query purchases visible to the current store session
  and submit proof to Vitrina.
- Hosted adapters refresh the identified Vitrina subscriber because no device
  store account owns the payment.
- Server ownership policy decides whether proof is accepted.
- The SDK does not alias, transfer, or share identified users locally.
- The SDK returns a safe typed ownership error when proof belongs to another
  user.
- Existing server-side access for the identified user can remain active even
  when a particular device store session has no matching proof.

## Caching And Recovery

- Paywall/profile cache keys include app environment and identified subscriber.
- Identity change or logout cannot reuse another user's cache.
- A successful purchase response replaces cached profile atomically.
- Pending attempts retain only opaque resumable metadata.
- Foreground recovery checks adapter purchases/attempt state with throttling.
- Network failure may return last known profile with explicit cache metadata;
  it never converts cached pending into success.
- Concurrent purchase calls return one in-progress result or share the same
  attempt; they never present two provider UIs.

## Compatibility And Migration

The hosted `makePurchase(product, userId, receiptEmail, returnUrl)` API is
deprecated after the new contract is available. During a documented migration
window it may delegate to the hosted adapter, but:

- new examples use `identify` plus `purchase`;
- native provider adapters never require receipt email or return URL in feature
  code;
- compatibility aliases remain only where they preserve binary/source safety;
- removal occurs only in a declared major/minor breaking release according to
  the SDK's versioning policy.

`createCheckoutSession` may remain as a low-level transport method for hosted
adapter implementation, but it is not part of the recommended facade.

## Build And Module Layout

The implementation plan must decide the smallest publication-safe module split
that produces at least:

- provider-neutral core/common artifact;
- Android Google Play adapter artifact;
- hosted checkout JVM, Android, and Apple artifacts, with an iOS XCFramework
  that exports core for a single-framework integration;
- RuStore adapter artifact when implemented.

Provider SDK dependencies must not leak transitively into core. Consumer
dependency reports and artifact inspection must prove this boundary. The
standalone core XCFramework remains available for custom adapters; a hosted iOS
app links the hosted XCFramework alone to avoid duplicate core symbols.

## Documentation Requirements

Public documentation must include:

- account/app/provider/catalog prerequisites without private operational
  details;
- activation and identity lifecycle;
- Gradle build-variant dependency examples;
- paywall, purchase, pending, cancellation, restore, and profile examples;
- provider-specific setup links and minimum supported versions;
- error handling and support references;
- privacy/logging rules;
- migration from hosted `makePurchase`;
- sandbox acceptance checklist;
- no examples containing secret keys, raw tokens, internal URLs, or customer
  data.

## Test Requirements

### Core Tests

- activation rejects missing key and zero/multiple adapters;
- purchase/restore/profile require identity according to app/session contract;
- identify change and logout isolate caches;
- one purchase call creates one attempt and invokes one adapter;
- concurrent calls cannot present twice;
- success, pending, cancellation, retryable failure, and terminal failure map
  exactly;
- provider proof never appears in public results, logs, or exception text;
- unknown Problem Details code falls back safely;
- hosted compatibility API delegates only to hosted adapter;
- restore ownership failure is typed and redacted.

### Google Adapter Tests

- product details and offer selection;
- Billing response mapping;
- pending and cancellation;
- listener success and server confirmation;
- connection/foreground query recovery;
- interrupted network followed by retry with the same attempt;
- activity unavailability and lifecycle cleanup;
- token redaction.

### Hosted Adapter Tests

- confirmation URL presentation;
- deep-link return triggers status refresh;
- delayed confirmation returns pending;
- browser cancellation/timeout mapping;
- attempt resume and idempotent polling;
- receipt callback isolated from common API;
- no navigation return grants access.

### RuStore Adapter Tests

- product resolution and presentation;
- success, pending, cancellation, and failure mapping;
- restore query and confirmation;
- lifecycle cleanup and proof redaction.

### Publication Gates

```bash
./gradlew test
./gradlew verifySdk
./gradlew dokkaGenerate
./gradlew verifyReleaseArtifacts
./gradlew assembleVitrinaKitXCFramework
make verify
```

Each provider artifact receives dependency and API-surface verification. Dokka
warnings are failures. Public APIs have concise KDoc, fixed wire values use
`@Serializable` typed enums with `@SerialName`, and no version is bumped outside
the release task.

## Acceptance Criteria

- [ ] Application feature code uses one `purchase(product)` call.
- [ ] Provider selection is explicit at composition/build time.
- [ ] Core has no transitive Google Play, hosted-browser, or RuStore SDK dependency.
- [ ] Subscriber identity and cache lifecycle are explicit and tested.
- [ ] Success is returned only with server-confirmed profile state.
- [ ] Pending and cancellation are first-class results.
- [ ] Restore does not locally transfer purchases between users.
- [ ] Provider proof is never exposed or logged.
- [ ] Hosted `makePurchase` has a documented migration path.
- [ ] Public docs, Dokka, tests, artifact verification, and security gate pass.
