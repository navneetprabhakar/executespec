# Preview Engine

VM-based preview system for ExecuteSpec. Runs generated projects as Docker containers behind a Caddy reverse proxy on GCE VMs.

## Architecture

```
                        ┌─────────────────────────────────────────┐
                        │  App VM (executespec-dev-vm)            │
                        │  - PipelineHarness completes run        │
                        │  - PreviewService triggers deploy       │
                        │  - SCP project → preview-gw             │
                        │  - SSH deploy-preview.sh                │
                        └──────────────┬──────────────────────────┘
                                       │ SSH + SCP
                        ┌──────────────▼──────────────────────────┐
                        │  Preview GW VM (preview-gw)             │
                        │  ┌──────────────────────────────────┐   │
                        │  │ Caddy (caddy-proxy container)    │   │
                        │  │ - Wildcard *.preview.executespec  │   │
                        │  │ - Per-preview reverse proxy       │   │
                        │  └────────────┬─────────────────────┘   │
                        │               │                         │
                        │  ┌────────────▼─────────────────────┐   │
                        │  │ Preview containers (Docker)       │   │
                        │  │ - preview-{id} on dynamic port    │   │
                        │  │ - Isolated, 512MB mem, 1 CPU      │   │
                        │  │ - Auto-cleanup after TTL          │   │
                        │  └──────────────────────────────────┘   │
                        └─────────────────────────────────────────┘
```

## Parallel Deploy Support

Deploys run **fully in parallel**. Each preview gets:
- **Per-preview lock** (`/tmp/preview-lock-{id}.lock`) — prevents double-deploy of the same preview only
- **Isolated port** — randomly allocated from 10000-60000
- **Independent Docker build** — no shared resources

Only the **Caddy reload** is globally serialized via `/tmp/caddy-reload.lock` (sub-second operation).

## Scripts

| Script | Purpose |
|--------|---------|
| `deploy-preview.sh` | Build + start preview container, configure Caddy, health check |
| `teardown-preview.sh` | Stop containers, remove Caddy config, clean up files |
| `write-caddy-config.sh` | Add Caddy reverse proxy entry (for multi-VM worker routing) |
| `extend-preview.sh` | Extend a preview's TTL |
| `cleanup-expired.sh` | Cron job to clean up expired previews (safety net) |
| `setup-preview-vm.sh` | One-time VM setup: Docker, Caddy, directory structure |

## Setup (New VM)

### 1. Create GCE VM

```bash
gcloud compute instances create preview-gw \
    --zone=us-east4-b \
    --machine-type=e2-standard-4 \
    --boot-disk-size=100GB \
    --image-family=ubuntu-2204-lts \
    --image-project=ubuntu-os-cloud \
    --tags=preview-vm
```

### 2. Firewall Rules

```bash
# Allow HTTPS from internet (Caddy handles TLS)
gcloud compute firewall-rules create allow-preview-https \
    --allow=tcp:443 \
    --target-tags=preview-vm \
    --source-ranges=0.0.0.0/0

# Allow SSH from app VM (internal network)
gcloud compute firewall-rules create allow-preview-ssh-internal \
    --allow=tcp:22 \
    --target-tags=preview-vm \
    --source-tags=executespec-app
```

### 3. DNS

Add wildcard DNS record:
```
*.preview.executespec.ai  →  A  →  <preview-gw external IP>
```

### 4. SSH Key Setup

On the **app VM**:
```bash
# Generate SSH key for preview deployments
ssh-keygen -t ed25519 -f /opt/app/preview-ssh-key -N "" -C "executespec-preview"

# Copy public key to preview-gw
gcloud compute ssh preview-gw --zone=us-east4-b -- \
    "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys" < /opt/app/preview-ssh-key.pub
```

### 5. Install Preview Engine

```bash
# SCP scripts to preview-gw
scp -r infra/preview-engine/bin/ user@preview-gw:/tmp/preview-setup/

# Run setup on preview-gw
ssh user@preview-gw 'sudo bash /tmp/preview-setup/setup-preview-vm.sh'
```

### 6. Configure App VM

Add to `/opt/app/executespec.env`:
```bash
PREVIEW_MODE=vm
PREVIEW_GATEWAY_IP=<preview-gw external IP>
PREVIEW_GW_INTERNAL_IP=<preview-gw internal IP>
PREVIEW_SSH_KEY_PATH=/opt/app/preview-ssh-key
PREVIEW_SSH_USER=<ssh username on preview-gw>
```

Restart the app:
```bash
sudo systemctl restart executespec
```

## Updating Scripts

After modifying scripts in this directory:

```bash
# SCP updated scripts to preview-gw
scp infra/preview-engine/bin/*.sh user@preview-gw:/opt/preview-engine/bin/

# Set permissions
ssh user@preview-gw 'chmod +x /opt/preview-engine/bin/*.sh'
```

## Troubleshooting

```bash
# Check running previews
docker ps --filter "name=preview-"

# Check Caddy config
docker exec caddy-proxy cat /etc/caddy/Caddyfile
ls /opt/preview-engine/caddy/sites/

# Check deploy logs
journalctl -u executespec | grep "preview"

# Manual teardown
/opt/preview-engine/bin/teardown-preview.sh <preview-id>

# Check for stale locks
ls /tmp/preview-lock-*.lock /tmp/caddy-reload.lock 2>/dev/null

# Clear stale lock (if deploy process died)
rm -f /tmp/preview-lock-<preview-id>.lock
```
