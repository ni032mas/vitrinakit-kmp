# VitrinaKit KMP SDK

Kotlin Multiplatform SDK for VitrinaKit mobile subscription integrations.

## Status

This package is developed in the private Vitrina monorepo and mirrored to the
public `ni032mas/vitrinakit-kmp` repository after secret scanning.

## Verify

```bash
./gradlew verifySdk
```

The verification task runs the JVM SDK tests and Dokka documentation generation
with warnings treated as failures.

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
