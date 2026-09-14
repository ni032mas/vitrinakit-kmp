# Repository Guidelines

## Project Structure & Module Organization

Single-module Kotlin Multiplatform SDK published as `ru.vitrina:vitrinakit-kmp-sdk`.

- `src/commonMain/kotlin/ru/vitrina/sdk/` contains public APIs, transport contracts, and serializable models.
- `src/commonTest/kotlin/ru/vitrina/sdk/` contains JVM-run shared tests.
- `build.gradle.kts` defines Kotlin, serialization, Dokka, publishing, JVM, and iOS targets.
- `.github/workflows/publish-kmp-sdk.yml` is the publishing workflow.
- Generated outputs live under `build/`; do not edit them.

## Branch Workflow

`dev` is the default integration branch. `main` is stable and release-only. Do not commit directly to either branch; create short feature branches such as `chore/3-repository-workflow-hooks`, open PRs into `dev`, and use release PRs from `dev` to `main`.

Merge strategy, which differs by target:

- **PRs into `dev` are squash-merged.**
- **A release PR into `main` is merged with a regular merge commit, never squash.** Squashing it writes a new commit onto `main` carrying content that already exists on `dev` under different SHAs, so the branches diverge permanently and the next ordinary merge conflicts. The repository permits merge commits for exactly this reason, and rebase merges stay disabled so nothing else can be selected by accident.
- **Release tags are annotated and point at the merge commit on `main`**, not at the release branch tip.
- A `main`-into-`dev` sync, when one is needed, is a regular merge, not a squash, so the release history stays connected.

## API Contract Alignment

This SDK is a public client for VitrinaKit API contracts. Before changing request paths, headers, response models, authentication behavior, or release processes, verify the change against the current server API contract available to maintainers.

Keep the SDK aligned with public-safe `/api/v1/*` contracts. Do not include private repository paths, internal issue links, unreleased roadmap details, customer data, secrets, or backend implementation notes in this public repository.

## Build, Test, and Development Commands

- `./gradlew test` runs the SDK JVM test suite (`jvmTest`).
- `./gradlew verifySdk` runs tests and Dokka with warnings as failures.
- `./gradlew dokkaGenerate` writes API docs to `build/dokka/html`.
- `./gradlew verifyReleaseArtifacts` dry-runs Maven publication to `build/repository`.
- `./gradlew assembleVitrinaKitXCFramework` builds `build/XCFrameworks/release/VitrinaKit.xcframework`.
- `make verify` runs the repository security gate and release dry-run.
- `make install-git-hooks` installs `.githooks` for branch, commit message, and secret-scan checks.

## Coding Style & Naming Conventions

Use idiomatic Kotlin with 4-space indentation, explicit visibility where useful, `val` over `var`, immutable collections, and `runCatching` for recoverable failures. Keep public APIs under `ru.vitrina.sdk`. Use `PascalCase` for types and `camelCase` for functions/properties.

Public SDK models should be `@Serializable`, use typed enums for fixed wire values, and annotate enum/API fields with `@SerialName`. Public APIs need concise KDoc because Dokka fails on undocumented surface.

## Testing Guidelines

Tests use `kotlin.test` and `kotlinx-coroutines-test`. Place common behavior tests in `src/commonTest`; add platform-specific source sets only when behavior differs. Cover request paths, headers, serialization, errors, and fallbacks. Run `./gradlew verifySdk` before opening a PR.

## Commit & Pull Request Guidelines

Recent history uses Conventional Commit-style prefixes such as `chore:`. Prefer `feat:`, `fix:`, `docs:`, `test:`, or `chore:` with a short imperative summary.

PRs should target `dev`, describe SDK/API impact, link the related issue, list verification commands, and call out release or publishing implications. Do not bump `version` in `build.gradle.kts` except for an explicit release task.

## Security & Configuration Tips

Never commit package tokens, secret API keys, provider credentials, or build logs containing them. Mobile integrations use publishable SDK keys only. Prefer the manual `Publish KMP SDK` workflow; local publishing requires `GITHUB_ACTOR` and `GITHUB_TOKEN`.
