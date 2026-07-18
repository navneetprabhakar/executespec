import importlib.util
from pathlib import Path

import pytest


SCRIPT = Path(__file__).with_name("k-eval.py")
SPEC = importlib.util.spec_from_file_location("k_eval", SCRIPT)
k_eval = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(k_eval)


def test_publish_requires_every_manifest_instance():
    with pytest.raises(SystemExit, match="incomplete result coverage"):
        k_eval.validate_publish_coverage({"a": 1}, ["a", "b"], None)


def test_publish_rejects_result_not_in_manifest():
    with pytest.raises(SystemExit, match="incomplete result coverage"):
        k_eval.validate_publish_coverage({"a": 1, "unexpected": 0}, ["a"], None)


def test_publish_accepts_exact_manifest_coverage():
    k_eval.validate_publish_coverage({"a": 1, "b": 0}, ["a", "b"], None)


def test_publish_without_manifest_requires_expected_total():
    with pytest.raises(SystemExit, match="requires --manifest or --expected-total"):
        k_eval.validate_publish_coverage({"a": 1}, [], None)
    k_eval.validate_publish_coverage({"a": 1, "b": 0}, [], 2)
