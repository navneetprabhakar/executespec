"""Recorded benchmark concurrency profile.

WHY THIS FILE EXISTS
--------------------
`run-tb.sh` has defaulted to `CONCURRENCY=4` since the first Terminal-Bench
campaign. That default was chosen operationally, not derived from a controlled
saturation measurement of RAM / CPU / provider rate limits / Postgres / Redis /
Harbor. A default that has never failed is *not* proof that the value is safe;
it is only evidence that it has not yet been observed to fail on one machine
shape, for one suite, at one dataset size.

This module makes that distinction explicit and machine-readable so callers can
print an honest provenance line instead of implying the number is validated.

IT DELIBERATELY DOES NOT CHANGE ANY DEFAULT. `DEFAULT_CONCURRENCY` is still 4.
Changing the operational default requires a real saturation measurement on the
benchmark VM, recorded here with `evidence=MEASURED`.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional

# The historical operational default. Unchanged on purpose.
DEFAULT_CONCURRENCY = 4

# Evidence strength, weakest to strongest.
EVIDENCE_UNMEASURED = "UNMEASURED"  # no data at all; inherited default
EVIDENCE_OBSERVED = "OBSERVED"  # ran without observed failure; not a saturation test
EVIDENCE_MEASURED = "MEASURED"  # controlled saturation measurement recorded below

_ORDER = {EVIDENCE_UNMEASURED: 0, EVIDENCE_OBSERVED: 1, EVIDENCE_MEASURED: 2}


@dataclass(frozen=True)
class ConcurrencyRecord:
    """One suite's recorded concurrency and the strength of its evidence."""

    suite: str
    concurrency: int
    evidence: str
    machine_shape: str
    recorded_on: str
    note: str

    @property
    def proven(self) -> bool:
        """True only for a controlled saturation measurement."""
        return self.evidence == EVIDENCE_MEASURED

    def provenance_line(self) -> str:
        prefix = "" if self.proven else "UNPROVEN: "
        return (
            f"{prefix}concurrency={self.concurrency} suite={self.suite} "
            f"evidence={self.evidence} shape={self.machine_shape} "
            f"recorded={self.recorded_on} — {self.note}"
        )


# ---------------------------------------------------------------------------
# Recorded values. Add a row (or raise an existing row's evidence to MEASURED)
# only when you have the artifact to back it.
# ---------------------------------------------------------------------------
RECORDS: Dict[str, ConcurrencyRecord] = {
    "terminal-bench-2-1": ConcurrencyRecord(
        suite="terminal-bench-2-1",
        concurrency=4,
        evidence=EVIDENCE_OBSERVED,
        machine_shape="e2-standard-16 / 1TB pd-ssd / us-east4-a",
        recorded_on="2026-07-18",
        note=(
            "K=1 89-task internal campaign completed at 4 concurrent trials with no "
            "observed OOM or Harbor scheduling failure. This is an absence of observed "
            "failure, NOT a saturation measurement: the ceiling was never probed, and "
            "provider rate-limit / Postgres / Redis headroom at higher concurrency is "
            "unknown. Do not cite this as proof that 4 is optimal or that 8 is unsafe."
        ),
    ),
    "swebench-lite": ConcurrencyRecord(
        suite="swebench-lite",
        concurrency=DEFAULT_CONCURRENCY,
        evidence=EVIDENCE_UNMEASURED,
        machine_shape="unknown",
        recorded_on="",
        note=(
            "Inherits the Terminal-Bench default with no supporting evidence. SWE-bench "
            "cleanroom containers have a different memory and disk profile than Harbor "
            "task containers; 4 is a guess here."
        ),
    ),
    "swebench-pro": ConcurrencyRecord(
        suite="swebench-pro",
        concurrency=DEFAULT_CONCURRENCY,
        evidence=EVIDENCE_UNMEASURED,
        machine_shape="unknown",
        recorded_on="",
        note="Inherits the default with no supporting evidence.",
    ),
    "programbench": ConcurrencyRecord(
        suite="programbench",
        concurrency=DEFAULT_CONCURRENCY,
        evidence=EVIDENCE_UNMEASURED,
        machine_shape="unknown",
        recorded_on="",
        note="Inherits the default with no supporting evidence.",
    ),
}


def resolve(suite: str) -> ConcurrencyRecord:
    """Return the recorded concurrency for a suite.

    Unknown suites get the inherited default with UNMEASURED evidence rather than
    an error, so a new suite cannot silently acquire an implied guarantee.
    """
    record = RECORDS.get(suite)
    if record is not None:
        return record
    return ConcurrencyRecord(
        suite=suite,
        concurrency=DEFAULT_CONCURRENCY,
        evidence=EVIDENCE_UNMEASURED,
        machine_shape="unknown",
        recorded_on="",
        note=(
            "No record for this suite. Inheriting the operational default of "
            f"{DEFAULT_CONCURRENCY}, which is unproven here."
        ),
    )


def at_least(evidence: str, minimum: str) -> bool:
    """True when `evidence` is at least as strong as `minimum`."""
    return _ORDER.get(evidence, -1) >= _ORDER.get(minimum, 99)


def describe(suite: str, requested: Optional[int] = None) -> str:
    """Human-readable provenance for logs.

    When the caller overrides the recorded value, the override is reported as
    explicitly unproven regardless of the record's own evidence strength.
    """
    record = resolve(suite)
    if requested is None or requested == record.concurrency:
        return record.provenance_line()
    return (
        f"UNPROVEN: concurrency={requested} suite={suite} evidence=OPERATOR_OVERRIDE "
        f"(recorded value is {record.concurrency} with evidence={record.evidence}). "
        "No measurement backs the overriding value."
    )


if __name__ == "__main__":  # pragma: no cover - operator convenience
    for name in sorted(RECORDS):
        print(describe(name))
