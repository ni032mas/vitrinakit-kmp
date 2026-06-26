# VitrinaKit KMP SDK Changelog

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
