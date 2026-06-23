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

## Verify

```bash
./gradlew verifySdk
```

The verification task runs the JVM SDK tests and Dokka documentation generation
with warnings treated as failures.

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
