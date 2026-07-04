#!/usr/bin/env python3
"""Build a per-model SWE-bench Pro patches JSON from a lane manifest, using the EXACT
proPatchJson path each generate call returned (no glob/attribution ambiguity).

Usage: build-patches.py <model>
Reads swe-pro-sample20-<model>.manifest.jsonl, writes patches-<model>.json.
Falls back to preds.json path if proPatchJson is absent (older manifest rows).
"""
import json, pathlib, sys

D = pathlib.Path.home() / "swebench-bench"
model = sys.argv[1]
mf = D / f"swe-pro-sample20-{model}.manifest.jsonl"
patches, missing = [], []
for line in mf.read_text().splitlines():
    r = json.loads(line)
    if r.get("outcome") != "COMPLETED":
        continue
    p = r.get("proPatchJson")
    entry = None
    if p and pathlib.Path(p).exists():
        arr = json.load(open(p))
        entry = arr[0] if isinstance(arr, list) and arr else None
    if entry is None:
        missing.append(r["instance"])
        continue
    entry = dict(entry)
    entry["prefix"] = model
    entry["instance_id"] = r["instance"]
    patches.append(entry)
json.dump(patches, open(D / f"patches-{model}.json", "w"))
print(f"{model}: {len(patches)} patches from exact paths, {len(missing)} missing")
if missing:
    print("  missing (no proPatchJson in manifest):", missing[:5])
