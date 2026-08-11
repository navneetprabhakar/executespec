#!/usr/bin/env bash
set -euo pipefail

: "${CURRENT_SHA_BUCKET:?CURRENT_SHA_BUCKET is required}"
: "${TARGET_SHA:?TARGET_SHA is required}"

MATCH_GENERATION="${PREVIOUS_CURRENT_GENERATION:-0}"
OBSERVED_SHA="$(gsutil cat "$CURRENT_SHA_BUCKET" 2>/dev/null | tr -d '\r\n' || true)"
OBSERVED_GENERATION="$(gsutil stat "$CURRENT_SHA_BUCKET" 2>/dev/null \
  | awk '/Generation:/ { print $2; exit }' || true)"

if [ "$OBSERVED_SHA" = "$TARGET_SHA" ]; then
  echo "CURRENT_SHA already identifies the verified live candidate."
  exit 0
fi
if [ "$OBSERVED_SHA" != "${PREVIOUS_CURRENT_SHA:-}" ] \
  || [ "${OBSERVED_GENERATION:-0}" != "$MATCH_GENERATION" ]; then
  echo "CURRENT_SHA changed during deploy; refusing stale pointer write" >&2
  echo "expected sha=${PREVIOUS_CURRENT_SHA:-missing} generation=$MATCH_GENERATION" >&2
  echo "observed sha=${OBSERVED_SHA:-missing} generation=${OBSERVED_GENERATION:-0}" >&2
  exit 1
fi

printf '%s' "$TARGET_SHA" \
  | gsutil -h "x-goog-if-generation-match:$MATCH_GENERATION" cp - "$CURRENT_SHA_BUCKET"
PUBLISHED_SHA="$(gsutil cat "$CURRENT_SHA_BUCKET" | tr -d '\r\n')"
if [ "$PUBLISHED_SHA" != "$TARGET_SHA" ]; then
  echo "CURRENT_SHA publication mismatch: $PUBLISHED_SHA" >&2
  exit 1
fi
echo "CURRENT_SHA now identifies verified live candidate $PUBLISHED_SHA."
