"""Supervised, idempotent, never-deleting GCS sync for benchmark artifacts.

Replaces the one-shot `sync-results.sh` behaviour (a single `gsutil rsync` whose
failure is silently swallowed by `|| true`) with a supervised interval loop.

Guarantees:
  * NEVER deletes remote artifacts. `-d` / `--delete` are rejected at construction
    time, not merely omitted, so a future edit cannot reintroduce them by accident.
  * Idempotent — rsync of an unchanged tree is a no-op; repeated runs converge.
  * Durable state — last success / last error / consecutive failures are written
    to a JSON state file readable by the read-only health monitor and reloadable
    across a process restart.
  * Survives transient failures with capped exponential backoff.
  * Performs a final sync on normal termination (EXIT / TERM / INT).

HONESTY: the loop, the backoff, the refusals and the state transitions are unit
tested with a fake runner. Nothing here has been exercised against real GCS, real
credentials, real network partitions, or a real VM teardown race.
"""

from __future__ import annotations

import json
import os
import shlex
import signal
import subprocess
import threading
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence

STATE_SCHEMA_VERSION = 1

# Any flag that could cause remote deletion. Checked as whole tokens.
_FORBIDDEN_FLAGS = {"-d", "--delete", "--delete-from-source", "-D"}


class UnsafeSyncCommand(Exception):
    """Raised when a command could delete remote artifacts."""


@dataclass
class SyncResult:
    ok: bool
    returncode: int
    started_at: float
    duration_sec: float
    stderr_tail: str = ""


def assert_no_delete(argv: Sequence[str]) -> None:
    """Refuse any command that can remove remote objects.

    Deliberately a hard exception rather than a filter: silently stripping a
    delete flag would hide operator intent that needs to be seen.
    """
    for token in argv:
        if token in _FORBIDDEN_FLAGS:
            raise UnsafeSyncCommand(
                f"refusing sync command containing {token!r}: benchmark artifact "
                "sync must never delete remote objects"
            )
    return None


def build_rsync_argv(source: str, dest: str, gsutil: str = "gsutil") -> List[str]:
    """Build the additive rsync command. `-r` recurses; there is no `-d`."""
    argv = [gsutil, "-m", "rsync", "-r", str(source), str(dest)]
    assert_no_delete(argv)
    return argv


def _default_runner(argv: Sequence[str], timeout: float) -> SyncResult:  # pragma: no cover
    started = time.time()
    try:
        proc = subprocess.run(
            list(argv),
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        return SyncResult(
            ok=proc.returncode == 0,
            returncode=proc.returncode,
            started_at=started,
            duration_sec=time.time() - started,
            stderr_tail=(proc.stderr or "")[-2000:],
        )
    except subprocess.TimeoutExpired:
        return SyncResult(
            ok=False,
            returncode=124,
            started_at=started,
            duration_sec=time.time() - started,
            stderr_tail=f"timeout after {timeout}s",
        )


class SupervisedSync:
    """Interval-driven supervisor around an additive `gsutil rsync`."""

    def __init__(
        self,
        source: Path,
        dest: str,
        state_path: Path,
        interval_sec: float = 300.0,
        max_backoff_sec: float = 1800.0,
        command_timeout_sec: float = 3600.0,
        runner: Optional[Callable[[Sequence[str], float], SyncResult]] = None,
        clock: Callable[[], float] = time.time,
        waiter: Optional[Callable[[threading.Event, float], bool]] = None,
        gsutil: str = "gsutil",
    ):
        self.source = Path(source)
        self.dest = dest
        self.state_path = Path(state_path)
        self.interval_sec = interval_sec
        self.max_backoff_sec = max_backoff_sec
        self.command_timeout_sec = command_timeout_sec
        self._runner = runner or _default_runner
        self._clock = clock
        # Waiting on the stop event (rather than sleeping) means a SIGTERM during
        # a long backoff is honoured immediately instead of after the full delay.
        self._wait = waiter or (lambda event, delay: event.wait(delay))
        self._gsutil = gsutil
        # Built once so an unsafe command is refused at construction, before any
        # loop starts and before the operator believes sync is running.
        self._argv = build_rsync_argv(str(self.source), self.dest, gsutil=gsutil)
        self.state: Dict = self._load_state()

    # -- state ------------------------------------------------------------
    def _load_state(self) -> Dict:
        if self.state_path.is_file():
            try:
                loaded = json.loads(self.state_path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict) and loaded.get("schemaVersion") == STATE_SCHEMA_VERSION:
                    return loaded
            except (OSError, ValueError):
                pass  # corrupt state is not fatal; sync is idempotent anyway
        return {
            "schemaVersion": STATE_SCHEMA_VERSION,
            "source": str(self.source),
            "dest": self.dest,
            "attempts": 0,
            "successes": 0,
            "failures": 0,
            "consecutiveFailures": 0,
            "lastSuccessAt": None,
            "lastErrorAt": None,
            "lastError": None,
            "lastAttemptAt": None,
            "finalSyncDone": False,
        }

    def _persist(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.state_path.with_suffix(self.state_path.suffix + ".tmp")
        tmp.write_text(json.dumps(self.state, indent=2, sort_keys=True), encoding="utf-8")
        os.replace(tmp, self.state_path)  # atomic; a reader never sees a half file

    # -- one pass ---------------------------------------------------------
    def sync_once(self) -> SyncResult:
        assert_no_delete(self._argv)  # re-checked per pass, not only at construction
        result = self._runner(self._argv, self.command_timeout_sec)
        self.state["attempts"] += 1
        self.state["lastAttemptAt"] = self._clock()
        if result.ok:
            self.state["successes"] += 1
            self.state["consecutiveFailures"] = 0
            self.state["lastSuccessAt"] = self._clock()
            self.state["lastError"] = None
        else:
            self.state["failures"] += 1
            self.state["consecutiveFailures"] += 1
            self.state["lastErrorAt"] = self._clock()
            self.state["lastError"] = {
                "returncode": result.returncode,
                "stderrTail": result.stderr_tail,
            }
        self._persist()
        return result

    def backoff_for(self, consecutive_failures: int) -> float:
        """Capped exponential backoff. Zero failures means the normal interval."""
        if consecutive_failures <= 0:
            return self.interval_sec
        delay = self.interval_sec * (2 ** (consecutive_failures - 1))
        return min(delay, self.max_backoff_sec)

    # -- supervision ------------------------------------------------------
    def run_forever(
        self, stop_event: threading.Event, max_iterations: Optional[int] = None
    ) -> Dict:
        """Loop until `stop_event` is set, then perform one final sync.

        `max_iterations` bounds the loop for tests; production passes None.
        """
        iterations = 0
        while not stop_event.is_set():
            if max_iterations is not None and iterations >= max_iterations:
                break
            self.sync_once()
            iterations += 1
            delay = self.backoff_for(int(self.state["consecutiveFailures"]))
            if self._wait(stop_event, delay):
                break
        self.final_sync()
        return self.state

    def final_sync(self) -> SyncResult:
        """Best-effort last pass so artifacts survive VM teardown."""
        result = self.sync_once()
        self.state["finalSyncDone"] = True
        self._persist()
        return result

    def install_signal_traps(self, stop_event: threading.Event) -> None:  # pragma: no cover
        def _handler(signum, _frame):
            stop_event.set()

        for sig in (signal.SIGTERM, signal.SIGINT):
            try:
                signal.signal(sig, _handler)
            except (ValueError, OSError):
                pass  # not the main thread; caller drives stop_event directly

    def describe(self) -> str:
        parts = [f"source={self.source}", f"dest={self.dest}"]
        last_ok = self.state.get("lastSuccessAt")
        parts.append(f"lastSuccessAt={last_ok if last_ok else 'never'}")
        parts.append(f"consecutiveFailures={self.state.get('consecutiveFailures', 0)}")
        return " ".join(parts)


def main(argv: Optional[Sequence[str]] = None) -> int:  # pragma: no cover - CLI shim
    import argparse

    parser = argparse.ArgumentParser(description="Supervised additive GCS sync.")
    parser.add_argument("--source", required=True)
    parser.add_argument("--dest", required=True, help="gs://bucket/prefix")
    parser.add_argument("--state", required=True)
    parser.add_argument("--interval", type=float, default=300.0)
    parser.add_argument("--max-backoff", type=float, default=1800.0)
    args = parser.parse_args(argv)

    sync = SupervisedSync(
        source=Path(args.source),
        dest=args.dest,
        state_path=Path(args.state),
        interval_sec=args.interval,
        max_backoff_sec=args.max_backoff,
    )
    stop = threading.Event()
    sync.install_signal_traps(stop)
    print(f"[supervised-sync] {shlex.join(sync._argv)}")
    sync.run_forever(stop)
    print(f"[supervised-sync] stopped: {sync.describe()}")
    return 0


if __name__ == "__main__":  # pragma: no cover - CLI shim
    raise SystemExit(main())
