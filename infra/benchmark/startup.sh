#!/usr/bin/env bash
# Benchmark VM bootstrap — installs the SWE-bench eval stack so the VM is ready
# to clone the repo, run the ExecuteSpec backend, and drive run-core-codegen.sh.
# Idempotent-ish; logs to /var/log/benchmark-bootstrap.log; on success touches
# /var/log/benchmark-ready.
set -euxo pipefail
exec > >(tee -a /var/log/benchmark-bootstrap.log) 2>&1

export DEBIAN_FRONTEND=noninteractive
PG_MAJOR="14"
PGVECTOR_VERSION="v0.8.0"
JAVA_HOME="/usr/lib/jvm/temurin-21-jdk-amd64"
TEMURIN_21_VERSION="21.0.11.0.0+10-1"
NODEJS_VERSION="24.14.1-1nodesource1"
DOCKER_VERSION="5:29.3.1-1~ubuntu.22.04~jammy"
CONTAINERD_VERSION="2.2.2-1~ubuntu.22.04~jammy"
DOCKER_COMPOSE_VERSION="5.1.1-1~ubuntu.22.04~jammy"
export JAVA_HOME

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

# ── Node.js LTS (matches the dev VM validator-toolchain path) ───────────────
curl -fsSL https://deb.nodesource.com/setup_lts.x | bash -

# ── Redis 7+ (Spring Data uses GETDEL; Ubuntu 22.04's Redis 6.0 lacks it) ──
curl -fsSL https://packages.redis.io/gpg | gpg --dearmor -o /etc/apt/keyrings/redis-archive-keyring.gpg
chmod a+r /etc/apt/keyrings/redis-archive-keyring.gpg
echo "deb [signed-by=/etc/apt/keyrings/redis-archive-keyring.gpg] https://packages.redis.io/deb ${VERSION_CODENAME} main" \
  > /etc/apt/sources.list.d/redis.list

apt-get update
apt-get install -y --allow-downgrades \
  docker-ce=${DOCKER_VERSION} docker-ce-cli=${DOCKER_VERSION} containerd.io=${CONTAINERD_VERSION} docker-buildx-plugin docker-compose-plugin=${DOCKER_COMPOSE_VERSION} \
  temurin-21-jdk=${TEMURIN_21_VERSION} maven \
  nodejs=${NODEJS_VERSION} \
  python3 python3-pip python3-venv \
  postgresql-${PG_MAJOR} postgresql-contrib postgresql-server-dev-${PG_MAJOR} redis-server

systemctl enable --now docker postgresql redis-server

# ── SWE-bench harness (official scorer) ──────────────────────────────────────
pip3 install --upgrade pip --break-system-packages 2>/dev/null \
  || pip3 install --upgrade pip \
  || true
pip3 install swebench datasets --break-system-packages 2>/dev/null \
  || pip3 install swebench datasets \
  || true

# ── Validator fallbacks used by ExecuteSpec build/test checks ────────────────
pip3 install --quiet --break-system-packages mypy ruff pytest 2>/dev/null \
  || pip3 install --quiet mypy ruff pytest 2>/dev/null \
  || echo "Python validator tools install skipped"

# ── Postgres + DB for the backend (matches local dev: db=executespec, postgres/postgres) ──
runuser -u postgres -- psql -c "ALTER USER postgres PASSWORD 'postgres';" || true
runuser -u postgres -- createdb executespec || true

# ── pgvector for project-document embeddings / Flyway V95 ───────────────────
# Ubuntu 22.04 does not package pgvector for PostgreSQL 14 by default. Build it
# once from source so a recreated benchmark VM can run the same migrations as dev.
if [ ! -f "/usr/share/postgresql/${PG_MAJOR}/extension/vector.control" ]; then
  rm -rf /tmp/pgvector
  git clone --depth 1 --branch "${PGVECTOR_VERSION}" https://github.com/pgvector/pgvector.git /tmp/pgvector
  make -C /tmp/pgvector PG_CONFIG="/usr/lib/postgresql/${PG_MAJOR}/bin/pg_config"
  make -C /tmp/pgvector install PG_CONFIG="/usr/lib/postgresql/${PG_MAJOR}/bin/pg_config"
fi
runuser -u postgres -- psql -d executespec -c "CREATE EXTENSION IF NOT EXISTS vector;"

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

echo "── Runtime versions ──"
java -version 2>&1 | head -1 || true
mvn --version 2>/dev/null | head -1 || true
node --version 2>/dev/null || true
npm --version 2>/dev/null || true
python3 --version 2>/dev/null || true
docker --version 2>/dev/null || true
docker compose version 2>/dev/null || true
psql --version 2>/dev/null || true
redis-server --version 2>/dev/null || true
runuser -u postgres -- psql -d executespec -Atc "select extname || '=' || extversion from pg_extension where extname = 'vector';" || true

touch /var/log/benchmark-ready
echo "BENCHMARK VM READY — $(date -u)"
