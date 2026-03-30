# ExecuteSpec — Deployment Guide

> **Target audience:** Engineers running ExecuteSpec locally or deploying to Google Cloud Platform.

---

## Local Development Setup

### Prerequisites

- Java 21 (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21` on macOS)
- PostgreSQL 15+ running on `localhost:5432`
- Redis 7+ running on `localhost:6379`
- Node.js 18+ and npm (for frontend build)
- Maven 3.8+ (`mvn -version`)
- API keys: `ANTHROPIC_API_KEY` (required), `OPENAI_API_KEY` (required for failover)

### Database Setup

```bash
# Create the database (using Postgres.app on macOS)
/Applications/Postgres.app/Contents/Versions/latest/bin/psql -U postgres -c "CREATE DATABASE executespec;"

# Or via standard psql
psql -U postgres -c "CREATE DATABASE executespec;"
```

Flyway migrations run automatically on startup and create all tables (V1–V39).

### Redis Setup

```bash
# Docker (simplest)
docker run -d -p 6379:6379 redis:7-alpine

# Or install via Homebrew (macOS)
brew install redis && brew services start redis
```

### Environment Variables

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANTHROPIC_API_KEY=sk-ant-...      # required
export OPENAI_API_KEY=sk-...             # required for fallback
export POSTGRES_USER=postgres
export POSTGRES_PASSWORD=postgres
export REDIS_HOST=localhost
# Optional
export GITHUB_PAT=ghp_...               # for GitHub integration
export GITHUB_OWNER=your-github-username
```

### Build & Run

```bash
# Option 1: Supervisor (recommended — auto-restart + health monitoring)
./supervisor.sh start
./supervisor.sh status   # check state
./supervisor.sh logs     # tail app logs
./supervisor.sh stop     # graceful stop

# Option 2: Manual
npm --prefix frontend install
npm --prefix frontend run build          # builds to src/main/resources/static/
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests
```

Open **http://localhost:8080** to access the dashboard.

Default admin: `admin@executespec.ai` — password is set via Flyway migration V7 (change on first login via Settings)

### Development Mode (Hot Reload)

Run frontend and backend separately:

```bash
# Terminal 1: Frontend dev server (proxies /api -> localhost:8080)
npm --prefix frontend run dev    # -> http://localhost:5173

# Terminal 2: Backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests
```

### Compile Check

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q     # backend
npm --prefix frontend run build                             # frontend (tsc + vite)
```

### Troubleshooting (Local)

| Issue | Fix |
|-------|-----|
| `JAVA_HOME not set` | `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` |
| `Connection refused` to PostgreSQL | Ensure PostgreSQL is running on port 5432 with database `executespec` |
| `Connection refused` to Redis | Start Redis: `docker run -d -p 6379:6379 redis:7-alpine` |
| Admin login fails | Flyway V5 seeds admin, V7 fixes password hash. Check `flyway_schema_history` table. |
| LLM calls fail | Verify `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` are set and valid |

---

## GCP Deployment Guide

> From a blank GCP project to production-ready, HA deployment.

---

## GCP Overview

ExecuteSpec is a **single-binary deployment**. The React 18 frontend is compiled via
`npm run build` and embedded into `src/main/resources/static/`, so the Spring Boot JAR
serves both the API and the SPA. There is no separate frontend server or CDN required.

### Architecture

```
                        ┌──────────────────────────────────────────────────────────┐
                        │                    GCP us-central1                       │
                        │                                                          │
  Internet ──HTTPS──► Cloud Load Balancer (443 → 8080)                           │
                        │         │                                                │
                        │   [Cookie Affinity / SSE sticky sessions]               │
                        │         │                                                │
                        │  ┌──────┴───────┐                                       │
                        │  │     MIG      │   Managed Instance Group              │
                        │  │  2× n2-std-8 │   us-central1-a                      │
                        │  │  GCE VMs     │   AutoHealing on /actuator/health     │
                        │  └──────┬───────┘                                       │
                        │         │ private IP only (no-address)                  │
                        │         │                                                │
                        │  ┌──────┴──────────────────────────┐                   │
                        │  │                                  │                   │
                        │  ▼                                  ▼                   │
                        │  Cloud SQL PostgreSQL 15     Cloud Memorystore Redis 7  │
                        │  db-custom-2-3840             1GB Basic tier            │
                        │  private IP only              private IP only           │
                        │                                                          │
                        │  GCS Buckets:                                            │
                        │  ├─ executespec-workspaces  (workspace archives)        │
                        │  └─ executespec-deploy      (JAR releases / CI-CD)      │
                        │                                                          │
                        │  Cloud NAT  (outbound: LLM API calls to Anthropic,      │
                        │             OpenAI, Google)                              │
                        └──────────────────────────────────────────────────────────┘
```

### Monthly Cost Estimate

| Component | Spec | Est. Cost/Month |
|---|---|---|
| GCE VMs (×2) | n2-standard-8 (8 vCPU, 32 GB), us-central1 | ~$260 |
| Cloud SQL | db-custom-2-3840, 50 GB pd-ssd, us-central1 | ~$60 |
| Data disk (per VM) | 100 GB pd-ssd | ~$17 |
| Cloud Memorystore Redis | 1 GB Basic, us-central1 | ~$16 |
| Cloud Load Balancer | Global HTTP(S) LB | ~$18 |
| GCS storage | 50 GB total (2 buckets) | ~$1 |
| Cloud NAT | ~10 GB egress to LLM APIs | ~$3 |
| Egress | General outbound traffic | ~$10 |
| Misc (Secret Manager, logging, etc.) | | ~$23 |
| **Total** | | **~$278/month** |

> LLM API costs (Anthropic, OpenAI, Google) are billed separately by each provider and
> are NOT included above. Budget ~$50-200/month depending on run volume.

---

## Prerequisites Checklist

Complete every item before starting Step 1.

- [ ] GCP account with billing enabled
- [ ] `gcloud` CLI installed and authenticated (`gcloud auth login && gcloud auth application-default login`)
- [ ] Docker installed locally (for testing container builds only)
- [ ] Java 21 installed locally (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21` on macOS)
- [ ] Node.js 18+ and npm installed locally (for frontend build)
- [ ] Apache Maven installed locally (`mvn -version` should show 3.8+)
- [ ] A domain name whose DNS you control (for the managed SSL certificate)
- [ ] GitHub repository with the ExecuteSpec source code
- [ ] API keys ready:
  - [ ] `ANTHROPIC_API_KEY` — required (primary LLM provider)
  - [ ] `OPENAI_API_KEY` — required (fallback LLM provider)
  - [ ] `GOOGLE_API_KEY` — optional (third-tier Gemini fallback)
- [ ] A GitHub Personal Access Token (`GITHUB_PAT`) with `repo` scope — used by the agent pipeline to push generated code to target repos
- [ ] Decided on a `GCP_PROJECT` ID (must be globally unique)

---

## Step 1: GCP Project Setup

### 1.1 Set your project and region

```bash
export PROJECT_ID=executespec-prod          # Change to your project ID
export REGION=us-central1
export ZONE=us-central1-a

gcloud config set project $PROJECT_ID
gcloud config set compute/region $REGION
gcloud config set compute/zone $ZONE
```

### 1.2 Enable required APIs

```bash
gcloud services enable \
  compute.googleapis.com \
  container.googleapis.com \
  sqladmin.googleapis.com \
  redis.googleapis.com \
  secretmanager.googleapis.com \
  storage.googleapis.com \
  iap.googleapis.com \
  artifactregistry.googleapis.com \
  cloudresourcemanager.googleapis.com \
  iam.googleapis.com \
  logging.googleapis.com \
  monitoring.googleapis.com
```

This may take 1-2 minutes. All APIs must show `Enabled` before proceeding.

### 1.3 Create the service account

```bash
gcloud iam service-accounts create executespec-sa \
  --display-name="ExecuteSpec App Service Account"
```

### 1.4 Grant IAM roles

```bash
SA=executespec-sa@${PROJECT_ID}.iam.gserviceaccount.com

# Cloud SQL access (app connects to DB)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${SA}" --role="roles/cloudsql.client"

# GCS: read/write workspace archives and JAR releases
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${SA}" --role="roles/storage.objectAdmin"

# Secret Manager: read all secrets at runtime
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${SA}" --role="roles/secretmanager.secretAccessor"

# Allow SA to impersonate itself (needed for some GCP SDK calls)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${SA}" --role="roles/iam.serviceAccountTokenCreator"

# Cloud Run: deploy preview containers (PreviewAgent / DeploymentService)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${SA}" --role="roles/run.admin"

# Artifact Registry: push/pull Docker images for Cloud Run previews
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${SA}" --role="roles/artifactregistry.admin"

# Allow IAP-tunneled SSH from GitHub Actions
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${SA}" --role="roles/iap.tunnelResourceAccessor"

# Compute instance admin (needed by deploy workflow to SSH via IAP)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${SA}" --role="roles/compute.instanceAdmin.v1"
```

### 1.5 Create a service account key for GitHub Actions

```bash
gcloud iam service-accounts keys create sa-key.json \
  --iam-account=executespec-sa@${PROJECT_ID}.iam.gserviceaccount.com
```

> **Security:** `sa-key.json` contains a private key. Store it in GitHub Actions secrets
> immediately and delete the local file. Do NOT commit it.

```bash
# Upload to GitHub (requires gh CLI)
gh secret set GCP_SA_KEY --repo YOUR_ORG/executespec < sa-key.json

# Then delete the local file
rm sa-key.json
```

---

## Step 2: Network & VPC Setup

ExecuteSpec VMs have **no external IP** (`--no-address`). All outbound traffic (LLM API
calls to Anthropic, OpenAI, Google) flows through Cloud NAT.

### 2.1 Create a Cloud Router

```bash
gcloud compute routers create executespec-router \
  --network=default \
  --region=$REGION
```

### 2.2 Create Cloud NAT

```bash
gcloud compute routers nats create executespec-nat \
  --router=executespec-router \
  --region=$REGION \
  --auto-allocate-nat-external-ips \
  --nat-all-subnet-ip-ranges \
  --enable-logging
```

### 2.3 Firewall rules

**Allow LB health checks to reach port 8080:**

```bash
gcloud compute firewall-rules create allow-lb-health-check \
  --direction=INGRESS \
  --priority=1000 \
  --network=default \
  --action=ALLOW \
  --rules=tcp:8080 \
  --source-ranges=130.211.0.0/22,35.191.0.0/16 \
  --target-tags=executespec-vm \
  --description="Allow GCP LB health check probes to port 8080"
```

**Allow IAP tunnel (SSH from GitHub Actions and engineers):**

```bash
gcloud compute firewall-rules create allow-iap-ssh \
  --direction=INGRESS \
  --priority=1000 \
  --network=default \
  --action=ALLOW \
  --rules=tcp:22 \
  --source-ranges=35.235.240.0/20 \
  --target-tags=executespec-vm \
  --description="Allow IAP TCP tunneling for SSH"
```

**Block all other ingress on 8080 and 22:**

```bash
gcloud compute firewall-rules create deny-direct-8080 \
  --direction=INGRESS \
  --priority=900 \
  --network=default \
  --action=DENY \
  --rules=tcp:8080 \
  --source-ranges=0.0.0.0/0 \
  --target-tags=executespec-vm \
  --description="Block direct access to port 8080 — LB only"
```

> Note: The `allow-lb-health-check` rule (priority 1000) takes precedence over the deny
> rule for LB health check source ranges because it is evaluated first; however, both rules
> are needed. The `deny-direct-8080` rule uses priority 900 which is *higher priority*
> (lower number = higher priority). Re-order if needed: set `allow-lb-health-check` to
> priority 800 and `deny-direct-8080` to priority 900 so the LB ranges are explicitly
> allowed first.

---

## Step 3: Cloud SQL (PostgreSQL 15)

### 3.1 Create the Cloud SQL instance

```bash
gcloud sql instances create executespec-pg \
  --database-version=POSTGRES_15 \
  --tier=db-custom-2-3840 \
  --region=$REGION \
  --no-assign-ip \
  --network=default \
  --storage-size=50 \
  --storage-type=SSD \
  --storage-auto-increase \
  --backup-start-time=03:00 \
  --retained-backups-count=7 \
  --deletion-protection
```

> This takes 5-10 minutes. The `--no-assign-ip` flag ensures the instance has no public IP.
> The `--deletion-protection` flag prevents accidental deletion.

### 3.2 Create the database

```bash
gcloud sql databases create executespec --instance=executespec-pg
```

### 3.3 Create the application user

```bash
# Generate a strong password
DB_PASSWORD=$(openssl rand -base64 32)
echo "DB password: $DB_PASSWORD"   # Save this — you'll need it for Secret Manager

gcloud sql users create appuser \
  --instance=executespec-pg \
  --password="$DB_PASSWORD"
```

### 3.4 Get the private IP address

```bash
POSTGRES_HOST=$(gcloud sql instances describe executespec-pg \
  --format='value(ipAddresses[0].ipAddress)')
echo "Cloud SQL private IP: $POSTGRES_HOST"
```

> Save this IP — you'll store it as a secret in Step 6.

---

## Step 4: GCS Buckets

### 4.1 Create the workspace archive bucket

```bash
gsutil mb -l $REGION -b on gs://executespec-workspaces
```

### 4.2 Create the CI/CD JAR staging bucket

```bash
gsutil mb -l $REGION -b on gs://executespec-deploy
```

### 4.3 Set lifecycle policy on executespec-deploy (keep last 30 days)

Create a lifecycle policy file:

```bash
cat > /tmp/deploy-lifecycle.json << 'EOF'
{
  "rule": [
    {
      "action": {"type": "Delete"},
      "condition": {"age": 30}
    }
  ]
}
EOF

gsutil lifecycle set /tmp/deploy-lifecycle.json gs://executespec-deploy
```

### 4.4 Set CORS on executespec-workspaces (for signed URL downloads)

```bash
cat > /tmp/workspace-cors.json << 'EOF'
[
  {
    "origin": ["https://YOUR_DOMAIN"],
    "method": ["GET", "HEAD"],
    "responseHeader": ["Content-Type", "Content-Disposition"],
    "maxAgeSeconds": 3600
  }
]
EOF

gsutil cors set /tmp/workspace-cors.json gs://executespec-workspaces
```

> Replace `YOUR_DOMAIN` with your actual domain (e.g., `https://app.executespec.ai`).

---

## Step 5: Cloud Memorystore (Redis 7)

```bash
gcloud redis instances create executespec-redis \
  --size=1 \
  --region=$REGION \
  --tier=BASIC \
  --redis-version=redis_7_0 \
  --network=default \
  --display-name="ExecuteSpec Redis"
```

> This takes 3-5 minutes.

Get the Redis host IP:

```bash
REDIS_HOST=$(gcloud redis instances describe executespec-redis \
  --region=$REGION \
  --format='value(host)')
echo "Redis host: $REDIS_HOST"
```

> Save this IP — you'll store it as a secret in Step 6.

---

## Step 6: Secret Manager — Store All Secrets

Store every runtime secret in Secret Manager. The VM startup script (Step 7) fetches
all of these at boot time and exports them as environment variables.

### 6.1 Create all secrets

```bash
# Helper function
create_secret() {
  local NAME=$1
  local VALUE=$2
  echo -n "$VALUE" | gcloud secrets create "$NAME" \
    --data-file=- \
    --replication-policy=automatic
}

# LLM API keys
create_secret ANTHROPIC_API_KEY  "sk-ant-api03-YOUR-KEY-HERE"
create_secret OPENAI_API_KEY     "sk-proj-YOUR-KEY-HERE"
create_secret GOOGLE_API_KEY     "AIzaSy-YOUR-KEY-HERE"   # optional — leave empty string if not used

# Database credentials
create_secret POSTGRES_USER      "appuser"
create_secret POSTGRES_PASSWORD  "$DB_PASSWORD"           # from Step 3.3
create_secret POSTGRES_HOST      "$POSTGRES_HOST"         # from Step 3.4
create_secret POSTGRES_DB        "executespec"

# GitHub integration (agent pipeline pushes code to repos)
create_secret GITHUB_PAT         "ghp_YOUR-PAT-HERE"
create_secret GITHUB_OWNER       "your-github-org-or-username"

# Storage
create_secret GCS_WORKSPACE_BUCKET  "executespec-workspaces"
create_secret GCS_DEPLOY_BUCKET     "executespec-deploy"

# JWT signing key — generate a strong random secret
JWT_SECRET=$(openssl rand -base64 64)
create_secret JWT_SECRET "$JWT_SECRET"

# Redis
create_secret REDIS_HOST "$REDIS_HOST"                    # from Step 5

# GCP identifiers (used by DeploymentService for Cloud Run previews)
create_secret GCP_PROJECT "$PROJECT_ID"
create_secret GCP_REGION  "$REGION"

# Application URL (used for email links)
create_secret APP_BASE_URL "https://YOUR_DOMAIN"

# Gmail OAuth (optional — set GMAIL_ENABLED=false to skip)
create_secret GMAIL_ENABLED       "false"
create_secret GMAIL_SENDER_EMAIL  "admin@executespec.ai"
```

### 6.2 Grant the service account access to all secrets

```bash
SA=executespec-sa@${PROJECT_ID}.iam.gserviceaccount.com

for SECRET in \
  ANTHROPIC_API_KEY OPENAI_API_KEY GOOGLE_API_KEY \
  POSTGRES_USER POSTGRES_PASSWORD POSTGRES_HOST POSTGRES_DB \
  GITHUB_PAT GITHUB_OWNER \
  GCS_WORKSPACE_BUCKET GCS_DEPLOY_BUCKET \
  JWT_SECRET REDIS_HOST GCP_PROJECT GCP_REGION \
  APP_BASE_URL GMAIL_ENABLED GMAIL_SENDER_EMAIL; do
  gcloud secrets add-iam-policy-binding $SECRET \
    --member="serviceAccount:${SA}" \
    --role="roles/secretmanager.secretAccessor"
done
```

---

## Step 7: VM Startup Script

This script runs on every VM boot. It fetches secrets from Secret Manager, mounts the
data disk, and starts the application via systemd.

Create the file at `/opt/app/startup.sh` on each VM (Step 9 covers putting it there):

```bash
#!/usr/bin/env bash
# /opt/app/startup.sh
# Runs at boot via cloud-init or manually.
# Fetches secrets, mounts data disk, launches the app.

set -euo pipefail

PROJECT=$(curl -sf "http://metadata.google.internal/computeMetadata/v1/project/project-id" \
  -H "Metadata-Flavor: Google")

fetch_secret() {
  gcloud secrets versions access latest \
    --secret="$1" \
    --project="$PROJECT" 2>/dev/null || echo ""
}

echo "[startup] Fetching secrets from Secret Manager..."

export ANTHROPIC_API_KEY=$(fetch_secret ANTHROPIC_API_KEY)
export OPENAI_API_KEY=$(fetch_secret OPENAI_API_KEY)
export GOOGLE_API_KEY=$(fetch_secret GOOGLE_API_KEY)
export POSTGRES_USER=$(fetch_secret POSTGRES_USER)
export POSTGRES_PASSWORD=$(fetch_secret POSTGRES_PASSWORD)
export POSTGRES_HOST=$(fetch_secret POSTGRES_HOST)
export POSTGRES_DB=$(fetch_secret POSTGRES_DB)
export POSTGRES_PORT=5432
export GITHUB_PAT=$(fetch_secret GITHUB_PAT)
export GITHUB_OWNER=$(fetch_secret GITHUB_OWNER)
export GCS_WORKSPACE_BUCKET=$(fetch_secret GCS_WORKSPACE_BUCKET)
export GCS_DEPLOY_BUCKET=$(fetch_secret GCS_DEPLOY_BUCKET)
export JWT_SECRET=$(fetch_secret JWT_SECRET)
export REDIS_HOST=$(fetch_secret REDIS_HOST)
export GCP_PROJECT=$(fetch_secret GCP_PROJECT)
export GCP_REGION=$(fetch_secret GCP_REGION)
export APP_BASE_URL=$(fetch_secret APP_BASE_URL)
export GMAIL_ENABLED=$(fetch_secret GMAIL_ENABLED)
export GMAIL_SENDER_EMAIL=$(fetch_secret GMAIL_SENDER_EMAIL)
export CORS_ALLOWED_ORIGINS="$APP_BASE_URL"

echo "[startup] All secrets loaded."

# ── Mount data disk at /data ─────────────────────────────────────────────────
DATA_DEVICE=/dev/disk/by-id/google-executespec-data

if [ -b "$DATA_DEVICE" ]; then
  if ! mountpoint -q /data; then
    echo "[startup] Mounting data disk..."
    mkdir -p /data/workspace
    # Format only if the disk has no filesystem yet
    if ! blkid "$DATA_DEVICE" | grep -q "ext4"; then
      echo "[startup] Formatting data disk as ext4 (first boot only)..."
      mkfs.ext4 -F "$DATA_DEVICE"
    fi
    mount "$DATA_DEVICE" /data
    # Ensure /etc/fstab has the entry so it survives reboot
    if ! grep -q "$DATA_DEVICE" /etc/fstab; then
      echo "$DATA_DEVICE /data ext4 defaults,nofail 0 2" >> /etc/fstab
    fi
    chown -R appuser:appuser /data
    echo "[startup] Data disk mounted at /data."
  else
    echo "[startup] /data already mounted."
  fi
else
  echo "[startup] WARNING: Data disk not found at $DATA_DEVICE — using local disk for workspaces."
  mkdir -p /data/workspace
  chown -R appuser:appuser /data
fi

echo "[startup] Starting ExecuteSpec..."

# ── Start the app (called by systemd ExecStart, so we exec directly) ─────────
exec java \
  -Xms1g \
  -Xmx6g \
  # NOTE: Pending upgrade to -Xms8g -Xmx16g once n2-standard-8 rollout completes
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:MaxGCPauseMillis=100 \
  -Djava.security.egd=file:/dev/./urandom \
  -jar /opt/app/app.jar \
  --agent.workspace=/data/workspace \
  --server.port=8080
```

---

## Step 8: Systemd Service Unit

Create this file at `/etc/systemd/system/executespec.service` on each VM:

```ini
# /etc/systemd/system/executespec.service
[Unit]
Description=ExecuteSpec Multi-Agent Coding System
Documentation=https://github.com/YOUR_ORG/executespec
After=network-online.target google-startup-scripts.service
Wants=network-online.target
Requires=network-online.target

[Service]
Type=simple
User=appuser
Group=appuser
WorkingDirectory=/opt/app

# startup.sh fetches secrets, mounts disk, then exec-replaces itself with the JVM
ExecStart=/opt/app/startup.sh

# Give the JVM time to start (120s for Spring context + DB migrations)
TimeoutStartSec=120

# Restart on any non-zero exit code
Restart=on-failure
RestartSec=15

# Capture all stdout/stderr to journald
StandardOutput=journal
StandardError=journal
SyslogIdentifier=executespec

# Environment hardening
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

---

## Step 9: VM Setup (First Instance)

Set up the first VM manually. After validating it, you will create the MIG in Step 10.

### 9.1 Create the VM

```bash
gcloud compute instances create executespec-prod \
  --machine-type=n2-standard-8 \
  --zone=$ZONE \
  --boot-disk-size=30GB \
  --boot-disk-type=pd-ssd \
  --image-family=debian-12 \
  --image-project=debian-cloud \
  --service-account=executespec-sa@${PROJECT_ID}.iam.gserviceaccount.com \
  --scopes=cloud-platform \
  --no-address \
  --subnet=default \
  --tags=executespec-vm \
  --metadata=enable-oslogin=TRUE
```

### 9.2 Create and attach the 100 GB data disk

```bash
# Create the disk
gcloud compute disks create executespec-data \
  --size=100GB \
  --type=pd-ssd \
  --zone=$ZONE

# Attach it with a stable device-name (used by startup.sh)
gcloud compute instances attach-disk executespec-prod \
  --disk=executespec-data \
  --device-name=executespec-data \
  --zone=$ZONE
```

### 9.3 SSH into the VM via IAP

```bash
gcloud compute ssh executespec-prod \
  --tunnel-through-iap \
  --zone=$ZONE
```

### 9.4 Install Java 21 and set up the application user

Run these commands on the VM (once connected via SSH):

```bash
# Update package index
sudo apt-get update -y

# Install Java 21 (Eclipse Temurin)
sudo apt-get install -y wget apt-transport-https gnupg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | \
  sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/adoptium.gpg
echo "deb https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" | \
  sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update -y
sudo apt-get install -y temurin-21-jdk

# Verify
java -version

# Install gcloud CLI (for Secret Manager access from startup.sh)
echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] \
  https://packages.cloud.google.com/apt cloud-sdk main" | \
  sudo tee /etc/apt/sources.list.d/google-cloud-sdk.list
curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | \
  sudo apt-key --keyring /usr/share/keyrings/cloud.google.gpg add -
sudo apt-get update -y && sudo apt-get install -y google-cloud-cli

# Create the application user
sudo useradd -r -m -s /sbin/nologin appuser

# Create application directories
sudo mkdir -p /opt/app/releases
sudo chown -R appuser:appuser /opt/app

# Create the startup script (paste contents from Step 7)
sudo nano /opt/app/startup.sh
sudo chmod +x /opt/app/startup.sh

# Create the systemd unit file (paste contents from Step 8)
sudo nano /etc/systemd/system/executespec.service

# Reload systemd and enable the service
sudo systemctl daemon-reload
sudo systemctl enable executespec
```

### 9.5 Deploy the first JAR manually

Before setting up CI/CD, deploy a JAR manually to verify the VM configuration works.

On your **local machine** (build the JAR first):

```bash
# Build the JAR (frontend is embedded automatically via the maven-exec-plugin)
# Ensure frontend dependencies are installed first if this is a fresh checkout
npm --prefix frontend install
mvn package -DskipTests

# Upload to GCS
JAR=$(ls target/*.jar | head -1)
INITIAL_SHA=$(git rev-parse HEAD)
gsutil cp "$JAR" gs://executespec-deploy/releases/app-${INITIAL_SHA}.jar
echo "$INITIAL_SHA" | gsutil cp - gs://executespec-deploy/CURRENT_SHA
```

Back on the VM (via SSH):

```bash
# Download and symlink the JAR
mkdir -p /opt/app/releases
gsutil cp gs://executespec-deploy/releases/app-${INITIAL_SHA}.jar /opt/app/releases/
# Replace INITIAL_SHA with your actual SHA
ln -sf /opt/app/releases/app-YOUR_SHA.jar /opt/app/app.jar
echo "YOUR_SHA" | sudo tee /opt/app/RUNNING_SHA

# Start the service
sudo systemctl start executespec

# Watch the logs to confirm startup
sudo journalctl -u executespec -f
```

Look for `Started ExecutespecApplication in X.X seconds` in the logs. If you see errors,
check Step 13 (Health Check Verification) for troubleshooting.

---

## Step 10: MIG Setup (Scale to 2 Instances)

Once the first VM is confirmed healthy, convert to a Managed Instance Group for HA.

### 10.1 Create an instance template

```bash
# Create a template from the existing VM's disk configuration
gcloud compute instance-templates create executespec-template \
  --machine-type=n2-standard-8 \
  --boot-disk-size=30GB \
  --boot-disk-type=pd-ssd \
  --image-family=debian-12 \
  --image-project=debian-cloud \
  --service-account=executespec-sa@${PROJECT_ID}.iam.gserviceaccount.com \
  --scopes=cloud-platform \
  --no-address \
  --subnet=default \
  --tags=executespec-vm \
  --metadata=enable-oslogin=TRUE \
  --metadata-from-file=startup-script=/opt/app/startup.sh
```

> Note: The startup script in the template handles software installation, secret fetching,
> and service configuration. New instances spun up by the MIG will run this script on
> first boot. Make the script idempotent (the provided script is).

### 10.2 Create the Managed Instance Group

```bash
# Create a health check for autohealing
gcloud compute health-checks create http executespec-health-check \
  --port=8080 \
  --request-path=/actuator/health \
  --check-interval=30s \
  --timeout=10s \
  --healthy-threshold=2 \
  --unhealthy-threshold=3

# Create the MIG with autohealing
gcloud compute instance-groups managed create executespec-mig \
  --template=executespec-template \
  --size=2 \
  --zone=$ZONE \
  --health-checks=executespec-health-check \
  --initial-delay=120
```

### 10.3 Set named port (required by load balancer)

```bash
gcloud compute instance-groups managed set-named-ports executespec-mig \
  --named-ports=http:8080 \
  --zone=$ZONE
```

### 10.4 Verify both instances are healthy

```bash
gcloud compute instance-groups managed list-instances executespec-mig --zone=$ZONE
```

Both instances should show `RUNNING` and `HEALTHY` before proceeding to the LB setup.

---

## Step 11: Load Balancer + HTTPS + SSL

### 11.1 Create a backend service health check

```bash
gcloud compute health-checks create http executespec-lb-health \
  --port=8080 \
  --request-path=/actuator/health \
  --check-interval=30s \
  --timeout=10s \
  --healthy-threshold=1 \
  --unhealthy-threshold=3
```

### 11.2 Create the backend service

```bash
gcloud compute backend-services create executespec-backend \
  --protocol=HTTP \
  --port-name=http \
  --health-checks=executespec-lb-health \
  --global \
  --timeout=600 \
  --connection-draining-timeout=300 \
  --session-affinity=GENERATED_COOKIE \
  --affinity-cookie-ttl=86400
```

> **Why 600s timeout?** ExecuteSpec uses Server-Sent Events (SSE) for live run logs, and
> LLM agent chains can take several minutes. The 600s timeout prevents the LB from killing
> active streams.
>
> **Why cookie affinity?** SSE connections must be sticky to the same VM instance. The
> generated cookie ensures all requests from a browser session route to the same backend.

### 11.3 Add the MIG to the backend service

```bash
gcloud compute backend-services add-backend executespec-backend \
  --instance-group=executespec-mig \
  --instance-group-zone=$ZONE \
  --global \
  --balancing-mode=UTILIZATION \
  --max-utilization=0.8
```

### 11.4 Create URL map

```bash
gcloud compute url-maps create executespec-url-map \
  --default-service=executespec-backend
```

### 11.5 Create a managed SSL certificate

```bash
gcloud compute ssl-certificates create executespec-ssl \
  --domains=YOUR_DOMAIN.com \
  --global
```

> Replace `YOUR_DOMAIN.com` with your actual domain. Certificate provisioning is automatic
> but requires the domain's DNS A record to already point to the LB IP (Step 11.7).
> Provisioning can take up to 24 hours after DNS propagates.

### 11.6 Create HTTPS proxy and forwarding rule

```bash
# HTTPS target proxy
gcloud compute target-https-proxies create executespec-https-proxy \
  --url-map=executespec-url-map \
  --ssl-certificates=executespec-ssl \
  --global

# Reserve a static external IP
gcloud compute addresses create executespec-ip \
  --ip-version=IPV4 \
  --global

LB_IP=$(gcloud compute addresses describe executespec-ip \
  --format='value(address)' --global)
echo "Load Balancer IP: $LB_IP"

# HTTPS forwarding rule (port 443)
gcloud compute forwarding-rules create executespec-https-rule \
  --address=executespec-ip \
  --global \
  --target-https-proxy=executespec-https-proxy \
  --ports=443
```

### 11.7 HTTP → HTTPS redirect

```bash
# Create a URL map that redirects HTTP to HTTPS
gcloud compute url-maps import executespec-http-redirect \
  --global \
  --source /dev/stdin << 'EOF'
name: executespec-http-redirect
defaultUrlRedirect:
  redirectResponseCode: MOVED_PERMANENTLY_DEFAULT
  httpsRedirect: true
EOF

# HTTP proxy
gcloud compute target-http-proxies create executespec-http-proxy \
  --url-map=executespec-http-redirect \
  --global

# HTTP forwarding rule (port 80)
gcloud compute forwarding-rules create executespec-http-rule \
  --address=executespec-ip \
  --global \
  --target-http-proxy=executespec-http-proxy \
  --ports=80
```

### 11.8 Set DNS A record

In your DNS provider's control panel, create:

```
Type:  A
Name:  @  (or subdomain, e.g., "app")
Value: <LB_IP from Step 11.6>
TTL:   300
```

After DNS propagates (~5 minutes for most providers), `curl http://YOUR_DOMAIN` should
redirect to `https://YOUR_DOMAIN` and the managed cert should begin provisioning.

---

## Step 12: CI/CD Setup (GitHub Actions)

The deploy workflow is already in the repository at `.github/workflows/deploy.yml`.
It triggers on every push to `main` and performs:

1. Build the JAR with `mvn package -DskipTests` (frontend is embedded via the build)
2. Upload JAR to `gs://executespec-deploy/releases/app-<SHA>.jar`
3. SSH to the GCE instance via IAP tunnel
4. Symlink the new JAR to `/opt/app/app.jar`
5. Restart the systemd service
6. Wait 20 seconds, run health check
7. Prune old JARs (keep last 10)

### 12.1 Add GitHub Actions secrets

Go to your GitHub repository → Settings → Secrets and variables → Actions → New repository secret.

Add each of the following:

| Secret name | Value |
|---|---|
| `GCP_PROJECT` | Your GCP project ID (e.g., `executespec-prod`) |
| `GCP_REGION` | `us-central1` |
| `GCE_INSTANCE` | `executespec-prod` (or your MIG instance name) |
| `GCE_ZONE` | `us-central1-a` |
| `GCP_SA_KEY` | Full contents of `sa-key.json` (from Step 1.5) |

> If using a MIG, set `GCE_INSTANCE` to one of the MIG instance names. For full MIG
> rolling deploys, a more advanced workflow using `gcloud compute instance-groups managed
> rolling-action replace` is recommended but not covered here.

### 12.2 Trigger the first CI/CD deployment

```bash
# Push to main to trigger the workflow
git push origin main
```

Monitor the deployment in GitHub Actions: repository → Actions tab → "Build & Deploy".

### 12.3 Rollback (one-liner)

```bash
ROLLBACK_SHA=abc123def456   # The commit SHA you want to roll back to

gcloud compute ssh executespec-prod \
  --tunnel-through-iap \
  --zone=$ZONE \
  --command="
    set -e
    ln -sf /opt/app/releases/app-${ROLLBACK_SHA}.jar /opt/app/app.jar
    echo '${ROLLBACK_SHA}' | sudo tee /opt/app/RUNNING_SHA
    sudo systemctl restart executespec
    sleep 20
    curl -sf http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"' \
      && echo 'Rollback successful: ${ROLLBACK_SHA}' \
      || (echo 'Rollback health check FAILED'; exit 1)
  "
```

> Rollback is ~20 seconds. No rebuild required — every JAR is stored permanently by SHA.

---

## Step 13: Health Check Verification

After deployment, run through this checklist to confirm everything is working:

- [ ] `curl -I https://YOUR_DOMAIN/actuator/health` returns HTTP 200
- [ ] Response body is `{"status":"UP"}` (details hidden by `show-details: never`)
- [ ] `curl -I https://YOUR_DOMAIN/api/health` returns HTTP 200 (Spring Boot health endpoint)
- [ ] `https://YOUR_DOMAIN` loads the React SPA (white screen or login page — not a 404)
- [ ] Login with `admin@executespec.ai` and the admin password succeeds and returns a JWT
- [ ] After login, the Runs list page loads without 401 errors
- [ ] Submit a test run (simple spec) and verify it transitions: PENDING → PLANNING → AWAITING_APPROVAL
- [ ] Approve the plan and verify workers start (RUNNING)
- [ ] Run detail page shows live SSE events (the log panel updates in real time)
- [ ] After workers complete: AWAITING_MERGE gate appears
- [ ] Merge the run and verify it transitions to COMPLETED
- [ ] GCS bucket `executespec-workspaces` contains an archived workspace zip after ~24h
- [ ] LB health check console: both backend instances show HEALTHY (GCP Console → Network services → Load balancing → executespec-backend)
- [ ] Cloud Logging shows no `ERROR` level entries from `executespec` during startup
- [ ] `gcloud compute ssh executespec-prod --tunnel-through-iap --zone=$ZONE --command="cat /opt/app/RUNNING_SHA"` returns the expected SHA

**Check logs for startup errors:**

```bash
gcloud compute ssh executespec-prod \
  --tunnel-through-iap \
  --zone=$ZONE \
  --command="sudo journalctl -u executespec -n 200 --no-pager"
```

**Cloud Logging filter:**

```
resource.type="gce_instance"
logName="projects/YOUR_PROJECT/logs/executespec"
severity>=ERROR
```

---

## Step 14: Rollback Procedure

### List available releases

```bash
gsutil ls gs://executespec-deploy/releases/
```

### Check what's currently running

```bash
gcloud compute ssh executespec-prod \
  --tunnel-through-iap \
  --zone=$ZONE \
  --command="cat /opt/app/RUNNING_SHA"
```

### Roll back to a specific SHA

```bash
ROLLBACK_SHA=abc123def456789...

gcloud compute ssh executespec-prod \
  --tunnel-through-iap \
  --zone=$ZONE \
  --command="
    ln -sf /opt/app/releases/app-${ROLLBACK_SHA}.jar /opt/app/app.jar \
    && echo '${ROLLBACK_SHA}' | sudo tee /opt/app/RUNNING_SHA \
    && sudo systemctl restart executespec \
    && echo '${ROLLBACK_SHA}' | sudo tee /opt/app/RUNNING_SHA
  "
```

> Rollback completes in ~20 seconds. No rebuild is needed. Every JAR that has ever been
> deployed is stored in GCS by its commit SHA and can be rolled back to instantly.

---

## Step 15: Ongoing Operations

### Check what is currently deployed

```bash
gcloud compute ssh executespec-prod \
  --tunnel-through-iap \
  --zone=$ZONE \
  --command="cat /opt/app/RUNNING_SHA"
```

### View live application logs

```bash
gcloud compute ssh executespec-prod \
  --tunnel-through-iap \
  --zone=$ZONE \
  --command="sudo journalctl -u executespec -f"
```

### View logs in Cloud Logging

```bash
gcloud logging read \
  'resource.type="gce_instance" AND logName:"executespec"' \
  --project=$PROJECT_ID \
  --limit=100 \
  --format=json
```

### Restart the service (without deploying a new JAR)

```bash
gcloud compute ssh executespec-prod \
  --tunnel-through-iap \
  --zone=$ZONE \
  --command="sudo systemctl restart executespec"
```

### Database backups

Automated daily backups are configured (`--backup-start-time=03:00`, 7 retained).

List backups:

```bash
gcloud sql backups list --instance=executespec-pg
```

Restore from a backup:

```bash
gcloud sql backups restore BACKUP_ID \
  --restore-instance=executespec-pg \
  --backup-instance=executespec-pg
```

### GCS workspace cleanup

`WorkspaceArchiveJob` runs hourly (cron `0 * * * * *`) and archives all completed run
workspaces older than 24 hours to `gs://executespec-workspaces`. Local workspace
directories are deleted after archiving to reclaim disk space on the VM's data disk.

The `executespec-deploy` bucket has a 30-day lifecycle policy (Step 4.3), and the
GitHub Actions workflow keeps only the last 10 JARs.

### Scale the MIG up or down

```bash
gcloud compute instance-groups managed resize executespec-mig \
  --size=3 \
  --zone=$ZONE
```

### Update the instance template (e.g., machine type change)

```bash
gcloud compute instance-templates create executespec-template-v2 \
  --machine-type=n2-standard-8 \
  # ... (same flags as original template)

gcloud compute instance-groups managed set-instance-template executespec-mig \
  --template=executespec-template-v2 \
  --zone=$ZONE

# Rolling replace (replaces instances one at a time)
gcloud compute instance-groups managed rolling-action replace executespec-mig \
  --zone=$ZONE \
  --max-unavailable=1
```

---

## Appendix: Environment Variable Reference

The following environment variables are consumed by the application. All are fetched from
Secret Manager by `startup.sh` at boot time.

| Variable | Required | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | Yes | Primary LLM provider (Planner, Worker, Reviewer) |
| `OPENAI_API_KEY` | Yes | Fallback LLM provider |
| `GOOGLE_API_KEY` | No | Third-tier Gemini fallback |
| `POSTGRES_HOST` | Yes | Cloud SQL private IP |
| `POSTGRES_PORT` | No | Default: 5432 |
| `POSTGRES_DB` | Yes | Database name (`executespec`) |
| `POSTGRES_USER` | Yes | Database user |
| `POSTGRES_PASSWORD` | Yes | Database password |
| `GITHUB_PAT` | Yes | GitHub PAT for pushing generated code |
| `GITHUB_OWNER` | Yes | GitHub org/user for default repo target |
| `GCS_WORKSPACE_BUCKET` | Yes | GCS bucket for workspace archives |
| `JWT_SECRET` | Yes | HS256 signing key (min 256 bits) |
| `REDIS_HOST` | No | Memorystore Redis host (if Redis integration enabled) |
| `GCP_PROJECT` | Yes | GCP project ID (used by DeploymentService) |
| `GCP_REGION` | Yes | GCP region (used by DeploymentService / Cloud Run) |
| `APP_BASE_URL` | Yes | Public HTTPS URL (used in email links) |
| `CORS_ALLOWED_ORIGINS` | Yes | Allowed CORS origin (should match `APP_BASE_URL`) |
| `GMAIL_ENABLED` | No | `true` to enable Gmail OAuth email sending |
| `GMAIL_SENDER_EMAIL` | No | Gmail address for outbound emails |
| `SLACK_CLIENT_ID` | No | Slack app client ID (for OAuth) |
| `SLACK_CLIENT_SECRET` | No | Slack app client secret |
| `SLACK_SIGNING_SECRET` | No | Slack app signing secret (for request verification) |
| `SLACK_REDIRECT_URI` | No | OAuth callback URL (default: `http://localhost:8080/api/integrations/slack/oauth/callback`) |
| `DASHBOARD_URL` | No | Public dashboard URL for Slack message action buttons (default: `http://localhost:8080`) |

---

## Appendix: Troubleshooting

### Application fails to start — "Connection refused" to PostgreSQL

1. Verify Cloud SQL private IP is reachable from the VM: `telnet $POSTGRES_HOST 5432`
2. Ensure the VM's service account has `roles/cloudsql.client`
3. Check that both VM and Cloud SQL are on the same VPC (`default`)
4. Verify `POSTGRES_HOST` secret has the correct private IP (not the public IP)

### LLM API calls fail with 401 or 403

1. SSH into the VM and run `echo $ANTHROPIC_API_KEY` — if empty, secret fetch failed
2. Check the service account has `roles/secretmanager.secretAccessor` on the specific secrets
3. Verify the secret names exactly match what `startup.sh` expects (case-sensitive)

### SSE stream disconnects after ~60 seconds

1. The LB timeout must be 600s — verify: `gcloud compute backend-services describe executespec-backend --global | grep timeoutSec`
2. Cookie affinity must be enabled — verify: `gcloud compute backend-services describe executespec-backend --global | grep sessionAffinity`

### Health check shows instances UNHEALTHY

1. Check the health check source ranges firewall rule (`allow-lb-health-check`)
2. Verify the app is listening on port 8080: `sudo ss -tlnp | grep 8080`
3. Check startup logs: `sudo journalctl -u executespec -n 50 --no-pager`
4. Verify `/actuator/health` is exposed (check `application.yml`: `management.endpoints.web.exposure.include: health`)

### Out of disk space on data disk

```bash
# Check disk usage
df -h /data

# The workspace archive job should be cleaning up, check if it's running
sudo journalctl -u executespec | grep -i "WorkspaceArchive"

# Manually trigger cleanup: hit the GCS archive endpoint or clear old workspaces
sudo find /data/workspace -maxdepth 1 -type d -mtime +7 -exec rm -rf {} +
```

### Admin password not working

Default admin email: `admin@executespec.ai` (password set by Flyway V7 migration)

These are seeded by Flyway migration V5 (initial seed) and corrected by V7 (bcrypt hash fix).
If login fails, check that Flyway migrations completed successfully in the startup logs:

```
sudo journalctl -u executespec | grep -i flyway
```

---

## Terraform Operations (infra/)

> Previously maintained in `infra/SETUP.md` — consolidated here.

### Prerequisites

1. **Terraform >= 1.5**: `brew install terraform`
2. **gcloud CLI**: `brew install --cask google-cloud-sdk`
3. **GCP Project** `executespecv1` with billing enabled
4. **Cloudflare** managing DNS for `executespec.ai`

### One-Time Setup

```bash
gcloud auth application-default login
gcloud config set project executespecv1

# Create Terraform state bucket
gsutil mb -l us-east4 gs://executespec-tfstate
gsutil versioning set on gs://executespec-tfstate

# Initialize & create workspaces
cd infra
terraform init
terraform workspace new dev
terraform workspace new prod
```

### Deploy Dev Environment

**Subdomain:** `dev.executespec.ai` | **Region:** `us-east4`

```bash
terraform workspace select dev
terraform plan  -var-file=environments/dev.tfvars -var="project_id=executespecv1"
terraform apply -var-file=environments/dev.tfvars -var="project_id=executespecv1"
```

**DNS (Cloudflare):** A record → `dev` → `$(terraform output lb_ip)` (DNS only until SSL is ACTIVE)

**Secrets → GCS:**
```bash
cat > /tmp/api-keys << 'EOF'
ANTHROPIC_API_KEY=sk-ant-...
OPENAI_API_KEY=sk-proj-...
GOOGLE_API_KEY=AIza...
GITHUB_PAT=ghp_...
EOF
gsutil cp /tmp/api-keys gs://executespec-dev-deploy/config/api-keys && rm /tmp/api-keys
```

**GitHub environment secrets (`development`):**

| Secret | Value |
|--------|-------|
| `GCP_PROJECT` | `executespecv1` |
| `GCP_REGION` | `us-east4` |
| `GCE_INSTANCE` | `<from gcloud compute instances list>` |
| `GCE_ZONE` | `us-east4-a` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github-pool/providers/github-provider` |
| `GCP_SERVICE_ACCOUNT` | `executespec-dev-gce@executespecv1.iam.gserviceaccount.com` |

Deploy: `git push -u origin dev`
Verify: `https://dev.executespec.ai/api/health`

### Deploy Prod Environment

**Subdomain:** `app.executespec.ai` | **Region:** `us-east4`

#### Step 1: Provision Infrastructure

```bash
terraform workspace select prod
terraform plan  -var-file=environments/prod.tfvars -var="project_id=executespecv1"
terraform apply -var-file=environments/prod.tfvars -var="project_id=executespecv1"
```

#### Step 2: DNS (Cloudflare)

| Record | Type | Name | Content | Proxy |
|--------|------|------|---------|-------|
| Main app | A | `app` | `$(terraform output lb_ip)` | Proxied (orange) |
| Preview wildcard | A | `*` | `<VM external IP>` | DNS only (grey)¹ |

¹ Wildcard proxy requires Cloudflare Enterprise. Previews use Cloud Run URLs on prod (no wildcard DNS needed).

**SSL/TLS Mode:** Full (not Full Strict) — uses Cloudflare Origin Certificate on origin.

#### Step 3: Secrets (GCS + Secret Manager)

```bash
# API keys → GCS (fetched by startup script)
cat > /tmp/api-keys << 'EOF'
ANTHROPIC_API_KEY=sk-ant-...
OPENAI_API_KEY=sk-proj-...
GOOGLE_API_KEY=AIza...
GITHUB_PAT=ghp_...
EOF
gsutil cp /tmp/api-keys gs://executespec-prod-deploy/config/api-keys && rm /tmp/api-keys

# Billing & integrations → Secret Manager (or GCS)
# RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET — Razorpay dashboard → API Keys
# SLACK_CLIENT_ID, SLACK_CLIENT_SECRET, SLACK_SIGNING_SECRET — Slack App settings
# SMTP_PASSWORD — email provider SMTP credentials
```

#### Step 4: Service Account Permissions

The prod VM service account needs:

```bash
SA_EMAIL="executespec-prod-gce@executespecv1.iam.gserviceaccount.com"

# Required roles
for ROLE in \
  roles/run.admin \
  roles/artifactregistry.writer \
  roles/artifactregistry.repoAdmin \
  roles/iam.serviceAccountUser \
  roles/logging.logWriter \
  roles/monitoring.metricWriter \
  roles/secretmanager.secretAccessor \
  roles/cloudsql.client; do
  gcloud projects add-iam-policy-binding executespecv1 \
    --member="serviceAccount:$SA_EMAIL" --role="$ROLE"
done
```

#### Step 5: GitHub Environment Secrets (`production`)

| Secret | Value |
|--------|-------|
| `GCP_PROJECT` | `executespecv1` |
| `GCP_REGION` | `us-east4` |
| `GCE_INSTANCE` | `executespec-prod-vm` |
| `GCE_ZONE` | `us-east4-a` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/<PROJECT_NUMBER>/locations/global/workloadIdentityPools/github-pool/providers/github-provider` |
| `GCP_SERVICE_ACCOUNT` | `executespec-prod-gce@executespecv1.iam.gserviceaccount.com` |
| `SLACK_WEBHOOK_URL` | Slack incoming webhook for deploy notifications |

#### Step 6: Application Config (application-prod.yml)

Create `src/main/resources/application-prod.yml` if not present:

```yaml
# Preview: Cloud Run mode on prod
preview:
  mode: cloud-run
  preview-ttl-minutes: 5
  proxy:
    enabled: false

# Observability: GCP exporters
spring:
  profiles:
    include: gcp
```

#### Step 7: Deploy

```bash
git push origin main    # triggers GitHub Actions → build → deploy to prod VM
```

#### Step 8: Verify

```bash
# Health check
curl https://app.executespec.ai/api/health

# Login
curl -s -X POST https://app.executespec.ai/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@executespec.ai","password":"REDACTED_FOR_PUBLIC_RELEASE"}' | jq .token

# Flyway migrations
gcloud compute ssh executespec-prod-vm --zone=us-east4-a --tunnel-through-iap \
  -- 'sudo journalctl -u executespec | grep -i "flyway\|migration" | tail -20'

# Preview test (start a run, wait for COMPLETE, then start preview)
# Preview URL will be a *.run.app Cloud Run URL

# Cloud Run logs for preview debugging
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=es-preview-XXXXXXXX" \
  --limit=50 --format="value(textPayload)" --freshness=10m --order=asc
```

> **Reliability notes:**
> - All `gcloud` and `docker` subprocess calls have timeouts: deploy/push = 10min, delete/rollback = 5min, list = 2min
> - Stdout and stderr are read in parallel (via `CompletableFuture`) to prevent pipe buffer deadlock
> - Virtual threads for preview/deploy use `ContextSnapshotFactory.captureAll().wrap()` for trace propagation
> - `ACTIVE_PREVIEWS` gauge is decremented on ALL terminal states (FAILED, TIMED_OUT, STOPPED)
> - Cloud Run resources (service + AR image) are cleaned up on preview failure
> - API docs probing works for both Docker (`localhost:port`) and Cloud Run (service URL)

#### Pre-Production Checklist

- [ ] **Infrastructure:** Terraform apply successful, all resources provisioned
- [ ] **DNS:** `app.executespec.ai` resolves to LB IP, SSL certificate ACTIVE
- [ ] **Database:** Cloud SQL accessible, Flyway V1–V39 migrations complete
- [ ] **Redis:** Memorystore accessible from VPC
- [ ] **Secrets:** All API keys in GCS/Secret Manager (Anthropic, OpenAI, Google, GitHub)
- [ ] **Billing:** Razorpay webhook URL → `https://app.executespec.ai/api/billing/webhook`
- [ ] **Slack:** App installed to workspace, OAuth redirect → `https://app.executespec.ai/api/integrations/slack/oauth/callback`
- [ ] **Monitoring:** Cloud Monitoring alerts for 5xx rate, latency > 5s, instance health
- [ ] **IAM:** Service account has all required roles (run.admin, artifactregistry.*, etc.)
- [ ] **Health check:** `/api/health` returns UP
- [ ] **Auth:** Admin login works, forced password change on first login
- [ ] **Pipeline test:** Submit a run, approve plan, verify COMPLETE status
- [ ] **Preview test:** Start preview, verify Cloud Run URL accessible
- [ ] **Rollback plan:** Previous JAR in GCS, can redeploy via SHA

### Useful Terraform Commands

```bash
terraform workspace show                    # current workspace
terraform output -raw db_private_ip         # DB IP
terraform output -raw lb_ip                 # LB IP
terraform output -raw db_password           # DB password (sensitive)
gcloud compute instances list --project=executespecv1 --filter="name~executespec-dev"
gcloud compute ssh <INSTANCE> --zone=us-east4-a --tunnel-through-iap
sudo journalctl -u executespec -f           # app logs on VM
```

### Infrastructure Cost Estimates

| Resource | Dev | Prod |
|----------|-----|------|
| Compute | $49/mo (e2-standard-2 ×1) | $388/mo (n2-standard-8 ×2) |
| Cloud SQL | $25/mo (db-g1-small) | $80/mo (db-custom-2-3840 HA) |
| Redis | $36/mo (1GB Basic) | $73/mo (1GB Standard HA) |
| Load Balancer | $18/mo | $18/mo |
| Cloud NAT + GCS | $4/mo | $8/mo |
| **Total** | **~$132/mo** | **~$373/mo** |
