#!/usr/bin/env python3
"""Check tracked-path hygiene without reading or printing credential values."""

import argparse
from pathlib import Path, PurePosixPath
import subprocess
import sys


LOCAL_ROOTS = {".codex-work", ".claude", "output", "outputs", "workspace"}
GENERATED_ROOTS = (
    "src/main/resources/static/",
    "scripts/web-agent-release-results/",
    "scripts/web-live-recovery-results/",
    "scripts/test-resilience-results/",
)
REQUIRED_FILES = ("LICENSE", "NOTICE", "README.md", "CONTRIBUTING.md", "SECURITY.md")


def forbidden_path(name):
    path = PurePosixPath(name)
    if path.parts[0] in LOCAL_ROOTS or name.startswith(GENERATED_ROOTS):
        return "local or generated artifact"
    if path.name.startswith(".env") and path.name not in {".env.template", ".env.example"}:
        return "local environment file"
    if path.name in {"application-local.yml", "application-local.yaml", "sa-key.json"}:
        return "local configuration or service-account file"
    if path.name == "tfplan" or path.name.startswith("tfplan-") or path.suffix == ".tfplan":
        return "Terraform plan"
    if ".tfstate" in path.name or path.suffix in {".p12", ".pfx", ".key"}:
        return "state or credential container"
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    root = args.root.resolve()
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    failures = []
    for name in filter(None, tracked):
        # Unstaged deletions are still tracked until staged; the index is the publication input.
        reason = forbidden_path(name)
        if reason:
            failures.append("{}: {}".format(name, reason))
    for name in REQUIRED_FILES:
        if not (root / name).is_file():
            failures.append("{}: required publication document missing".format(name))
    if failures:
        print("Public repository hygiene failed:", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("Tracked-path hygiene passed. Secret/history scanning is a separate check.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
