# API compatibility report: 0.1.0-rc.6 to 0.1.0-rc.7

## Binary and source surface

- The existing core coordinate `ru.vitrina:vitrinakit-kmp-sdk` and public
  package names are preserved.
- Legacy paywall, hosted `makePurchase`, profile, and blocking overloads remain
  present and deprecated for the 0.1 RC migration line.
- Identity, provider-neutral purchase/restore results, adapter registration,
  and provider artifacts are additive public surfaces.

## Approved breaking migration

The only intentional compatibility break is the move from identity-per-call
hosted checkout to an activated, identity-bound SDK with exactly one adapter:

- activation without exactly one adapter is rejected;
- `identify` must bind the current application account before paywall, profile,
  purchase, and restore operations;
- deprecated per-call `userId`, `receiptEmail`, and `returnUrl` arguments are no
  longer authoritative; hosted values come from adapter configuration;
- feature code handles provider-neutral success, pending, cancellation, and
  failure results.

No other binary or source removal is approved for this release candidate.
Provider evidence remains below the official adapter-to-core boundary and is
absent from feature-level results.
