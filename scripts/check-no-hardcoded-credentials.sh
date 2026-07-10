#!/usr/bin/env bash
set -euo pipefail

# Static guard for executable QA/eval helpers. Mail/from defaults are not credentials;
# password/token literals and password fallbacks are forbidden.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if rg -n --glob '*.sh' --glob '*.mjs' --glob '!check-no-hardcoded-credentials.sh' \
  'g7kZfo#oKYrI|Admin@123|Revalidate1!|PASSWORD="\$\{[^}]*:-[A-Za-z0-9]|ES_PASS="\$\{[^}]*:-[A-Za-z0-9]|E2E_PASSWORD="\$\{[^}]*:-[A-Za-z0-9]' \
  "$ROOT/scripts" "$ROOT/tooling"; then
  echo "hardcoded credential or password fallback detected" >&2
  exit 1
fi
echo "credential scan passed"
