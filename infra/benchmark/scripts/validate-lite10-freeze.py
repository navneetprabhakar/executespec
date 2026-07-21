#!/usr/bin/env python3
"""Fail-closed validation for the frozen GLM 5.2 SWE-bench Lite-10 input."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from decimal import Decimal
from pathlib import Path


EXPECTED_IDS = [
    "astropy__astropy-12907",
    "astropy__astropy-14182",
    "astropy__astropy-14365",
    "astropy__astropy-14995",
    "astropy__astropy-7746",
    "astropy__astropy-6938",
    "django__django-10914",
    "django__django-11001",
    "django__django-10924",
    "django__django-11019",
]
SHA256 = re.compile(r"^[0-9a-f]{64}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_row_sha256(row: dict) -> str:
    payload = json.dumps(
        row, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL: {message}")


def validate(source: Path, frozen: Path, manifest_path: Path, verify_registry: bool) -> None:
    source_rows = json.loads(source.read_text(encoding="utf-8"))
    frozen_rows = json.loads(frozen.read_text(encoding="utf-8"))
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    require(isinstance(source_rows, list), "source dataset is not a JSON array")
    require(len(source_rows) == 300, f"source row count is {len(source_rows)}, expected 300")
    require(
        file_sha256(source) == manifest["dataset"]["sourceFileSha256"],
        "source dataset SHA-256 does not match manifest",
    )
    require(
        len({row.get("instance_id") for row in source_rows}) == len(source_rows),
        "source dataset contains duplicate or missing instance IDs",
    )

    require(len(frozen_rows) == 10, f"frozen row count is {len(frozen_rows)}, expected 10")
    frozen_ids = [row.get("instance_id") for row in frozen_rows]
    require(frozen_ids == EXPECTED_IDS, "frozen IDs or order do not match the contract")
    require(len(set(frozen_ids)) == 10, "frozen dataset contains duplicate IDs")
    require(
        file_sha256(frozen) == manifest["dataset"]["frozenFileSha256"],
        "frozen dataset SHA-256 does not match manifest",
    )

    source_by_id = {row["instance_id"]: row for row in source_rows}
    cases = manifest.get("cases")
    require(isinstance(cases, list) and len(cases) == 10, "manifest must contain ten cases")
    for position, (row, case) in enumerate(zip(frozen_rows, cases), start=1):
        instance_id = EXPECTED_IDS[position - 1]
        require(row == source_by_id[instance_id], f"{instance_id} differs from source row")
        require(case.get("position") == position, f"{instance_id} position metadata differs")
        require(case.get("instanceId") == instance_id, f"case {position} ID metadata differs")
        require(case.get("baseCommit") == row.get("base_commit"), f"{instance_id} base commit differs")
        require(
            case.get("canonicalRowSha256") == canonical_row_sha256(row),
            f"{instance_id} canonical row SHA-256 differs",
        )
        image = case.get("image", {})
        require(DIGEST.fullmatch(str(image.get("manifestDigest", ""))) is not None,
                f"{instance_id} image manifest digest is invalid")
        require(DIGEST.fullmatch(str(image.get("configDigest", ""))) is not None,
                f"{instance_id} image config digest is invalid")
        require(
            image.get("immutableReference", "").endswith("@" + image["manifestDigest"]),
            f"{instance_id} immutable image reference does not use its digest",
        )
        require(
            image.get("platform") == {"os": "linux", "architecture": "amd64"},
            f"{instance_id} image platform is not linux/amd64",
        )

    config = manifest.get("generationConfig", {})
    require(config.get("provider") == "openrouter", "provider is not frozen to openrouter")
    require(config.get("matrixModel") == "glm-5.2", "model is not frozen to glm-5.2")
    require(config.get("upstreamModel") == "z-ai/glm-5.2", "upstream model differs")
    require(config.get("strictProvider") is True, "strict provider must be true")
    require(config.get("crossProviderFallback") is False, "cross-provider fallback must be false")
    require(config.get("expectedTotal") == 10, "generation denominator is not ten")
    require(config.get("generationConcurrency") == 4,
            "qualified generation concurrency is not four")
    require(config.get("qualificationNewTaskWaves") == [1, 2, 4, 3],
            "qualification waves must cover C1, C2, C4, then the final three")
    require(sum(config["qualificationNewTaskWaves"]) == config["expectedTotal"],
            "qualification waves do not cover the exact denominator")
    require(config.get("adaptiveConcurrencyLadder") == [4, 2, 1],
            "adaptive concurrency ladder is not frozen to 4 -> 2 -> 1")
    per_task_cap = Decimal(str(config.get("perTaskCapUsd")))
    campaign_cap = Decimal(str(config.get("campaignCapUsd")))
    opening_balance = Decimal(str(config.get("recordedOpeningBalanceUsd")))
    require(per_task_cap * config["expectedTotal"] <= campaign_cap,
            "campaign cap cannot cover every frozen task reservation")
    require(campaign_cap < opening_balance,
            "campaign cap must remain below the no-top-up opening balance")
    task_timeout = int(config.get("taskTimeoutSeconds", 0))
    shutdown_reserve = int(config.get("shutdownReserveSeconds", 0))
    require(task_timeout == 3600, "task timeout is not frozen to 3600 seconds")
    require(shutdown_reserve == 120, "shutdown reserve is not frozen to 120 seconds")
    require(task_timeout > shutdown_reserve, "shutdown reserve consumes the task deadline")
    require(manifest["scoringConfig"].get("denominator") == 10, "scoring denominator is not ten")
    provenance = manifest["provenance"]
    runner_sha = provenance.get("runnerSha256", "")
    require(SHA256.fullmatch(runner_sha) is not None, "runner SHA-256 is absent or malformed")
    repo_root = Path(__file__).resolve().parents[3]
    runner_path = repo_root / provenance.get("runnerFile", "")
    require(runner_path.is_file(), "frozen runner file does not exist")
    require(file_sha256(runner_path) == runner_sha, "frozen runner SHA-256 differs from current bytes")

    if verify_registry:
        for case in cases:
            image = case["image"]
            command = [
                "docker",
                "manifest",
                "inspect",
                "--verbose",
                image["mutableReference"],
            ]
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            require(result.returncode == 0, f"registry lookup failed for {case['instanceId']}")
            remote = json.loads(result.stdout)
            descriptor = remote["Descriptor"]
            schema = remote["SchemaV2Manifest"]
            require(
                descriptor.get("digest") == image["manifestDigest"],
                f"registry manifest digest drifted for {case['instanceId']}",
            )
            require(
                schema.get("config", {}).get("digest") == image["configDigest"],
                f"registry config digest drifted for {case['instanceId']}",
            )
            require(
                descriptor.get("platform") == image["platform"],
                f"registry platform drifted for {case['instanceId']}",
            )

    print(f"PASS source_rows={len(source_rows)} source_sha256={file_sha256(source)}")
    print(f"PASS frozen_rows={len(frozen_rows)} frozen_sha256={file_sha256(frozen)}")
    print("PASS ordered_ids=10 unique_ids=10 source_rows_byte-equivalent_after_JSON_parse=10")
    print("PASS base_commits=10 row_hashes=10 immutable_images=10 platform=linux/amd64")
    print("PASS route=openrouter/glm-5.2 upstream=z-ai/glm-5.2 denominator=10")
    if verify_registry:
        print("PASS registry_manifest_digests=10 registry_config_digests=10")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--frozen", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument(
        "--verify-registry",
        action="store_true",
        help="Resolve all mutable provenance tags and require their manifest/config digests to match",
    )
    args = parser.parse_args()
    validate(args.source, args.frozen, args.manifest, args.verify_registry)


if __name__ == "__main__":
    main()
