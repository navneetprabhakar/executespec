import importlib.util
import pathlib


MODULE_PATH = pathlib.Path(__file__).with_name("tb-bench-bridge.py")
SPEC = importlib.util.spec_from_file_location("tb_bench_bridge", MODULE_PATH)
bridge = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(bridge)


def test_exception_file_setup_failure_is_fatal():
    outcome, reason = bridge._classify_trial(
        {"agent_result": None, "verifier_result": None},
        0,
        "Docker registry setup failed before agent execution",
    )
    assert outcome == "INFRA_SETUP_FAILED"
    assert "Docker" in reason


def test_timeout_still_wins_over_setup_classification():
    outcome, _ = bridge._classify_trial(
        {"agent_result": None, "verifier_result": None},
        0,
        "AgentTimeoutError while waiting for container",
    )
    assert outcome == "TIMEOUT"
