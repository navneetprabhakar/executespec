#!/usr/bin/env python3
"""Run mini-swe-agent on ProgramBench tasks.

Forks the structure of mini-swe-agent's built-in swebench.py. Differences:
  - Tasks come from the locally installed `programbench` package
    (`<install>/data/tasks/<id>/task.yaml`) — no HuggingFace download.
  - Image: `programbench/<id_with_double_underscore_replaced>:task_cleanroom`.
  - Container started with `--network none` (cleanroom contract).
  - The agent's reconstructed code in /workspace is captured via
    `docker cp` and tarred as submission.tar.gz at the layout
    `<output>/<instance_id>/submission.tar.gz` that `programbench eval`
    expects.

Usage (single task):
    programbench_runner.py --instance abishekvashok__cmatrix.5c082c6 \\
        --output ./out --model anthropic/claude-sonnet-4-5-20250929

Usage (subset by language/difficulty):
    programbench_runner.py --filter '.*' --slice 0:5 --output ./out

Then score:
    programbench eval ./out
"""
from __future__ import annotations

import concurrent.futures
import json
import re
import subprocess
import sys
import tarfile
import threading
import time
import traceback
from pathlib import Path

import typer
import yaml
from jinja2 import StrictUndefined, Template
from rich.live import Live

from minisweagent import Environment
from minisweagent.agents.default import DefaultAgent
from minisweagent.config import get_config_from_spec
from minisweagent.environments import get_environment
from minisweagent.models import get_model
from minisweagent.run.benchmarks.utils.batch_progress import RunBatchProgressManager
from minisweagent.utils.log import logger
from minisweagent.utils.serialize import UNSET, recursive_merge

HERE = Path(__file__).resolve().parent
DEFAULT_CONFIG_FILE = HERE / "programbench.yaml"

app = typer.Typer(rich_markup_mode="rich", add_completion=False)


# ---------- task discovery -------------------------------------------------


def _programbench_tasks_dir() -> Path:
    """Locate the data/tasks dir of the installed programbench package."""
    try:
        import programbench  # type: ignore
    except ImportError as e:
        raise RuntimeError(
            "programbench is not installed. Run `uv pip install programbench`."
        ) from e
    pkg_root = Path(programbench.__file__).resolve().parent
    tasks = pkg_root / "data" / "tasks"
    if not tasks.is_dir():
        raise RuntimeError(f"programbench data/tasks not found at {tasks}")
    return tasks


def discover_instances() -> list[dict]:
    """Build instance dicts (similar shape to swebench instances) from local tasks."""
    out: list[dict] = []
    for d in sorted(_programbench_tasks_dir().iterdir()):
        spec_path = d / "task.yaml"
        if not spec_path.is_file():
            continue
        spec = yaml.safe_load(spec_path.read_text()) or {}
        out.append(
            {
                "instance_id": d.name,
                "repository": spec.get("repository", "unknown"),
                "commit": spec.get("commit", "unknown"),
                "language": spec.get("language", "unknown"),
                "difficulty": spec.get("difficulty", "unknown"),
            }
        )
    return out


def filter_instances(
    instances: list[dict],
    *,
    instance_id: str | None,
    filter_spec: str,
    slice_spec: str,
    languages: list[str],
    difficulties: list[str],
) -> list[dict]:
    if instance_id:
        match = [i for i in instances if i["instance_id"] == instance_id]
        if not match:
            raise typer.BadParameter(f"unknown instance id: {instance_id}")
        return match
    if languages:
        instances = [i for i in instances if i["language"] in languages]
    if difficulties:
        instances = [i for i in instances if i["difficulty"] in difficulties]
    if filter_spec:
        instances = [i for i in instances if re.match(filter_spec, i["instance_id"])]
    if slice_spec:
        bounds = [int(x) if x else None for x in slice_spec.split(":")]
        instances = instances[slice(*bounds)]
    return instances


# ---------- image + env ----------------------------------------------------


def image_name_from_instance_id(instance_id: str, org: str = "programbench") -> str:
    """Match programbench.constants.image_name_from_instance_id."""
    docker_safe = instance_id.replace("__", "_1776_")
    return f"{org}/{docker_safe}:task_cleanroom"


def _render_agent_templates(config: dict, instance: dict) -> dict:
    """Render Jinja in agent.system_template and agent.instance_template."""
    agent_cfg = config.get("agent", {})
    for key in ("system_template", "instance_template"):
        if key in agent_cfg:
            agent_cfg[key] = Template(
                agent_cfg[key], undefined=StrictUndefined
            ).render(**instance)
    config["agent"] = agent_cfg
    return config


def get_pb_environment(config: dict, instance: dict) -> Environment:
    env_config = dict(config.get("environment", {}))
    env_config.setdefault("environment_class", "docker")
    env_config["image"] = image_name_from_instance_id(instance["instance_id"])
    return get_environment(env_config)


# ---------- per-instance run ----------------------------------------------


_CAPTURE_LOCK = threading.Lock()


class ProgressTrackingAgent(DefaultAgent):
    def __init__(self, *args, progress_manager, instance_id: str = "", **kwargs):
        super().__init__(*args, **kwargs)
        self.progress_manager = progress_manager
        self.instance_id = instance_id

    def step(self) -> dict:
        self.progress_manager.update_instance_status(
            self.instance_id, f"Step {self.n_calls + 1:3d} (${self.cost:.2f})"
        )
        return super().step()


def _capture_workspace(container_id: str, output_dir: Path) -> Path:
    """`docker cp <cid>:/workspace ./submission/` then tar.gz it.

    Returns the path to submission.tar.gz.
    """
    submission_dir = output_dir / "submission"
    if submission_dir.exists():
        # programbench expects a clean directory layout per task
        import shutil

        shutil.rmtree(submission_dir)
    submission_dir.mkdir(parents=True)

    # docker cp brings the workspace dir contents to the host
    cp_cmd = ["docker", "cp", f"{container_id}:/workspace/.", str(submission_dir)]
    subprocess.run(cp_cmd, check=True, timeout=300)

    archive = output_dir / "submission.tar.gz"
    if archive.exists():
        archive.unlink()
    with tarfile.open(archive, "w:gz") as tf:
        # programbench eval extracts the tar into /workspace and runs
        # `chmod +x ./compile.sh && ./compile.sh` — so the entries must be at
        # the archive root, NOT under a top-level "submission/" directory.
        # (Verified by reading programbench/eval/eval.py:_compile_executable.)
        tf.add(submission_dir, arcname=".")
    return archive


def process_instance(
    instance: dict,
    output_root: Path,
    config_template: dict,
    progress_manager: RunBatchProgressManager,
) -> None:
    instance_id = instance["instance_id"]
    instance_dir = output_root / instance_id
    instance_dir.mkdir(parents=True, exist_ok=True)
    traj_path = instance_dir / f"{instance_id}.traj.json"
    if traj_path.exists():
        traj_path.unlink()

    # Each instance gets its own deep-copied + Jinja-rendered config so
    # parallel workers don't trample each other's agent.system_template.
    config = recursive_merge(config_template, {})
    config = _render_agent_templates(config, instance)

    progress_manager.on_instance_start(instance_id)
    progress_manager.update_instance_status(instance_id, "starting container")

    model = get_model(config=config.get("model", {}))
    task = config.get("agent", {}).get(
        "instance_template", "Reimplement the binary at /workspace."
    )

    agent = None
    env = None
    exit_status = None
    submission_archive = None
    extra: dict = {}

    try:
        env = get_pb_environment(config, instance)
        agent = ProgressTrackingAgent(
            model,
            env,
            progress_manager=progress_manager,
            instance_id=instance_id,
            **config.get("agent", {}),
        )
        info = agent.run(task)
        exit_status = info.get("exit_status")
        progress_manager.update_instance_status(instance_id, "capturing /workspace")

        container_id = getattr(env, "container_id", None)
        if not container_id:
            raise RuntimeError("env did not expose container_id; cannot docker cp")
        submission_archive = _capture_workspace(container_id, instance_dir)
    except Exception as e:
        logger.error("instance %s failed: %s", instance_id, e, exc_info=True)
        exit_status = exit_status or type(e).__name__
        extra = {"traceback": traceback.format_exc(), "exception_str": str(e)}
    finally:
        if agent is not None:
            try:
                agent.save(
                    traj_path,
                    {
                        "info": {
                            "exit_status": exit_status,
                            "submission_archive": (
                                str(submission_archive)
                                if submission_archive
                                else None
                            ),
                            **extra,
                        },
                        "instance_id": instance_id,
                    },
                )
            except Exception:
                logger.exception("failed to save trajectory for %s", instance_id)
        # mini-swe-agent's docker env starts the container with --rm so it
        # will clean itself up on exit. Best-effort kill if it's still around.
        if env is not None:
            cid = getattr(env, "container_id", None)
            if cid:
                subprocess.run(
                    ["docker", "rm", "-f", cid],
                    capture_output=True,
                    timeout=30,
                    check=False,
                )
        progress_manager.on_instance_end(instance_id, exit_status)


# ---------- CLI ------------------------------------------------------------


# fmt: off
@app.command()
def main(
    output: Path = typer.Option(..., "--output", "-o", help="Output directory (programbench-eval-compatible layout)."),
    config_path: Path = typer.Option(DEFAULT_CONFIG_FILE, "--config", "-c", help="Config yaml. Defaults to bundled programbench.yaml."),
    instance_id: str = typer.Option(None, "--instance", "-i", help="Run exactly one instance by id (e.g. abishekvashok__cmatrix.5c082c6)."),
    filter_spec: str = typer.Option("", "--filter", help="Regex filter on instance_id."),
    slice_spec: str = typer.Option("", "--slice", help="Slice spec, e.g. '0:5'."),
    languages: list[str] = typer.Option([], "--language", help="Filter by language (rust, c, go, ...). Repeatable."),
    difficulties: list[str] = typer.Option([], "--difficulty", help="Filter by difficulty (easy, medium, hard). Repeatable."),
    model_name: str = typer.Option(None, "--model", "-m", help="Override model name (e.g. anthropic/claude-sonnet-4-5-20250929)."),
    workers: int = typer.Option(1, "--workers", "-w", help="Concurrent instance count. Each holds a docker container — keep low on small disks."),
):
    """Run mini-swe-agent on ProgramBench tasks."""
    output.mkdir(parents=True, exist_ok=True)
    log_file = output / "run.log"
    logger.info("logging to %s", log_file)

    config = get_config_from_spec(str(config_path))
    if model_name:
        config = recursive_merge(config, {"model": {"model_name": model_name}})

    instances = filter_instances(
        discover_instances(),
        instance_id=instance_id,
        filter_spec=filter_spec,
        slice_spec=slice_spec,
        languages=languages,
        difficulties=difficulties,
    )
    if not instances:
        raise typer.BadParameter("filters matched zero instances")
    logger.info("running %d instance(s)", len(instances))
    for i in instances:
        logger.info("  - %s (%s, %s)", i["instance_id"], i["language"], i["difficulty"])

    progress_manager = RunBatchProgressManager(
        len(instances), output / f"exit_statuses_{int(time.time())}.yaml"
    )

    def drain(futs: dict[concurrent.futures.Future, str]) -> None:
        for f in concurrent.futures.as_completed(futs):
            try:
                f.result()
            except concurrent.futures.CancelledError:
                pass
            except Exception as e:
                iid = futs[f]
                logger.exception("instance %s raised: %s", iid, e)
                progress_manager.on_uncaught_exception(iid, e)

    with Live(progress_manager.render_group, refresh_per_second=4):
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
            futs = {
                ex.submit(process_instance, inst, output, config, progress_manager): inst["instance_id"]
                for inst in instances
            }
            try:
                drain(futs)
            except KeyboardInterrupt:
                logger.info("cancelling pending jobs (^C again to exit immediately)")
                for f in futs:
                    if not f.running() and not f.done():
                        f.cancel()
                drain(futs)

    print(f"Done. Output at: {output}")
    print(f"Score it with: programbench eval {output}")


if __name__ == "__main__":
    app()
