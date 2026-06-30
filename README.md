# VitrinaKit KMP SDK

Kotlin Multiplatform SDK for VitrinaKit mobile subscription integrations.

## Status

This package is published from the public `ni032mas/vitrinakit-kmp`
repository to its own GitHub Packages Maven registry.

## Version

Current release candidate: `0.1.0-rc.6`.

SDK versions are changed only as part of a release task. Do not bump `version`
in `build.gradle.kts` for normal feature work.

## Install

LitoFit uses GitHub Packages for the first private release channel.

```kotlin
repositories {
    maven("https://maven.pkg.github.com/ni032mas/vitrinakit-kmp") {
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
            password = providers.gradleProperty("gpr.key").orNull
        }
    }
}

dependencies {
    implementation("ru.vitrina:vitrinakit-kmp-sdk:0.1.0-rc.6")
}
```

Use `vitrinakit-kmp-sdk` for production integrations. Use
`vitrinakit-kmp-sdk-dev` for development integrations that must call the
development VitrinaKit API endpoint. The API URL is compiled into the published
SDK artifact and is not configured by mobile application code.

Use a GitHub token with package read access for `gpr.key`. Do not put tokens in
source files, docs, build logs, or mobile application code.

## Quickstart

Activate the SDK once with the public API key from VitrinaKit. Do not pass
secret API keys or provider credentials to mobile apps.

```kotlin
import ru.vitrina.sdk.VitrinaKit
import ru.vitrina.sdk.VitrinaKitConfig
import ru.vitrina.sdk.model.VitrinaCheckoutErrorCode
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitResult

VitrinaKit.activate(
    VitrinaKitConfig.Builder("PUBLIC_API_KEY").build(),
)

val paywallResult = VitrinaKit.getPaywall(
    placementId = "main",
    userId = externalUserId,
)

when (paywallResult) {
    is VitrinaKitResult.Success -> {
        val paywall = paywallResult.value
        val products = VitrinaKit.getPaywallProducts(paywall)
        // Render products in your paywall UI.
    }
    is VitrinaKitResult.Failure -> {
        // Show a retry or fallback state.
    }
}
```

Start hosted checkout for the selected product:

```kotlin
val purchaseResult = VitrinaKit.makePurchase(
    product = selectedProduct,
    userId = externalUserId,
    receiptEmail = "buyer@example.com",
    returnUrl = "myapp://subscription/return",
)
```

`receiptEmail` is required for checkout receipt delivery. The returned
`confirmationUrl` is the hosted provider checkout URL. If `reused` is `true`,
the SDK received an existing open checkout session instead of creating a new
provider payment.

Consumer apps can distinguish public checkout validation and state errors:

```kotlin
when (val result = purchaseResult) {
    is VitrinaKitResult.Success -> openHostedCheckout(result.value.confirmationUrl)
    is VitrinaKitResult.Failure -> when (val error = result.error) {
        is VitrinaKitError.Checkout -> when (error.code) {
            VitrinaCheckoutErrorCode.RECEIPT_EMAIL_REQUIRED -> showReceiptEmailRequired()
            VitrinaCheckoutErrorCode.INVALID_RECEIPT_EMAIL -> showInvalidReceiptEmail()
            VitrinaCheckoutErrorCode.ACTIVE_SUBSCRIPTION_EXISTS -> refreshProfile()
        }
        else -> showCheckoutError()
    }
}
```

Refresh the subscriber profile after checkout return, app launch, or restore:

```kotlin
val profileResult = VitrinaKit.getProfile(userId = externalUserId)
```

Blocking wrappers are available for JVM/Android call sites that cannot call
suspend functions:

```kotlin
val profileResult = VitrinaKit.getProfileBlocking(userId = externalUserId)
```

Advanced integrations and tests can inject a custom transport:

```kotlin
VitrinaKit.activate(
    VitrinaKitConfig.Builder("PUBLIC_API_KEY")
        .withHttpClient(customTransport)
        .build(),
)
```

## Verify

```bash
./gradlew verifySdk
```

The verification task runs the JVM SDK tests and Dokka documentation generation
with warnings treated as failures.

Repository-level verification is also available:

```bash
make verify
```

This runs the security gate and local release dry run.

## Development Workflow

`dev` is the default integration branch. `main` is stable and release-only.
Create short feature branches from `dev`, open pull requests back into `dev`,
and reserve `main` updates for release PRs.

Install repository-managed git hooks before committing:

```bash
make install-git-hooks
```

The hooks require `gitleaks`. The pre-commit hook scans staged changes for
secrets and blocks direct commits to `dev`/`main`. The commit-msg hook enforces
Conventional Commits, the pre-merge-commit hook protects `main`, and the
pre-push hook blocks protected-branch rewrites and runs a full `make verify`.

## Release Dry Run

```bash
./gradlew verifyReleaseArtifacts
```

Expected output:

- SDK JVM tests pass;
- Dokka documentation is generated;
- Maven/KMP artifacts are written under `build/repository`;
- no GitHub Packages credentials are required.

The production dry-run repository should contain the root multiplatform
publication at
`build/repository/ru/vitrina/vitrinakit-kmp-sdk/0.1.0-rc.6/` and target
publications such as JVM/iOS variants with Kotlin-generated artifact suffixes.
The development dry-run uses `-PvitrinaKitPublication=development` and writes
the root publication to
`build/repository/ru/vitrina/vitrinakit-kmp-sdk-dev/0.1.0-rc.6/`.

## Publish

Real publication uses the `GitHubPackages` Gradle repository. Prefer the
manual `Publish KMP SDK` GitHub Actions workflow and pass the exact SDK
version. The workflow publishes both production and development SDK artifacts:

- `ru.vitrina:vitrinakit-kmp-sdk:<version>` uses
  `https://api.vitrinakit.ru`;
- `ru.vitrina:vitrinakit-kmp-sdk-dev:<version>` uses
  `https://api.dev.vitrinakit.ru`.

The workflow publishes from `ni032mas/vitrinakit-kmp` to that repository's own
GitHub Packages registry using the built-in `GITHUB_TOKEN` and
`permissions: packages: write`. No cross-repository PAT is required.

Local publication is also possible when a package token is available:

```bash
GITHUB_ACTOR=<github-user> GITHUB_TOKEN=<package-token> \
  ./gradlew publishAllPublicationsToGitHubPackagesRepository
```

For local development artifact publication, add
`-PvitrinaKitPublication=development` or set
`VITRINAKIT_PUBLICATION=development`.

Publication tasks depend on `verifySdk`.

## Documentation

```bash
./gradlew dokkaGenerate
```

Generated API documentation is written to `build/dokka/html`.

## iOS

The SDK exposes iOS frameworks for `iosArm64` and `iosSimulatorArm64`.

```bash
./gradlew assembleVitrinaKitXCFramework
```

The release artifact is written to
`build/XCFrameworks/release/VitrinaKit.xcframework`.

Use the Maven/KMP dependency for normal LitoFit integration. Build the
XCFramework only when an iOS consumer needs a direct native framework artifact.
