#!/usr/bin/env python3
"""B.2 RCA: how did SWE-bench Pro generation runs actually terminate?

Counts worker terminal outcomes (from pipeline_run.status_reason = 'BENCH_GEN:<OUTCOME>')
for benchmark-source runs, cross-tabbed with model and — when benchmark tracking rows
exist — with verifier reward. Purpose: quantify how many "model failures" were actually
harness terminations (COMPLETED_BY_STAGNATION / STOPPED_WITHOUT_COMPLETE / iteration or
wall-clock caps) vs clean completeTask calls. See finding #2 in
docs/plans/swe-bench-pro-harness-adversarial-assessment-2026-07-04.md.

The benchmark VM is being torn down (2026-07-04), so this targets a DB DUMP or any
reachable Postgres with the copied data:

  # against a restored dump / local postgres
  ./rca-stagnation-outcomes.py --dsn postgresql://user:pass@localhost:5432/executespec

  # or against plain-SQL/custom dumps without restoring: restore first, e.g.
  #   createdb es_dump && pg_restore -d es_dump dump.tar   (custom format)
  #   createdb es_dump && psql es_dump < dump.sql          (plain format)

Requires: psycopg2 or psycopg (pip install psycopg2-binary). Read-only queries.
"""

import argparse
import json
import sys
from collections import defaultdict


def connect(dsn):
    try:
        import psycopg2  # type: ignore

        return psycopg2.connect(dsn)
    except ImportError:
        try:
            import psycopg  # type: ignore

            return psycopg.connect(dsn)
        except ImportError:
            sys.exit("need psycopg2-binary or psycopg: pip install psycopg2-binary")


def fetch(conn, sql, args=()):
    with conn.cursor() as cur:
        cur.execute(sql, args)
        cols = [d[0] for d in cur.description]
        return [dict(zip(cols, r)) for r in cur.fetchall()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", required=True, help="postgres DSN of the dump/copy")
    ap.add_argument(
        "--since",
        default="2026-06-25",
        help="only runs created on/after this date (default covers the Pro sample-20 era)",
    )
    ap.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    args = ap.parse_args()

    conn = connect(args.dsn)

    runs = fetch(
        conn,
        """
        SELECT run_id, worker_model, ai_provider, status, status_reason, created_at
        FROM pipeline_run
        WHERE source_type LIKE 'benchmark%%'
          AND created_at >= %s
        ORDER BY created_at
        """,
        (args.since,),
    )
    if not runs:
        sys.exit("no benchmark-source pipeline_run rows found — check --since / the dump")

    # Optional: verifier truth from the in-app tracking tables (may not cover every run).
    rewards = {}
    try:
        for r in fetch(
            conn,
            """
            SELECT es_run_id, reward FROM benchmark_task_result
            WHERE es_run_id IS NOT NULL
            """,
        ):
            rewards[r["es_run_id"]] = r["reward"]
    except Exception:
        conn.rollback()  # tracking tables absent in this dump — outcome-only mode

    HARNESS_TERMINATIONS = {
        "COMPLETED_BY_STAGNATION",
        "STOPPED_WITHOUT_COMPLETE",
        "ITERATION_LIMIT_EXCEEDED",
        "WALL_CLOCK_EXCEEDED",
        "BUDGET_EXCEEDED",
        "ERROR",
    }

    by_model = defaultdict(lambda: defaultdict(int))
    rows = []
    for r in runs:
        reason = (r["status_reason"] or "").strip()
        outcome = reason[len("BENCH_GEN:") :] if reason.startswith("BENCH_GEN:") else reason or "?"
        model = r["worker_model"] or r["ai_provider"] or "?"
        reward = rewards.get(r["run_id"])
        by_model[model][outcome] += 1
        rows.append(
            {
                "run_id": r["run_id"],
                "model": model,
                "outcome": outcome,
                "status": r["status"],
                "reward": reward,
                "created_at": str(r["created_at"]),
            }
        )

    if args.json:
        print(json.dumps({"runs": rows, "by_model": {m: dict(o) for m, o in by_model.items()}}, indent=2))
        return

    print(f"benchmark-source runs since {args.since}: {len(rows)}\n")
    for model, outcomes in sorted(by_model.items()):
        total = sum(outcomes.values())
        harness = sum(v for k, v in outcomes.items() if k in HARNESS_TERMINATIONS)
        print(f"== {model}  (n={total}, harness-terminated={harness} = {100*harness/total:.0f}%)")
        for outcome, n in sorted(outcomes.items(), key=lambda kv: -kv[1]):
            tag = "  <-- HARNESS" if outcome in HARNESS_TERMINATIONS else ""
            print(f"   {outcome:<28} {n}{tag}")
        print()

    # The verdict line the assessment cares about:
    fails_with_reward = [r for r in rows if r["reward"] == 0]
    if fails_with_reward:
        ht = sum(1 for r in fails_with_reward if r["outcome"] in HARNESS_TERMINATIONS)
        print(
            f"of {len(fails_with_reward)} verifier-failed runs, {ht} "
            f"({100*ht/len(fails_with_reward):.0f}%) were harness terminations, "
            f"not clean completeTask calls"
        )
    else:
        print("(no verifier rewards joined — outcome distribution only)")


if __name__ == "__main__":
    main()
