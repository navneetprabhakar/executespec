#!/bin/sh
set -eu

test -d /workspace/.executespec
test ! -L /workspace/.executespec
runtime_dir="$(mktemp -d /workspace/.executespec/runtime-python-http.XXXXXX)"
cleanup() {
  rm -rf "${runtime_dir}"
}
trap cleanup EXIT HUP INT TERM

printf '%s\n' 'EXECUTESPEC_RUNTIME_KIT=python-http-v1'
export HOME=/tmp
export PYTHONDONTWRITEBYTECODE=1
export PIP_DISABLE_PIP_VERSION_CHECK=1
export PIP_NO_CACHE_DIR=1

python3 -m pip install \
  --no-index \
  --only-binary=:all: \
  --no-compile \
  --target "${runtime_dir}" \
  --find-links=/opt/executespec/runtime/wheels \
  --requirement /opt/executespec/runtime/python-http-runtime.lock

PYTHONPATH="${runtime_dir}" \
  python3 -m pytest -q -s .executespec/bootstrap/test_acceptance.py
