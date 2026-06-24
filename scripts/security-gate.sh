#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required security tool is missing: $1" >&2
    exit 1
  fi
}

echo "==> Scanning git history for committed secrets"
require_command gitleaks
(cd "$ROOT_DIR" && gitleaks git . --redact --no-banner)

echo "==> Optional extended scanners"
if command -v osv-scanner >/dev/null 2>&1; then
  (cd "$ROOT_DIR" && osv-scanner scan source -r .)
else
  echo "osv-scanner is not installed; skipping OSV source scan"
fi

if command -v trivy >/dev/null 2>&1; then
  (cd "$ROOT_DIR" && trivy fs --scanners vuln,secret,misconfig --skip-dirs .git --skip-dirs .gradle --skip-dirs build .)
else
  echo "trivy is not installed; skipping filesystem scan"
fi
