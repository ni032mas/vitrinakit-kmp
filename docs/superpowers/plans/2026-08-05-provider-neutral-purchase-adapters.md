# VitrinaKit Provider-Neutral Purchase Adapters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a provider-neutral VitrinaKit core plus isolated Google Play, hosted checkout, and RuStore purchase adapters so application feature code uses one identity-bound purchase/restore API.

**Architecture:** `vitrinakit-core` owns activation, subscriber identity, attempt orchestration, confirmation, profile caching, single-flight, and public results. Provider modules own presentation and local recovery only. An application artifact supplies exactly one adapter at its composition root; provider proofs stay inside the adapter-to-core boundary and authoritative entitlement state comes from Vitrina services.

**Tech Stack:** Kotlin Multiplatform 2.3.20, Kotlin Coroutines/Serialization, Ktor 3.3.3, Google Play Billing Library, RuStore Pay SDK, Android/JVM/iOS targets, kotlin.test, Dokka, Maven publishing, XCFramework.

## Global Constraints

- Umbrella issue: #14; implement #16, #15, #18, #17, #19, and #20 in that dependency order.
- This public repository must contain no private repository paths, private issue links, credentials, customer data, raw store proofs, or unreleased service implementation notes.
- Feature code sees `VitrinaKit.purchase(product)` and `restorePurchases()` only; provider types are restricted to artifact wiring.
- The SDK never grants entitlement from a store callback or redirect. `Success` requires an authoritative profile returned after server confirmation.
- Public results contain only opaque support/attempt/purchase references, never purchase tokens or raw receipts.
- Identity change/logout clears subscriber-scoped cache and resumable state before the next user can observe it.
- Zero or more than one registered purchase adapter is an activation error.
- Every public type has KDoc; stable wire values are typed serializable enums; no stringly typed provider/status branching.
- Each task starts with a failing test and ends with a focused commit. Version bump/publishing happens only in the release task and requires separate release approval.

---

## Target Module Layout

```text
vitrinakit-core/
  src/commonMain/kotlin/ru/vitrina/sdk/
  src/commonTest/kotlin/ru/vitrina/sdk/
vitrinakit-googleplay/
  src/androidMain/kotlin/ru/vitrina/sdk/googleplay/
  src/androidUnitTest/kotlin/ru/vitrina/sdk/googleplay/
vitrinakit-hosted/
  src/commonMain/kotlin/ru/vitrina/sdk/hosted/
  src/commonTest/kotlin/ru/vitrina/sdk/hosted/
vitrinakit-rustore/
  src/androidMain/kotlin/ru/vitrina/sdk/rustore/
  src/androidUnitTest/kotlin/ru/vitrina/sdk/rustore/
```

The repository root remains the build/release aggregator. `vitrinakit-core`
produces Maven metadata and the standalone `VitrinaKit` XCFramework.
`vitrinakit-hosted` publishes JVM, Android, and Apple targets plus a
`VitrinaKitHosted` XCFramework that exports core for one-framework iOS
integration. Google Play and RuStore publish Android artifacts and depend on
core without re-exporting store SDK types.

## Task 1: Split Release Artifacts Without Breaking Core (#16)

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `gradle/publishing-conventions.gradle.kts`
- Create: `vitrinakit-core/build.gradle.kts`
- Create: `vitrinakit-googleplay/build.gradle.kts`
- Create: `vitrinakit-hosted/build.gradle.kts`
- Create: `vitrinakit-rustore/build.gradle.kts`
- Move: `src/commonMain` to `vitrinakit-core/src/commonMain`
- Move: `src/jvmMain` to `vitrinakit-core/src/jvmMain`
- Move: `src/iosMain` to `vitrinakit-core/src/iosMain`
- Move: `src/commonTest` to `vitrinakit-core/src/commonTest`
- Modify: `Makefile`

- [ ] **Step 1: Add a failing artifact topology check.** Create root task `verifyModuleTopology` that expects projects `:vitrinakit-core`, `:vitrinakit-googleplay`, `:vitrinakit-hosted`, and `:vitrinakit-rustore`, unique artifact IDs, and no provider dependency in core.
- [ ] **Step 2: Run `./gradlew verifyModuleTopology`.** Expect failure because the subprojects do not exist.
- [ ] **Step 3: Move existing source history into core and include subprojects.** Preserve public package names so existing imports remain source-compatible.
- [ ] **Step 4: Extract publication conventions.** Keep production/development artifact suffix behavior, generated environment config, Dokka rules, local build repository, GitHub Packages credentials lookup, and publication metadata consistent across modules.
- [ ] **Step 5: Configure targets.** Core: JVM, iOS arm64/simulator arm64,
  standalone XCFramework. Hosted: JVM, Android, iOS arm64/simulator arm64, plus
  an XCFramework that exports core. Google/RuStore: Android library targets
  with unit tests. Provider dependencies use `implementation`, except hosted's
  public core API/export required by its single-framework Apple distribution.
- [ ] **Step 6: Recreate root lifecycle tasks.** `test`, `verifySdk`, `verifyReleaseArtifacts`, and `assembleVitrinaKitXCFramework` aggregate the correct subproject tasks. `verifyReleaseArtifacts` must inspect the local Maven repository for all expected artifacts and POM dependency isolation.
- [ ] **Step 7: Run `./gradlew verifyModuleTopology :vitrinakit-core:jvmTest assembleVitrinaKitXCFramework`.** Expect PASS with unchanged core behavior.
- [ ] **Step 8: Commit.** `git commit -m "build: split core and provider artifacts (#16)"`

## Task 2: Identity-Bound Core And Provider-Neutral Facade (#15)

**Files:**
- Create: `vitrinakit-core/src/commonMain/kotlin/ru/vitrina/sdk/identity/VitrinaKitIdentity.kt`
- Create: `vitrinakit-core/src/commonMain/kotlin/ru/vitrina/sdk/purchase/VitrinaKitPurchaseAdapter.kt`
- Create: `vitrinakit-core/src/commonMain/kotlin/ru/vitrina/sdk/purchase/VitrinaKitPurchaseModels.kt`
- Create: `vitrinakit-core/src/commonMain/kotlin/ru/vitrina/sdk/purchase/PurchaseCoordinator.kt`
- Create: `vitrinakit-core/src/commonMain/kotlin/ru/vitrina/sdk/purchase/PurchaseCoordinatorTest.kt`
- Create: `vitrinakit-core/src/commonMain/kotlin/ru/vitrina/sdk/cache/SubscriberCache.kt`
- Create: `vitrinakit-core/src/commonTest/kotlin/ru/vitrina/sdk/cache/SubscriberCacheTest.kt`
- Modify: `vitrinakit-core/src/commonMain/kotlin/ru/vitrina/sdk/VitrinaKit.kt`
- Modify: `vitrinakit-core/src/commonMain/kotlin/ru/vitrina/sdk/VitrinaClient.kt`
- Modify: `vitrinakit-core/src/commonMain/kotlin/ru/vitrina/sdk/model/Models.kt`
- Modify: `vitrinakit-core/src/commonTest/kotlin/ru/vitrina/sdk/VitrinaClientTest.kt`

- [ ] **Step 1: Write failing public contract tests.** Prove activate, identify with trusted token/client-supplied identity, identity replacement, logout, identity-required calls, paywall/profile without repeated user ID, purchase success/pending/cancel/failure, restore outcomes, unknown error-code compatibility, and token/proof redaction.
- [ ] **Step 2: Write failing concurrency/recovery tests.** Two purchase calls cannot present twice; a compatible retry reuses the attempt; adapter mismatch fails before presentation; cancellation sends no fake proof; pending stores only opaque resume data; every path releases the guard.
- [ ] **Step 3: Run `./gradlew :vitrinakit-core:jvmTest`.** Expect unresolved public types/methods.
- [ ] **Step 4: Add public identity and result types.** Required surface:

```kotlin
sealed interface VitrinaKitIdentity {
    data class TrustedToken(val value: String) : VitrinaKitIdentity
    data class ExternalUserId(val value: String) : VitrinaKitIdentity
}

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
    data class Failure(val error: VitrinaKitPurchaseError) : VitrinaKitPurchaseResult
}
```

Do not include provider name or proof in `Success`; diagnostics use safe typed error/support metadata.
- [ ] **Step 5: Add the narrow adapter boundary.** `VitrinaKitPurchaseAdapter` exposes capability, present, restore query, resume, and close. Instruction/proof envelopes are internal implementation types with redacted `toString`; integrating feature code does not implement them.
- [ ] **Step 6: Implement facade lifecycle.** `activate(config)`, `identify(identity)`, `logout()`, `getPaywall(placementId)`, `purchase(product)`, `restorePurchases()`, and `getProfile(forceRefresh)` all use one identity-bound client/coordinator. Remove arbitrary `userId`, `receiptEmail`, and `returnUrl` from the normal purchase call.
- [ ] **Step 7: Implement safe migration.** Mark old hosted-shaped methods deprecated with replacement guidance for one release line; delegate them through the hosted adapter only when configured and never preserve per-call arbitrary identity.
- [ ] **Step 8: Add cache isolation.** Key by environment/app/subscriber session; atomically replace on success; clear on identity change/logout; never elevate cached pending to active; cancel resume jobs and close prior adapter resources.
- [ ] **Step 9: Run `./gradlew :vitrinakit-core:jvmTest :vitrinakit-core:dokkaGenerate`.** Expect PASS.
- [ ] **Step 10: Commit.** `git commit -m "feat(core): add identity-bound purchase orchestration (#15)"`

## Task 3: Hosted Checkout Adapter (#18)

**Files:**
- Create: `vitrinakit-hosted/src/commonMain/kotlin/ru/vitrina/sdk/hosted/HostedCheckoutAdapter.kt`
- Create: `vitrinakit-hosted/src/commonMain/kotlin/ru/vitrina/sdk/hosted/HostedCheckoutLauncher.kt`
- Create: `vitrinakit-hosted/src/commonMain/kotlin/ru/vitrina/sdk/hosted/HostedCheckoutConfiguration.kt`
- Create: `vitrinakit-hosted/src/commonTest/kotlin/ru/vitrina/sdk/hosted/HostedCheckoutAdapterTest.kt`

- [ ] **Step 1: Write failing adapter tests.** Cover server-provided HTTPS URL only, allowlisted return scheme, receipt-data callback, browser open failure, detectable dismissal, deep-link return, bounded polling, timeout to pending, resume after restart, hosted restore as profile refresh, and secret/proof redaction.
- [ ] **Step 2: Run `./gradlew :vitrinakit-hosted:allTests`.** Expect missing adapter failures.
- [ ] **Step 3: Implement composition callbacks.** `HostedCheckoutLauncher` opens the URI and reports lifecycle signals; provider-required receipt identity is supplied by `HostedCheckoutConfiguration`, not by `VitrinaKit.purchase`.
- [ ] **Step 4: Treat navigation as a signal.** On return/dismissal, query attempt/profile until authoritative success, terminal failure, or bounded pending. Never convert a return URL into payment proof.
- [ ] **Step 5: Persist only opaque resume state.** Attempt reference and expiry may be stored through a host-provided secure state boundary; confirmation URL, email, and provider payload are not logged or exposed in public results.
- [ ] **Step 6: Run `./gradlew :vitrinakit-hosted:allTests :vitrinakit-hosted:dokkaGenerate`.** Expect PASS.
- [ ] **Step 7: Commit.** `git commit -m "feat(hosted): add provider-neutral checkout adapter (#18)"`

## Task 4: Google Play Billing Adapter (#17)

**Files:**
- Create: `vitrinakit-googleplay/src/androidMain/kotlin/ru/vitrina/sdk/googleplay/GooglePlayPurchaseAdapter.kt`
- Create: `vitrinakit-googleplay/src/androidMain/kotlin/ru/vitrina/sdk/googleplay/BillingClientConnection.kt`
- Create: `vitrinakit-googleplay/src/androidMain/kotlin/ru/vitrina/sdk/googleplay/GooglePlayActivityProvider.kt`
- Create: `vitrinakit-googleplay/src/androidMain/kotlin/ru/vitrina/sdk/googleplay/GooglePlayProof.kt`
- Create: `vitrinakit-googleplay/src/androidUnitTest/kotlin/ru/vitrina/sdk/googleplay/GooglePlayPurchaseAdapterTest.kt`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Write failing BillingClient tests behind a small wrapper.** Cover connection/reconnection, ProductDetails refresh, base-plan/offer selection from server instruction, obfuscated account/profile IDs, launch response, purchased/pending/cancelled/error callbacks, duplicate callbacks, callback after coroutine cancellation, and disconnect.
- [ ] **Step 2: Write recovery/restore tests.** On activation/foreground/connection recovery, `queryPurchasesAsync` finds missed or pending purchases, submits each proof once, handles ownership conflict safely, and does not transfer access when the current Play account differs.
- [ ] **Step 3: Run `./gradlew :vitrinakit-googleplay:test`.** Expect missing adapter failures.
- [ ] **Step 4: Implement foreground-safe BillingClient lifecycle.** Require an activity provider only for presentation, serialize UI launch, reconnect with bounded backoff, unregister listeners/close resources, and never retain Activity beyond the call.
- [ ] **Step 5: Resolve products immediately before purchase.** Match provider identifiers/base plan/offer from the server instruction against fresh ProductDetails. Reject mismatch or unavailable offer as typed retryable/non-retryable errors; never trust UI price as purchase truth.
- [ ] **Step 6: Return internal proof only to core.** Include the minimum package/product/token fields needed for server confirmation, redact `toString`, and zero references after submission where practical. Do not acknowledge on device; server owns validation and acknowledgement.
- [ ] **Step 7: Map pending and cancellation exactly.** Pending never grants access; `USER_CANCELED` becomes `Cancelled`; service disconnection is retryable; developer/config errors are non-retryable and carry a safe support reference.
- [ ] **Step 8: Run `./gradlew :vitrinakit-googleplay:test :vitrinakit-googleplay:dokkaGenerate`.** Expect PASS.
- [ ] **Step 9: Commit.** `git commit -m "feat(googleplay): add BillingClient purchase adapter (#17)"`

## Task 5: RuStore Adapter And Capability Boundary (#19)

**Files:**
- Create: `vitrinakit-rustore/src/androidMain/kotlin/ru/vitrina/sdk/rustore/RuStorePurchaseAdapter.kt`
- Create: `vitrinakit-rustore/src/androidMain/kotlin/ru/vitrina/sdk/rustore/RuStoreActivityProvider.kt`
- Create: `vitrinakit-rustore/src/androidMain/kotlin/ru/vitrina/sdk/rustore/RuStoreProof.kt`
- Create: `vitrinakit-rustore/src/androidUnitTest/kotlin/ru/vitrina/sdk/rustore/RuStorePurchaseAdapterTest.kt`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Write the same conformance cases as Google.** Product resolution, presentation, success/pending/cancel/failure, missed callback recovery, restore, duplicate proof suppression, ownership conflict, activity/resource cleanup, and redaction must pass through a shared test contract where platform APIs permit.
- [ ] **Step 2: Run `./gradlew :vitrinakit-rustore:test`.** Expect missing adapter failures.
- [ ] **Step 3: Implement Pay SDK presentation behind the core contract.** Keep all RuStore identifiers and SDK result types inside the module; translate only to internal proof/pending/cancel/failure outcomes.
- [ ] **Step 4: Implement recovery/restore without ownership assumptions.** Query purchases visible to the current RuStore session, submit proof to core, and let the service decide immutable subscriber ownership.
- [ ] **Step 5: Keep refund capabilities out of mobile.** Refund/cancel/revoke are operator/server concerns and must not appear on the purchase adapter public API.
- [ ] **Step 6: Run `./gradlew :vitrinakit-rustore:test :vitrinakit-rustore:dokkaGenerate`.** Expect PASS.
- [ ] **Step 7: Commit.** `git commit -m "feat(rustore): add purchase and restore adapter (#19)"`

## Task 6: Public Quickstarts, Migration Guide, And Release Candidate (#20)

**Files:**
- Modify: `README.md`
- Create: `docs/identity.md`
- Create: `docs/purchases.md`
- Create: `docs/migration-provider-neutral-purchases.md`
- Create: `samples/android-flavors/settings.gradle.kts`
- Create: `samples/android-flavors/build.gradle.kts`
- Create: `samples/android-flavors/app/build.gradle.kts`
- Create: `samples/android-flavors/app/src/main/kotlin/ru/vitrina/sample/PurchaseFeature.kt`
- Create: `samples/android-flavors/app/src/global/kotlin/ru/vitrina/sample/PurchaseAdapterFactory.kt`
- Create: `samples/android-flavors/app/src/ru/kotlin/ru/vitrina/sample/PurchaseAdapterFactory.kt`
- Modify: `build.gradle.kts`
- Modify: `Makefile`

- [ ] **Step 1: Add executable sample checks.** Root verification compiles both sample variants, inspects dependency graphs, and fails if global contains hosted/RuStore modules, ru contains Google/RuStore, or any artifact registers zero/multiple adapters.
- [ ] **Step 2: Document the complete developer flow.** Install core plus one adapter, activate, fetch a trusted subscriber token from the customer's backend, identify, load paywall, purchase, handle every result, restore, foreground recovery, logout, and safe diagnostics.
- [ ] **Step 3: Document build-variant wiring.** Provider-specific factory symbols live only in flavor composition roots while feature code imports core types only. State explicitly that runtime geography/locale/IP does not switch billing.
- [ ] **Step 4: Document account semantics.** Store restore never transfers a purchase to a different identified application account; hosted restore uses the identified profile; ownership errors are intentionally privacy-safe.
- [ ] **Step 5: Document migration.** Replace hosted-shaped `makePurchase(userId, receiptEmail, returnUrl)` with identify-once plus `purchase(product)`; list deprecation timeline and behavior changes for pending/cancel/restore.
- [ ] **Step 6: Verify public-boundary cleanliness.** Run `rg -n '/Users/|vitrina#|LitoFit|BEGIN (RSA|OPENSSH)|purchaseToken|service.account' README.md docs samples`; investigate every match and remove private/internal/secret content.
- [ ] **Step 7: Set the approved RC version and release notes only after service contract compatibility is proven.** Do not publish in this commit unless separately authorized.
- [ ] **Step 8: Run `./gradlew verifySdk verifyReleaseArtifacts assembleVitrinaKitXCFramework` and `make verify`.** Expect PASS.
- [ ] **Step 9: Inspect the local Maven repository.** Confirm core, hosted, Google, RuStore, sources, metadata, development suffixes, and no provider dependency leakage into core POM.
- [ ] **Step 10: Commit.** `git commit -m "docs: publish multi-provider integration guide (#20)"`

## Test Requirements

| Surface | Required tests |
|---|---|
| Core lifecycle | activation cardinality, identify/replace/logout, cache isolation, missing/expired identity, redaction |
| Purchase coordinator | idempotency, single-flight, adapter mismatch, success only after confirmation, pending/cancel/failure, cleanup |
| Restore | native proof query, hosted profile refresh, empty result, ownership failure, duplicate proof |
| Hosted adapter | URL/return validation, launcher lifecycle, bounded polling, resume, no redirect-as-success |
| Google adapter | BillingClient connection, ProductDetails/offer, every response code, pending, query recovery, cleanup |
| RuStore adapter | Pay SDK conformance, pending/cancel, recovery/restore, cleanup |
| Publications | module/artifact/POM topology, production/development suffixes, Dokka, XCFramework |
| Sample variants | exact one adapter and forbidden dependency/class absence per artifact |

## Final Verification

- [ ] `./gradlew :vitrinakit-core:jvmTest`
- [ ] `./gradlew :vitrinakit-hosted:allTests`
- [ ] `./gradlew :vitrinakit-googleplay:test :vitrinakit-rustore:test`
- [ ] `./gradlew verifySdk verifyReleaseArtifacts assembleVitrinaKitXCFramework`
- [ ] `make verify`
- [ ] Public KDoc contains no undocumented public symbols and no provider proof type leaks.
- [ ] API binary/source compatibility report explicitly lists only the approved breaking migration.
- [ ] `rg -n 'TO''DO|TB''D|FIX''ME|place''holder' vitrinakit-* README.md docs samples` returns no newly introduced unfinished markers.
- [ ] Issues #14-#20 and project fields match evidence; publishing still requires explicit approval.
