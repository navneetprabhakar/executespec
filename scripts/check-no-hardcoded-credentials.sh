#!/usr/bin/env bash
set -euo pipefail

# Scan the proposed commit using the repository's narrowly scoped fixture policy.
# Install the gitleaks CLI first, or set GITLEAKS_BIN to an installed executable.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCANNER="${GITLEAKS_BIN:-gitleaks}"
if ! command -v "$SCANNER" >/dev/null 2>&1; then
  echo "gitleaks is required; install it or set GITLEAKS_BIN" >&2
  exit 2
fi
exec "$SCANNER" git --staged --redact=100 --no-banner --config "$ROOT/.gitleaks.toml" "$ROOT"
