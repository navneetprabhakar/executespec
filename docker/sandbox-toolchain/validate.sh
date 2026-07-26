#!/usr/bin/env bash
set -euo pipefail

IMAGE_TAG="${1:-executespec/sandbox-toolchain:local}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTEXT_DIR="${ROOT_DIR}/docker/sandbox-toolchain"

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

docker run --rm \
  --network none \
  --user 65532:65532 \
  --security-opt no-new-privileges \
  --cap-drop ALL \
  --read-only \
  --pids-limit 128 \
  --memory 1g \
  --cpus 2 \
  --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --volume "${TMP_DIR}:/workspace:rw" \
  --workdir /workspace \
  "${IMAGE_TAG}" \
  sh -ceu '
    javac -d /tmp Hello.java
    java -cp /tmp Hello | grep -qx java-ok
    python3 main.py | grep -qx python-ok
    node index.js | grep -qx node-ok
    GOCACHE=/tmp/go-cache go build -o /workspace/go-main main.go
    /workspace/go-main | grep -qx go-ok
    rustc -o /workspace/rust-main main.rs
    /workspace/rust-main | grep -qx rust-ok
    black --quiet format.py
    prettier --write --log-level=warn format.js
    gofmt -w main.go
    mvn --version >/dev/null
  '

IMAGE_ID="$(docker image inspect "${IMAGE_TAG}" --format '{{.Id}}')"
printf 'validated_image=%s\n' "${IMAGE_TAG}"
printf 'local_image_id=%s\n' "${IMAGE_ID}"
printf '%s\n' \
  'deployment requires a registry push and the resulting repository@sha256:... reference; flags remain off'
