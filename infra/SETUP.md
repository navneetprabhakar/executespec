# Infrastructure Setup Guide

## Prerequisites

1. **Terraform >= 1.5**: `brew install terraform`
2. **gcloud CLI**: `brew install --cask google-cloud-sdk`
3. **GCP Project** with billing enabled

## One-Time Setup

### 1. Authenticate to GCP

```bash
gcloud auth application-default login
gcloud config set project YOUR_PROJECT_ID
```

### 2. Create the Terraform state bucket

```bash
gsutil mb -l us-central1 gs://executespec-tfstate
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

## Deploy an Environment

### Development

```bash
terraform workspace select dev
terraform plan -var-file=environments/dev.tfvars -var="project_id=YOUR_PROJECT_ID"
terraform apply -var-file=environments/dev.tfvars -var="project_id=YOUR_PROJECT_ID"
```

### Production

```bash
terraform workspace select prod
terraform plan -var-file=environments/prod.tfvars -var="project_id=YOUR_PROJECT_ID"
terraform apply -var-file=environments/prod.tfvars -var="project_id=YOUR_PROJECT_ID"
```

## Post-Apply Steps

### 1. Point DNS to the Load Balancer

After `terraform apply`, get the LB IP:

```bash
terraform output lb_ip
```

Create an A record in your DNS provider:
- **Dev**: `dev.executespec.ai → <lb_ip>`
- **Prod**: `app.executespec.ai → <lb_ip>`

SSL certificate auto-provisions once DNS propagates (5-15 minutes).

### 2. Set GitHub Secrets

For each environment (`development` and `production`), set these GitHub environment secrets:

| Secret | Value |
|--------|-------|
| `GCP_PROJECT` | Your GCP project ID |
| `GCP_REGION` | `us-central1` |
| `GCE_INSTANCE` | `terraform output instance_name` |
| `GCE_ZONE` | `us-central1-a` |
| `GCP_SA_KEY` | Service account JSON key |

### 3. Set Application Secrets on the VM

SSH into the instance and add API keys to the environment file:

```bash
gcloud compute ssh $(terraform output -raw instance_name) \
  --zone=$(terraform output -raw instance_zone) \
  --tunnel-through-iap

# Edit the env file
sudo vi /opt/app/executespec.env

# Add these lines:
# ANTHROPIC_API_KEY=sk-ant-...
# OPENAI_API_KEY=sk-...
# GOOGLE_API_KEY=AIza...
# GITHUB_PAT=ghp_...

# Restart
sudo systemctl restart executespec
```

### 4. First Deployment

Push to the `dev` branch to trigger dev deployment, or `main` for production:

```bash
git push origin dev    # → deploys to dev environment
git push origin main   # → deploys to prod environment
```

## Useful Commands

```bash
# Check current workspace
terraform workspace show

# View outputs
terraform output

# View specific output
terraform output -raw db_private_ip

# View DB password (sensitive)
terraform output -raw db_password

# Destroy dev environment (careful!)
terraform workspace select dev
terraform destroy -var-file=environments/dev.tfvars -var="project_id=YOUR_PROJECT_ID"
```

## Cost Estimates

| Resource | Dev | Prod |
|----------|-----|------|
| Compute | $49/mo (e2-standard-2 ×1) | $194/mo (n2-standard-4 ×2) |
| Cloud SQL | $25/mo (db-g1-small) | $80/mo (db-custom-2-3840 HA) |
| Redis | $36/mo (1GB Basic) | $73/mo (1GB Standard HA) |
| Load Balancer | $18/mo | $18/mo |
| Cloud NAT | $3/mo | $3/mo |
| GCS | $1/mo | $5/mo |
| **Total** | **~$132/mo** | **~$373/mo** |

## Architecture

```
Internet → HTTPS Load Balancer (managed SSL)
  ↓
GCE MIG (1-2 instances, auto-healing)
  ↓ (private network)
├── Cloud SQL PostgreSQL (private IP, daily backups)
├── Memorystore Redis (private access)
└── Cloud NAT (outbound for LLM API calls)

Storage:
├── GCS: {env}-deploy (JAR releases)
├── GCS: {env}-workspaces (workspace archives)
└── Artifact Registry: Docker images
```
