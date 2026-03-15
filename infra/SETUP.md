# Infrastructure Setup Guide

## Prerequisites

1. **Terraform >= 1.5**: `brew install terraform`
2. **gcloud CLI**: `brew install --cask google-cloud-sdk`
3. **GCP Project** `executespecv1` with billing enabled
4. **Cloudflare** managing DNS for `executespec.ai`

## One-Time Setup

### 1. Authenticate to GCP

```bash
gcloud auth application-default login
gcloud config set project executespecv1
```

### 2. Create the Terraform state bucket

```bash
gsutil mb -l asia-south1 gs://executespec-tfstate
gsutil versioning set on gs://executespec-tfstate
```

### 3. Initialize Terraform

```bash
cd infra
terraform init
```

### 4. Create workspaces

```bash
terraform workspace new dev
terraform workspace new prod
```

---

## Deploy Dev Environment

**Subdomain:** `dev.executespec.ai`
**Region:** `asia-south1` (Mumbai)

### 1. Provision infrastructure

```bash
terraform workspace select dev
terraform plan  -var-file=environments/dev.tfvars -var="project_id=executespecv1"
terraform apply -var-file=environments/dev.tfvars -var="project_id=executespecv1"
```

### 2. Point DNS (Cloudflare)

Get the Load Balancer IP:

```bash
terraform output lb_ip
```

In **Cloudflare Dashboard** → `executespec.ai` → **DNS Records**:

| Type | Name  | Content        | Proxy status          |
|------|-------|----------------|-----------------------|
| A    | `dev` | `<lb_ip>`      | **DNS only** (gray cloud) |

> **Important:** Keep proxy OFF (gray cloud) until Google's managed SSL cert becomes `ACTIVE`.
> Google needs to reach the LB directly to verify domain ownership.

Check SSL cert status:

```bash
gcloud compute ssl-certificates list --project=executespecv1
```

Once status is `ACTIVE` (5-15 min), you can optionally flip Cloudflare proxy to orange cloud
and set SSL/TLS mode to **Full (strict)**.

### 3. Upload application secrets to GCS

API keys are stored in GCS and loaded automatically by the startup script on every boot.
This ensures keys survive VM reboots, MIG recreations, and auto-healer events.

```bash
# Create the api-keys file locally
cat > /tmp/api-keys << 'EOF'
ANTHROPIC_API_KEY=sk-ant-...
OPENAI_API_KEY=sk-proj-...
GOOGLE_API_KEY=AIza...
GITHUB_PAT=ghp_...
EOF

# Upload to the dev deploy bucket
gsutil cp /tmp/api-keys gs://executespec-dev-deploy/config/api-keys
rm /tmp/api-keys
```

The startup script reads `gs://<DEPLOY_BUCKET>/config/api-keys` and writes
the values into `/opt/app/executespec.env` before starting the application.

To verify keys are loaded, SSH into the VM and check:

```bash
gcloud compute instances list --project=executespecv1 --filter="name~executespec-dev" --format="value(name)"
gcloud compute ssh <INSTANCE_NAME> --zone=asia-south1-a --tunnel-through-iap --project=executespecv1
sudo grep API_KEY /opt/app/executespec.env | cut -c1-30   # verify prefix only
```

### 4. Set GitHub secrets

Create a GitHub environment called `development` with these secrets:

| Secret         | Value                                |
|----------------|--------------------------------------|
| `GCP_PROJECT`  | `executespecv1`                      |
| `GCP_REGION`   | `asia-south1`                        |
| `GCE_INSTANCE` | `<from instances list above>`        |
| `GCE_ZONE`     | `asia-south1-a`                      |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github-pool/providers/github-provider` |
| `GCP_SERVICE_ACCOUNT` | `executespec-dev-gce@executespecv1.iam.gserviceaccount.com` |

### 5. Deploy

```bash
git push -u origin dev    # → triggers GitHub Actions → deploys to dev
```

### 6. Verify

```
https://dev.executespec.ai                  → app loads
https://dev.executespec.ai/api/health  → {"status":"UP"}
```

---

## Deploy Prod Environment

**Subdomain:** `app.executespec.ai`
**Region:** `asia-south1` (Mumbai)

### 1. Provision infrastructure

```bash
terraform workspace select prod
terraform plan  -var-file=environments/prod.tfvars -var="project_id=executespecv1"
terraform apply -var-file=environments/prod.tfvars -var="project_id=executespecv1"
```

### 2. Point DNS (Cloudflare)

Get the Load Balancer IP:

```bash
terraform output lb_ip
```

In **Cloudflare Dashboard** → `executespec.ai` → **DNS Records**:

| Type | Name  | Content        | Proxy status          |
|------|-------|----------------|-----------------------|
| A    | `app` | `<lb_ip>`      | **DNS only** (gray cloud) |

> Same SSL provisioning process as dev — wait for `ACTIVE`, then optionally enable Cloudflare proxy.

### 3. Upload application secrets to GCS

```bash
cat > /tmp/api-keys << 'EOF'
ANTHROPIC_API_KEY=sk-ant-...
OPENAI_API_KEY=sk-proj-...
GOOGLE_API_KEY=AIza...
GITHUB_PAT=ghp_...
RAZORPAY_KEY_ID=rzp_...
RAZORPAY_KEY_SECRET=...
SLACK_CLIENT_ID=...
SLACK_CLIENT_SECRET=...
SLACK_SIGNING_SECRET=...
EOF

gsutil cp /tmp/api-keys gs://executespec-prod-deploy/config/api-keys
rm /tmp/api-keys
```

See the dev section above for how verification works.

### 4. Set GitHub secrets

Create a GitHub environment called `production` with these secrets:

| Secret         | Value                                |
|----------------|--------------------------------------|
| `GCP_PROJECT`  | `executespecv1`                      |
| `GCP_REGION`   | `asia-south1`                        |
| `GCE_INSTANCE` | `<from instances list above>`        |
| `GCE_ZONE`     | `asia-south1-a`                      |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github-pool/providers/github-provider` |
| `GCP_SERVICE_ACCOUNT` | `executespec-dev-gce@executespecv1.iam.gserviceaccount.com` |

### 5. Deploy

```bash
git push origin main    # → triggers GitHub Actions → deploys to prod
```

### 6. Verify

```
https://app.executespec.ai                  → app loads
https://app.executespec.ai/api/health  → {"status":"UP"}
```

---

## Useful Commands

```bash
# Check current workspace
terraform workspace show

# Switch workspace
terraform workspace select dev

# View all outputs
terraform output

# View specific output
terraform output -raw db_private_ip
terraform output -raw lb_ip

# View DB password (sensitive)
terraform output -raw db_password

# List dev instances (MIG adds random suffix)
gcloud compute instances list --project=executespecv1 --filter="name~executespec-dev"

# SSH into dev VM
gcloud compute ssh <INSTANCE_NAME> --zone=asia-south1-a --tunnel-through-iap

# List prod instances
gcloud compute instances list --project=executespecv1 --filter="name~executespec-prod"

# SSH into prod VM
gcloud compute ssh <INSTANCE_NAME> --zone=asia-south1-a --tunnel-through-iap

# Check app logs on VM
sudo journalctl -u executespec -f

# Check SSL cert status
gcloud compute ssl-certificates list --project=executespecv1

# Destroy dev environment (careful!)
terraform workspace select dev
terraform destroy -var-file=environments/dev.tfvars -var="project_id=executespecv1"
```

## CI/CD Flow

```
Push to dev branch  → GitHub Actions → builds JAR → deploys to dev VM
Push to main branch → GitHub Actions → builds JAR → deploys to prod VM
```

Each environment uses its own GitHub environment secrets, GCS deploy bucket, and GCE instance.

## Cost Estimates

| Resource | Dev (asia-south1) | Prod (asia-south1) |
|----------|-------------------|---------------------|
| Compute | $49/mo (e2-standard-2 ×1) | $194/mo (n2-standard-4 ×2) |
| Cloud SQL | $25/mo (db-g1-small) | $80/mo (db-custom-2-3840 HA) |
| Redis | $36/mo (1GB Basic) | $73/mo (1GB Standard HA) |
| Load Balancer | $18/mo | $18/mo |
| Cloud NAT | $3/mo | $3/mo |
| GCS | $1/mo | $5/mo |
| **Total** | **~$132/mo** | **~$373/mo** |

## Architecture

```
Internet → Cloudflare DNS → HTTPS Load Balancer (Google managed SSL)
  ↓
GCE MIG (1-2 instances, auto-healing)
  ↓ (private network, asia-south1)
├── Cloud SQL PostgreSQL 15 (private IP, daily backups)
├── Memorystore Redis 7.0 (private access)
└── Cloud NAT (outbound for LLM API calls)

Storage:
├── GCS: executespec-{env}-deploy (JAR releases)
├── GCS: executespec-{env}-workspaces (workspace archives)
└── Artifact Registry: Docker images

Environments:
├── dev  → dev.executespec.ai  (1 instance, no HA)
└── prod → app.executespec.ai  (2 instances, HA database + redis)
```
