#!/usr/bin/env bash
#
# deploy-local.sh — run the Build & Deploy pipeline from a workstation when GitHub
# Actions minutes are exhausted. Mirrors .github/workflows/deploy.yml step-for-step
# (build JAR → upload to GCS → env-patch + swap JAR + systemctl restart over IAP SSH
# → health check → provider-key verify), but authenticates with your LOCAL gcloud
# (Workload Identity is CI-only) instead of the CI service account.
#
# SAFETY
#   --dry-run is the default. It runs ONLY read-only checks (instance list, bucket
#   read, a no-op SSH probe) and prints the plan. It mutates nothing.
#   The real deploy requires BOTH:  --deploy  AND  CONFIRM_DEPLOY=YES
#   It SSHes into a running VM and restarts the service — only do this knowingly.
#
# USAGE
#   scripts/deploy-local.sh                         # dry-run against dev (default)
#   scripts/deploy-local.sh --sha <commit>          # dry-run a specific SHA
#   CONFIRM_DEPLOY=YES scripts/deploy-local.sh --deploy            # real deploy, dev, HEAD
#   CONFIRM_DEPLOY=YES scripts/deploy-local.sh --deploy --sha <c>  # real deploy, specific SHA
#
# ENV OVERRIDES (defaults discovered from the running dev VM)
#   ENVNAME (dev|benchmark)   default: dev      # NOTE: prod is not wired (no GCP prod creds)
#   GCP_PROJECT               default: executespecv1
#   GCE_ZONE                  default: us-east4-b
#
# NOT mirrored here (auxiliary + idempotent in CI; the VM already has them):
#   validator-toolchain install, design-system rsync, docker-image prune, Slack notify.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENVNAME="${ENVNAME:-dev}"
GCP_PROJECT="${GCP_PROJECT:-executespecv1}"
GCE_ZONE="${GCE_ZONE:-us-east4-b}"
MODE="dry-run"
SHA=""
SKIP_BUILD=0

while (($#)); do
  case "$1" in
    --deploy)     MODE="deploy"; shift ;;
    --dry-run)    MODE="dry-run"; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --env)        ENVNAME="$2"; shift 2 ;;
    --sha)        SHA="$2"; shift 2 ;;
    -h|--help)    sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

[ -n "$SHA" ] || SHA="$(git rev-parse HEAD)"
INSTANCE="executespec-${ENVNAME}-vm"
BUCKET="gs://executespec-${ENVNAME}-deploy"
BUCKET_NAME="executespec-${ENVNAME}-deploy"
case "$ENVNAME" in
  prod)       PUBLIC_URL="https://app.executespec.ai" ;;
  benchmark)  PUBLIC_URL="https://benchmark.executespec.ai" ;;
  *)          PUBLIC_URL="https://dev.executespec.ai" ;;
esac

# gcloud's bundled python prints a noisy 3.9 deprecation warning; keep it off stdout.
GC() { gcloud "$@" 2>/dev/null; }
SSH_BASE=(compute ssh "$INSTANCE" --project "$GCP_PROJECT" --zone "$GCE_ZONE" --tunnel-through-iap --strict-host-key-checking=no)

echo "── deploy-local ──────────────────────────────────────────"
echo "  mode=$MODE  env=$ENVNAME  sha=$SHA"
echo "  project=$GCP_PROJECT  zone=$GCE_ZONE  instance=$INSTANCE"
echo "  bucket=$BUCKET  public_url=$PUBLIC_URL"
echo "──────────────────────────────────────────────────────────"

# ── 0. Read-only preflight (always; also IS the dry-run) ──────────────────────
echo "[preflight] compute instance…"
GC compute instances list --project "$GCP_PROJECT" --filter="name=$INSTANCE" \
  --format="table(name,zone,status)" || { echo "  cannot list instance"; exit 1; }
echo "[preflight] deploy bucket…"
gsutil ls "$BUCKET/releases/" >/dev/null 2>&1 && echo "  bucket readable: $BUCKET" || { echo "  cannot read $BUCKET"; exit 1; }
echo "  CURRENT_SHA on $ENVNAME: $(gsutil cat "$BUCKET/CURRENT_SHA" 2>/dev/null || echo '?')"
gsutil -q stat "$BUCKET/config/api-keys" 2>/dev/null && echo "  config/api-keys: present" || echo "  config/api-keys: MISSING (runs would 401)"

if [ "$MODE" = "dry-run" ]; then
  echo "[dry-run] SSH/IAP probe (read-only echo)…"
  "${SSH_BASE[@]}" --command="echo SSH_OK; cat /opt/app/RUNNING_SHA 2>/dev/null; sudo systemctl is-active executespec 2>/dev/null" 2>/dev/null \
    && echo "[dry-run] SSH/IAP OK" || echo "[dry-run] SSH/IAP FAILED (perms or IAP) — fix before --deploy"
  echo "[dry-run] complete — nothing was changed. Re-run with: CONFIRM_DEPLOY=YES $0 --deploy"
  exit 0
fi

# ── Real deploy from here ─────────────────────────────────────────────────────
[ "${CONFIRM_DEPLOY:-}" = "YES" ] || { echo "Refusing to deploy without CONFIRM_DEPLOY=YES"; exit 3; }

# ── 1. Build frontend + JAR (mirrors deploy.yml) ──────────────────────────────
if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[build] frontend…"; (cd frontend && npm ci && npm run build)
  echo "[build] JAR (skip tests — PR-gated)…"; mvn -B -q -DskipTests package
fi
JAR="$(ls target/*.jar | head -1)"; echo "[build] JAR: $JAR"

# ── 2. Upload JAR keyed by SHA + CURRENT_SHA ──────────────────────────────────
echo "[upload] $BUCKET/releases/app-$SHA.jar"
gsutil cp "$JAR" "$BUCKET/releases/app-$SHA.jar"
echo "$SHA" | gsutil cp - "$BUCKET/CURRENT_SHA"

# ── 3. Write env-patch.sh (verbatim from deploy.yml) ──────────────────────────
cat > /tmp/env-patch.sh << 'SCRIPT'
#!/bin/bash
ENV_FILE="/opt/app/executespec.env"
touch "$ENV_FILE"; chmod 600 "$ENV_FILE"
upsert() {
  local key="${1%%=*}"; local kv="$1"
  if grep -q "^${key}=" "$ENV_FILE" 2>/dev/null; then sed -i "s|^${key}=.*|${kv}|" "$ENV_FILE"; else echo "${kv}" >> "$ENV_FILE"; fi
}
upsert "CORS_ALLOWED_ORIGINS=https://app.executespec.ai,https://dev.executespec.ai,https://benchmark.executespec.ai,http://localhost:5173"
upsert "DASHBOARD_URL=$1"
upsert "APP_BASE_URL=$1"
upsert "MAIL_TRANSPORT=smtp"
upsert "MAIL_FROM=admin@executespec.ai"
if grep -q '^JAVA_OPTS=' "$ENV_FILE" && ! grep -q 'launchMechanism' "$ENV_FILE"; then
  sed -i 's|^\(JAVA_OPTS="[^"]*\)"|\1 -Djdk.lang.Process.launchMechanism=vfork"|' "$ENV_FILE"
elif ! grep -q '^JAVA_OPTS=' "$ENV_FILE"; then
  echo 'JAVA_OPTS="-Xms4g -Xmx12g -XX:+UseG1GC -Djdk.lang.Process.launchMechanism=vfork"' >> "$ENV_FILE"
fi
upsert "PREVIEW_ENGINE_URL=https://preview-engine.executespec.ai"
upsert "PREVIEW_ENGINE_REGISTRY_URL=us-east4-docker.pkg.dev/executespecv1/preview-images"
upsert "PREVIEW_ENGINE_ROUTE_VIA=true"
upsert "PREVIEW_MODE=docker"
DEPLOY_BUCKET="$2"
if [ -n "$DEPLOY_BUCKET" ]; then
  KF="/tmp/api-keys-$$"
  if gsutil -q stat "gs://${DEPLOY_BUCKET}/config/api-keys" 2>/dev/null; then
    gsutil cp "gs://${DEPLOY_BUCKET}/config/api-keys" "$KF" 2>/dev/null
    while IFS= read -r line; do [[ -z "$line" || "$line" == \#* ]] && continue; upsert "$line"; done < "$KF"; rm -f "$KF"
  fi
  SF="/tmp/smtp-creds-$$"
  if gsutil -q stat "gs://${DEPLOY_BUCKET}/config/smtp-credentials" 2>/dev/null; then
    gsutil cp "gs://${DEPLOY_BUCKET}/config/smtp-credentials" "$SF" 2>/dev/null
    while IFS= read -r line; do [[ -z "$line" || "$line" == \#* ]] && continue; upsert "$line"; done < "$SF"; rm -f "$SF"
  fi
fi
SCRIPT
chmod +x /tmp/env-patch.sh

# ── 4. Deploy: scp env-patch, swap JAR, restart ───────────────────────────────
echo "[deploy] scp env-patch + swap JAR + restart…"
gcloud compute scp /tmp/env-patch.sh "$INSTANCE":/tmp/env-patch.sh \
  --project "$GCP_PROJECT" --zone "$GCE_ZONE" --tunnel-through-iap --strict-host-key-checking=no
"${SSH_BASE[@]}" --command="
  set -e
  sudo mkdir -p /opt/app/releases
  sudo gsutil cp $BUCKET/releases/app-$SHA.jar /opt/app/releases/
  sudo bash /tmp/env-patch.sh '$PUBLIC_URL' '$BUCKET_NAME'
  rm -f /tmp/env-patch.sh
  sudo ln -sf /opt/app/releases/app-$SHA.jar /opt/app/app.jar
  echo '$SHA' | sudo tee /opt/app/RUNNING_SHA
  sudo systemctl restart executespec
  echo 'Deploy done — service restarting'
"

# ── 5. Wait + health check (mirrors deploy.yml) ───────────────────────────────
echo "[health] waiting 45s for boot…"; sleep 45
for i in $(seq 1 20); do
  RESULT="$("${SSH_BASE[@]}" --command="rm -f /tmp/health.json; HTTP=\$(curl -s -o /tmp/health.json -w '%{http_code}' http://localhost:8080/actuator/health); echo \"http=\$HTTP body=\$(cat /tmp/health.json 2>/dev/null)\"" 2>/dev/null || echo SSH_FAIL)"
  echo "  attempt $i: $RESULT"
  if echo "$RESULT" | grep -q 'http=200' && echo "$RESULT" | grep -qE '"status":"(UP|DEGRADED)"'; then
    echo "[health] OK"; break
  fi
  [ "$i" -eq 20 ] && { echo "[health] FAILED after 20 attempts"; exit 1; }
  sleep 10
done

# ── 6. Provider-key verify (read-only) ────────────────────────────────────────
PROV="$("${SSH_BASE[@]}" --command="curl -s http://localhost:8080/api/health/providers" 2>/dev/null || echo SSH_FAIL)"
echo "[providers] $PROV"
echo "$PROV" | grep -qE '"anthropic"[[:space:]]*:[[:space:]]*false' && { echo "::error:: anthropic key missing — runs will 401"; exit 1; } || true

echo "✅ Deployed $SHA to $INSTANCE ($ENVNAME). JAR: $BUCKET/releases/app-$SHA.jar"
