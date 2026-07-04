#!/usr/bin/env python3
"""Pre-pull Terminal-Bench Docker images from Harbor's cached task metadata.

Harbor records the prebuilt task image in each cached task's task.toml as
`docker_image = "..."`. Pulling those images before the timed benchmark avoids
Docker Hub registry/transient pull failures being misread as model failures.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import os
from pathlib import Path
import re
import subprocess
import sys
import time


DOCKER_IMAGE_RE = re.compile(r'^\s*docker_image\s*=\s*["\']([^"\']+)["\']\s*$')


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--cache-root",
        default=str(Path.home() / ".cache" / "harbor" / "tasks" / "packages"),
        help="Harbor cache package root.",
    )
    parser.add_argument(
        "--package",
        default="terminal-bench",
        help="Harbor package directory under the cache root.",
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=5,
        help="docker pull attempts per image.",
    )
    parser.add_argument(
        "--retry-sleep",
        type=int,
        default=20,
        help="base seconds to sleep between pull retries.",
    )
    parser.add_argument(
        "--jobs",
        type=int,
        default=4,
        help="parallel docker pulls.",
    )
    parser.add_argument(
        "--min-images",
        type=int,
        default=1,
        help="fail if fewer images are discovered.",
    )
    parser.add_argument(
        "--image-list-out",
        help="optional file path where discovered images are written.",
    )
    return parser.parse_args()


def discover_images(cache_root: Path, package: str) -> list[str]:
    package_root = cache_root / package
    images: set[str] = set()
    for task_toml in package_root.glob("*/*/task.toml"):
        try:
            for line in task_toml.read_text(errors="replace").splitlines():
                match = DOCKER_IMAGE_RE.match(line)
                if match:
                    images.add(match.group(1).strip())
                    break
        except OSError as exc:
            print(f"preflight: cannot read {task_toml}: {exc}", file=sys.stderr)
    return sorted(images)


def pull_image(image: str, retries: int, retry_sleep: int) -> tuple[str, bool, str]:
    last_output = ""
    for attempt in range(1, retries + 1):
        proc = subprocess.run(
            ["docker", "pull", image],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        last_output = proc.stdout[-4000:]
        if proc.returncode == 0:
            return image, True, f"ok attempt={attempt}"
        if attempt < retries:
            sleep_for = retry_sleep * attempt
            print(
                f"preflight: pull failed image={image} attempt={attempt}/{retries}; "
                f"sleep={sleep_for}s",
                flush=True,
            )
            time.sleep(sleep_for)
    return image, False, last_output


def main() -> int:
    args = parse_args()
    images = discover_images(Path(args.cache_root), args.package)
    if args.image_list_out:
        out = Path(args.image_list_out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text("\n".join(images) + ("\n" if images else ""))
    print(f"preflight: discovered {len(images)} images package={args.package}", flush=True)
    if len(images) < args.min_images:
        print(
            f"preflight: expected at least {args.min_images} images, found {len(images)}",
            file=sys.stderr,
        )
        return 2

    failures: list[tuple[str, str]] = []
    jobs = max(1, min(args.jobs, len(images)))
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = {
            pool.submit(pull_image, image, args.retries, args.retry_sleep): image
            for image in images
        }
        for fut in concurrent.futures.as_completed(futures):
            image, ok, detail = fut.result()
            if ok:
                print(f"preflight: pulled {image} ({detail})", flush=True)
            else:
                failures.append((image, detail))
                print(f"preflight: FAILED {image}", file=sys.stderr, flush=True)

    if failures:
        print("preflight: docker image pull failures:", file=sys.stderr)
        for image, detail in failures:
            print(f"--- {image} ---", file=sys.stderr)
            print(detail, file=sys.stderr)
        return 1
    print(f"preflight: all {len(images)} images are available", flush=True)
    return 0


if __name__ == "__main__":
    os.environ.setdefault("DOCKER_CLI_HINTS", "false")
    sys.exit(main())
