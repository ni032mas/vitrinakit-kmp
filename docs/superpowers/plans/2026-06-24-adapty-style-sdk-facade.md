# VitrinaKit Adapty-Style SDK Facade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a complete Adapty-style static `VitrinaKit` SDK facade so consumer applications no longer own SDK transport glue.

**Architecture:** Keep the existing low-level `VitrinaClient` request implementation, add public `VitrinaKit*` model names and a singleton facade over the client. Add default SDK-owned transport creation where the current KMP target set supports it, with injected transport preserved for tests and advanced consumers. Consumer applications keep only product-specific domain mapping.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, kotlinx.coroutines, Ktor transport boundary, kotlin.test.

---

## File Structure

- SDK create `src/commonMain/kotlin/ru/vitrina/sdk/VitrinaKit.kt`: public singleton facade, activation state, coroutine and blocking methods.
- SDK create `src/commonMain/kotlin/ru/vitrina/sdk/http/KtorVitrinaHttpClient.kt`: SDK-owned Ktor transport.
- SDK modify `src/commonMain/kotlin/ru/vitrina/sdk/VitrinaClient.kt`: support public API key naming and optional app header behavior without breaking existing low-level constructor.
- SDK modify `src/commonMain/kotlin/ru/vitrina/sdk/model/Models.kt`: add `VitrinaKit*` public names and compatibility aliases.
- SDK modify `build.gradle.kts`: add Ktor client dependencies needed by the default transport.
- SDK modify `src/commonTest/kotlin/ru/vitrina/sdk/VitrinaClientTest.kt`: retain low-level tests and add facade tests.
- SDK modify `README.md`: document Adapty-style activation and methods.
- Downstream consumer migrations remain in their own repositories; this public
  plan documents only the SDK contract they consume.

## Task 1: SDK Facade Tests

**Files:**
- Modify: `src/commonTest/kotlin/ru/vitrina/sdk/VitrinaClientTest.kt`

- [ ] **Step 1: Write failing tests for activation and facade methods**

Add tests that prove:

```kotlin
@Test
fun facadeRequiresActivationBeforeUse() = runTest {
    VitrinaKit.resetForTesting()

    val result = VitrinaKit.getPaywall(
        placementId = "main",
        userId = "user-1",
    )

    val failure = assertIs<VitrinaKitResult.Failure>(result)
    assertIs<VitrinaKitError.Configuration>(failure.error)
}

@Test
fun facadeFetchesPaywallAfterActivation() = runTest {
    val http = FakeHttpClient(
        response = VitrinaHttpResponse(HttpStatusOk, paywallJson),
    )
    VitrinaKit.activate(
        VitrinaKitConfig.Builder("pk_test")
            .withAppId("app_test")
            .withHttpClient(http)
            .build(),
    )

    val result = VitrinaKit.getPaywall(
        placementId = "main",
        userId = "user-1",
    )

    val success = assertIs<VitrinaKitResult.Success<VitrinaKitPaywall>>(result)
    assertEquals("main", success.value.placementKey)
    assertEquals("PublishableKey pk_test", http.singleRequest().headers["Authorization"])
}
```

- [ ] **Step 2: Run tests and verify they fail for missing symbols**

Run: `./gradlew jvmTest --tests ru.vitrina.sdk.VitrinaClientTest`

Expected: FAIL with unresolved references for `VitrinaKit`, `VitrinaKitConfig`, and `VitrinaKitResult`.

## Task 2: SDK Facade Implementation

**Files:**
- Create: `src/commonMain/kotlin/ru/vitrina/sdk/VitrinaKit.kt`
- Create: `src/commonMain/kotlin/ru/vitrina/sdk/http/KtorVitrinaHttpClient.kt`
- Modify: `src/commonMain/kotlin/ru/vitrina/sdk/model/Models.kt`
- Modify: `src/commonMain/kotlin/ru/vitrina/sdk/VitrinaClient.kt`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add public facade and config**

Implement:

```kotlin
object VitrinaKit {
    private var client: VitrinaClient? = null

    fun activate(config: VitrinaKitConfig): VitrinaKitResult<Unit> {
        client = VitrinaClient(
            config = config.toClientConfig(),
            httpClient = config.httpClient ?: KtorVitrinaHttpClient(),
        )
        return VitrinaKitResult.Success(Unit)
    }

    suspend fun getPaywall(placementId: String, userId: String): VitrinaKitResult<VitrinaKitPaywall> =
        activeClient().flatMap { it.fetchPaywall(placementId, UserContext(userId)) }

    fun getPaywallProducts(paywall: VitrinaKitPaywall): VitrinaKitResult<List<VitrinaKitPaywallProduct>> =
        VitrinaKitResult.Success(paywall.products)
}
```

Keep code idiomatic and compile-ready; the snippet is the required shape, not a copy-paste ceiling.

- [ ] **Step 2: Add SDK-owned Ktor transport**

Implement `KtorVitrinaHttpClient` inside the SDK:

```kotlin
class KtorVitrinaHttpClient(
    private val httpClient: HttpClient = HttpClient(),
) : VitrinaHttpClient {
    override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
        val response = httpClient.request(request.url) {
            method = request.method.toKtor()
            headers {
                request.headers.forEach { (name, value) -> append(name, value) }
            }
            request.body?.let { setBody(it) }
        }
        return VitrinaHttpResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
        )
    }
}
```

- [ ] **Step 3: Add model aliases/names**

Expose `VitrinaKit*` names while preserving old names as aliases where Kotlin allows it without breaking serialization.

- [ ] **Step 4: Run SDK tests**

Run: `./gradlew jvmTest --tests ru.vitrina.sdk.VitrinaClientTest`

Expected: PASS.

## Task 3: Purchase/Profile Facade and Blocking Wrappers

**Files:**
- Modify: `src/commonMain/kotlin/ru/vitrina/sdk/VitrinaKit.kt`
- Modify: `src/commonTest/kotlin/ru/vitrina/sdk/VitrinaClientTest.kt`

- [ ] **Step 1: Write failing tests for purchase, profile, and blocking wrappers**

Add tests proving:

```kotlin
@Test
fun facadeCreatesPurchaseFromPaywallProduct() = runTest {
    val http = FakeHttpClient(response = VitrinaHttpResponse(HttpStatusCreated, checkoutJson))
    VitrinaKit.activate(
        VitrinaKitConfig.Builder("pk_test")
            .withAppId("app_test")
            .withHttpClient(http)
            .build(),
    )

    val result = VitrinaKit.makePurchase(
        product = monthlyProduct(),
        userId = "user-1",
        returnUrl = "litofit://subscription/return",
    )

    val success = assertIs<VitrinaKitResult.Success<VitrinaKitPurchase>>(result)
    assertEquals("https://pay.example/confirm", success.value.confirmationUrl)
}
```

- [ ] **Step 2: Run tests and verify failure**

Run: `./gradlew jvmTest --tests ru.vitrina.sdk.VitrinaClientTest`

Expected: FAIL for missing facade methods.

- [ ] **Step 3: Implement methods**

Add:

```kotlin
suspend fun makePurchase(product: VitrinaKitPaywallProduct, userId: String, returnUrl: String): VitrinaKitResult<VitrinaKitPurchase>
suspend fun getProfile(userId: String): VitrinaKitResult<VitrinaKitProfile>
fun getPaywallBlocking(placementId: String, userId: String): VitrinaKitResult<VitrinaKitPaywall>
fun makePurchaseBlocking(product: VitrinaKitPaywallProduct, userId: String, returnUrl: String): VitrinaKitResult<VitrinaKitPurchase>
fun getProfileBlocking(userId: String): VitrinaKitResult<VitrinaKitProfile>
```

- [ ] **Step 4: Run SDK tests**

Run: `./gradlew jvmTest --tests ru.vitrina.sdk.VitrinaClientTest`

Expected: PASS.

## Task 4: SDK Documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document the new quickstart**

Add activation and method examples:

```kotlin
VitrinaKit.activate(
    VitrinaKitConfig.Builder("PUBLIC_API_KEY").build(),
)

val paywall = VitrinaKit.getPaywall("main", userId)
val products = VitrinaKit.getPaywallProducts(paywall.value)
```

- [ ] **Step 2: Run documentation verification**

Run: `./gradlew verifySdk`

Expected: PASS.

## Task 5: Consumer Migration Guidance

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document facade-based consumer wiring**

Show activation, paywall, purchase, and profile calls without an app-owned SDK
transport.

- [ ] **Step 2: Run documentation verification**

Run: `./gradlew verifySdk`

Expected: PASS.

- [ ] **Step 3: Publish consumer migration notes**

State that downstream application migrations and tests remain in their own
repositories.

- [ ] **Step 4: Run SDK verification**

Run: `./gradlew verifySdk`

Expected: PASS.

## Final Verification

- [ ] Run from SDK repo: `./gradlew verifySdk`
- [ ] Confirm public documentation contains no downstream repository paths or
  private issue references.
