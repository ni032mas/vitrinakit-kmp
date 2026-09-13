# Android billing flavors sample

This sample keeps provider wiring in flavor composition roots while
`PurchaseFeature.kt` imports core SDK packages only.

- `global`: `vitrinakit-kmp-sdk` plus `vitrinakit-googleplay`;
- `ru`: `vitrinakit-kmp-sdk` plus `vitrinakit-hosted`.

RuStore is a separate distribution composition documented in
[`docs/purchases.md`](../../docs/purchases.md); it is intentionally absent from
both sample flavors.

From the repository root, compile and inspect both variants with:

```bash
./gradlew verifyAndroidFlavorSample
```

The verifier resolves each runtime dependency graph, rejects forbidden provider
modules, checks exactly one adapter registration in each flavor factory, and
compiles `globalDebug` and `ruDebug`.
