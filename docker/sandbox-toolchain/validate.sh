#!/usr/bin/env bash
set -euo pipefail

IMAGE_TAG="${1:-executespec/sandbox-toolchain:local}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTEXT_DIR="${ROOT_DIR}/docker/sandbox-toolchain"
# Strict mounts must use the non-root service identity that owns the fixture workspace.
VALIDATE_RUNTIME_UID="$(id -u)"
VALIDATE_RUNTIME_GID="$(id -g)"
if [[ "${VALIDATE_RUNTIME_UID}" == 0 ]]; then
  printf '%s\n' 'Run validation as the non-root workspace owner.' >&2
  exit 2
fi

docker build --pull=false --tag "${IMAGE_TAG}" "${CONTEXT_DIR}"

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

cat >"${TMP_DIR}/Hello.java" <<'EOF'
public class Hello { public static void main(String[] args) { System.out.println("java-ok"); } }
EOF
cat >"${TMP_DIR}/main.py" <<'EOF'
print("python-ok")
EOF
cat >"${TMP_DIR}/index.js" <<'EOF'
console.log("node-ok")
EOF
cat >"${TMP_DIR}/main.go" <<'EOF'
package main
import "fmt"
func main() { fmt.Println("go-ok") }
EOF
cat >"${TMP_DIR}/main.rs" <<'EOF'
fn main() { println!("rust-ok"); }
EOF
cat >"${TMP_DIR}/format.py" <<'EOF'
x=  1
EOF
cat >"${TMP_DIR}/format.js" <<'EOF'
const value={answer:42}
EOF
mkdir -p "${TMP_DIR}/.executespec/bootstrap"
cat >"${TMP_DIR}/.executespec/bootstrap/test_acceptance.py" <<'EOF'
def test_offline_runtime_kit_imports():
    import fastapi
    import pydantic
    import uvicorn

    assert fastapi.__version__
    assert pydantic.__version__
    assert uvicorn.__version__
EOF

docker run --rm \
  --network none \
  --user "${VALIDATE_RUNTIME_UID}:${VALIDATE_RUNTIME_GID}" \
  --env "EXPECTED_RUNTIME_UID=${VALIDATE_RUNTIME_UID}" \
  --env "EXPECTED_RUNTIME_GID=${VALIDATE_RUNTIME_GID}" \
  --security-opt no-new-privileges \
  --cap-drop ALL \
  --read-only \
  --pids-limit 128 \
  --memory 1g \
  --memory-swap 1g \
  --cpus 2 \
  --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --volume "${TMP_DIR}:/workspace:rw" \
  --workdir /workspace \
  "${IMAGE_TAG}" \
  sh -ceu '
    test "$(id -u)" = "${EXPECTED_RUNTIME_UID}"
    test "$(id -g)" = "${EXPECTED_RUNTIME_GID}"
    test "$(id -u)" != 0
    test ! -S /var/run/docker.sock
    test ! -e /Users
    if touch /etc/executespec-readonly-probe 2>/dev/null; then exit 11; fi
    grep -Eq "^CapEff:[[:space:]]+0+$" /proc/self/status
    test "$(cat /sys/fs/cgroup/pids.max)" = 128
    test "$(cat /sys/fs/cgroup/memory.max)" = 1073741824
    test "$(cat /sys/fs/cgroup/memory.swap.max)" = 0
    test "$(cat /sys/fs/cgroup/cpu.max)" = "200000 100000"
    printf "#!/bin/sh\nexit 0\n" >/tmp/noexec-probe
    chmod +x /tmp/noexec-probe
    if /tmp/noexec-probe 2>/dev/null; then exit 12; fi
    python3 - <<"PY"
import socket
s = socket.socket()
s.settimeout(1)
try:
    s.connect(("169.254.169.254", 80))
except OSError:
    raise SystemExit(0)
raise SystemExit(13)
PY
    javac -d /tmp Hello.java
    java -cp /tmp Hello | grep -qx java-ok
    python3 main.py | grep -qx python-ok
    python3 -m pytest --version
    PYTHONDONTWRITEBYTECODE=1 python3 -m pytest -q -s .executespec/bootstrap/test_acceptance.py
    node index.js | grep -qx node-ok
    GOCACHE=/tmp/go-cache go build -o /workspace/go-main main.go
    /workspace/go-main | grep -qx go-ok
    rustc -o /workspace/rust-main main.rs
    /workspace/rust-main | grep -qx rust-ok
    black --quiet format.py
    prettier --write --log-level=warn format.js
    gofmt -w main.go
    mvn --version >/dev/null
    runtime_output="$(/opt/executespec/runtime/python-http-acceptance.sh)"
    printf "%s\n" "${runtime_output}" | grep -q "EXECUTESPEC_RUNTIME_KIT=python-http-v1"
    printf "%s\n" "${runtime_output}" | grep -Eq "1 passed"
  '

IMAGE_ID="$(docker image inspect "${IMAGE_TAG}" --format '{{.Id}}')"
printf 'validated_image=%s\n' "${IMAGE_TAG}"
printf 'local_image_id=%s\n' "${IMAGE_ID}"
printf '%s\n' 'containment_smoke=pass (non-root, no socket/host path, zero caps, cgroup ceilings, noexec tmp, metadata denied)'
printf '%s\n' \
  'deployment requires a registry push and repository@sha256:... reference; runtime dependency install is offline and flags remain off'
