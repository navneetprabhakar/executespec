#!/bin/bash
# ── Seed Secret Manager from existing GCS api-keys file ──────────────────────
#
# One-time migration: reads the plain-text GCS config/api-keys file and
# creates Secret Manager secrets. Run once, then the GCS file can be deleted.
#
# Prerequisites:
#   - gcloud authenticated with sufficient permissions
#   - Secret Manager API enabled
#   - Secrets already created (via Terraform module)
#
# Usage:
#   ./seed-secrets.sh <GCP_PROJECT> <DEPLOY_BUCKET>
#
# Example:
#   ./seed-secrets.sh cs-poc-wndx76juxpe1pups25gw6tj executespec-dev-deploy

set -euo pipefail

PROJECT="${1:?Usage: $0 <GCP_PROJECT> <DEPLOY_BUCKET>}"
BUCKET="${2:?Usage: $0 <GCP_PROJECT> <DEPLOY_BUCKET>}"
PREFIX="executespec"

# Map env var name → secret suffix
declare -A KEY_MAP=(
  ["ANTHROPIC_API_KEY"]="anthropic-api-key"
  ["OPENAI_API_KEY"]="openai-api-key"
  ["GOOGLE_API_KEY"]="google-api-key"
  ["GITHUB_PAT"]="github-pat"
  ["RAZORPAY_KEY_ID"]="razorpay-key-id"
  ["RAZORPAY_KEY_SECRET"]="razorpay-key-secret"
  ["RAZORPAY_WEBHOOK_SECRET"]="razorpay-webhook-secret"
  ["SMTP_USERNAME"]="smtp-username"
  ["SMTP_PASSWORD"]="smtp-password"
  ["SLACK_CLIENT_SECRET"]="slack-client-secret"
  ["SLACK_SIGNING_SECRET"]="slack-signing-secret"
  ["JIRA_API_TOKEN"]="jira-api-token"
  ["CONFLUENCE_API_TOKEN"]="confluence-api-token"
)

echo "=== Seeding Secret Manager from GCS ==="
echo "  Project: $PROJECT"
echo "  Bucket:  gs://$BUCKET/config/api-keys"
echo ""

# Download keys file
TMP_FILE=$(mktemp)
trap "rm -f $TMP_FILE" EXIT

if ! gsutil cp "gs://$BUCKET/config/api-keys" "$TMP_FILE" 2>/dev/null; then
  echo "ERROR: Could not download gs://$BUCKET/config/api-keys"
  exit 1
fi

SEEDED=0
SKIPPED=0

while IFS= read -r line; do
  [[ -z "$line" || "$line" == \#* ]] && continue

  key="${line%%=*}"
  value="${line#*=}"

  suffix="${KEY_MAP[$key]:-}"
  if [ -z "$suffix" ]; then
    echo "  SKIP: $key (no mapping)"
    ((SKIPPED++))
    continue
  fi

  secret_id="${PREFIX}-${suffix}"

  # Check if secret exists
  if ! gcloud secrets describe "$secret_id" --project="$PROJECT" &>/dev/null; then
    echo "  CREATE: $secret_id"
    gcloud secrets create "$secret_id" \
      --project="$PROJECT" \
      --replication-policy="automatic" \
      --labels="app=executespec" 2>/dev/null || true
  fi

  # Add version
  echo -n "$value" | gcloud secrets versions add "$secret_id" \
    --project="$PROJECT" \
    --data-file=- 2>/dev/null

  echo "  SEEDED: $key → $secret_id (${#value} chars)"
  ((SEEDED++))

done < "$TMP_FILE"

echo ""
echo "=== Done: $SEEDED seeded, $SKIPPED skipped ==="

# Also seed SMTP credentials if available
SMTP_FILE=$(mktemp)
trap "rm -f $TMP_FILE $SMTP_FILE" EXIT

if gsutil cp "gs://$BUCKET/config/smtp-credentials" "$SMTP_FILE" 2>/dev/null; then
  echo ""
  echo "=== Seeding SMTP credentials ==="
  while IFS= read -r line; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    key="${line%%=*}"
    value="${line#*=}"
    suffix="${KEY_MAP[$key]:-}"
    if [ -n "$suffix" ]; then
      secret_id="${PREFIX}-${suffix}"
      echo -n "$value" | gcloud secrets versions add "$secret_id" \
        --project="$PROJECT" \
        --data-file=- 2>/dev/null
      echo "  SEEDED: $key → $secret_id"
    fi
  done < "$SMTP_FILE"
fi
