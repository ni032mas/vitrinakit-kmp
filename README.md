# VitrinaKit KMP SDK

Kotlin Multiplatform SDK for VitrinaKit mobile subscription integrations.

## Status

This package is published from the public `ni032mas/vitrinakit-kmp`
repository to its own GitHub Packages Maven registry.

## Version

Current release candidate: `0.1.0-rc.2`.

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
    implementation("ru.vitrina:vitrinakit-kmp-sdk:0.1.0-rc.2")
}
```

Use a GitHub token with package read access for `gpr.key`. Do not put tokens in
source files, docs, build logs, or mobile application code.

## Quickstart

Activate the SDK once with the public API key from VitrinaKit. Do not pass
secret API keys or provider credentials to mobile apps.

```kotlin
import ru.vitrina.sdk.VitrinaKit
import ru.vitrina.sdk.VitrinaKitConfig
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
    returnUrl = "myapp://subscription/return",
)
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

The local dry-run repository should contain the root multiplatform publication
at `build/repository/ru/vitrina/vitrinakit-kmp-sdk/0.1.0-rc.2/` and target
publications such as JVM/iOS variants with Kotlin-generated artifact suffixes.

## Publish

Real publication uses the `GitHubPackages` Gradle repository. Prefer the
manual `Publish KMP SDK` GitHub Actions workflow and pass the exact SDK
version.

The workflow publishes from `ni032mas/vitrinakit-kmp` to that repository's own
GitHub Packages registry using the built-in `GITHUB_TOKEN` and
`permissions: packages: write`. No cross-repository PAT is required.

Local publication is also possible when a package token is available:

```bash
GITHUB_ACTOR=<github-user> GITHUB_TOKEN=<package-token> \
  ./gradlew publishAllPublicationsToGitHubPackagesRepository
```

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
