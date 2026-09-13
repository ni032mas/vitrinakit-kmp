#!/usr/bin/env bash
# Installs repository-managed git hooks for this checkout.

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"

if ! command -v gitleaks >/dev/null 2>&1; then
  echo "gitleaks is required before installing hooks." >&2
  exit 1
fi

chmod +x "$ROOT"/.githooks/commit-msg "$ROOT"/.githooks/pre-commit "$ROOT"/.githooks/pre-merge-commit "$ROOT"/.githooks/pre-push
git -C "$ROOT" config core.hooksPath .githooks
echo "Installed git hooks from .githooks"
