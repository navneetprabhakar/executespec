#!/usr/bin/env python3
"""Independent held-out evaluator for the low-python-addition fixture.

The fixture harness only checks the source text. This evaluator loads the exported
artifact as a separate process and checks multiple runtime cases, so a matching
substring alone cannot make a cohort task score as passed.
"""

import importlib.util
import json
import pathlib
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print(json.dumps({"passed": 0, "total": 2, "reason": "usage: evaluator FILE"}))
        return 2
    path = pathlib.Path(sys.argv[1]).resolve()
    spec = importlib.util.spec_from_file_location("heldout_calculator", path)
    if spec is None or spec.loader is None:
        print(json.dumps({"passed": 0, "total": 2, "reason": "unable to load artifact"}))
        return 1
    module = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(module)
        cases = [(2, 3, 5), (-2, 7, 5)]
        results = [module.add(a, b) == expected for a, b, expected in cases]
    except Exception as exc:  # noqa: BLE001 - evaluator must report a bounded failure
        print(json.dumps({"passed": 0, "total": 2, "reason": f"runtime error: {exc}"}))
        return 1
    passed = sum(results)
    payload = {"passed": passed, "total": len(results), "results": results}
    print(json.dumps(payload, sort_keys=True))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
