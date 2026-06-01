#!/usr/bin/env bash
# Benchmark VM bootstrap — installs the SWE-bench eval stack so the VM is ready
# to clone the repo, run the ExecuteSpec backend, and drive run-core-codegen.sh.
# Idempotent-ish; logs to /var/log/benchmark-bootstrap.log; on success touches
# /var/log/benchmark-ready.
set -euxo pipefail
exec > >(tee -a /var/log/benchmark-bootstrap.log) 2>&1

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl gnupg git jq unzip wget build-essential apt-transport-https lsof
install -m 0755 -d /etc/apt/keyrings
. /etc/os-release

# ── Docker (cleanroom containers + official SWE-bench scorer) ────────────────
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
  > /etc/apt/sources.list.d/docker.list

# ── Java 21 (Temurin) for the ExecuteSpec backend ────────────────────────────
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb ${VERSION_CODENAME} main" \
  > /etc/apt/sources.list.d/adoptium.list

apt-get update
apt-get install -y \
  docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin \
  temurin-21-jdk maven \
  python3 python3-pip python3-venv \
  postgresql postgresql-contrib redis-server

systemctl enable --now docker postgresql redis-server

# ── SWE-bench harness (official scorer) ──────────────────────────────────────
pip3 install --upgrade pip
pip3 install swebench datasets || true

# ── Postgres + DB for the backend (matches local dev: db=executespec, postgres/postgres) ──
sudo -u postgres psql -c "ALTER USER postgres PASSWORD 'postgres';" || true
sudo -u postgres createdb executespec || true

# ── docker group for OS Login users (dynamic username) ───────────────────────
groupadd -f docker
cat > /etc/profile.d/99-docker-group.sh <<'EOF'
# Add the interactive user to the docker group on first login so `docker` works
# without sudo (OS Login usernames are dynamic, so we can't do this at bake time).
if [ -n "${USER:-}" ] && ! id -nG "$USER" 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
  sudo usermod -aG docker "$USER" 2>/dev/null \
    && echo "[benchmark] added $USER to docker group — run 'newgrp docker' or re-login."
fi
EOF
chmod +x /etc/profile.d/99-docker-group.sh

touch /var/log/benchmark-ready
echo "BENCHMARK VM READY — $(date -u)"
