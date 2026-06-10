# ExecuteSpec Feature Docs

> Current-state feature documentation. These files replace stale point-in-time review and audit docs as the first stop for product capability questions.

Use these when you need to understand what the product supports today, where the implementation lives, and what is still gated or not proven live.

| Feature area | Doc | Primary code |
| --- | --- | --- |
| Run execution | [`run-execution.md`](run-execution.md) | `integration/`, `engine/`, `harness/`, `reviewer/`, `tool/`, `report/` |
| Project workbench | [`project-workbench.md`](project-workbench.md) | `project/`, `codeintel/`, `memory/`, `contracts/`, `standards/`, `frontend/src/ide/` |
| Models and BYOK | [`models-and-routing.md`](models-and-routing.md) | `agent/`, `router/`, `cost/`, `auth/*AiKey*`, `application.yml` |
| Billing and access | [`billing-and-access.md`](billing-and-access.md) | `billing/`, `auth/`, `sharing/`, `ratelimit/` |
| Benchmarks | [`benchmarks.md`](benchmarks.md) | `benchmark/`, `terminalbench/`, `swebench/`, `programbench/`, `frontend/src/ide/IdeBenchmark.tsx` |

## Source-of-truth order

1. Code and migrations.
2. `application.yml` for model/pricing/routing defaults.
3. These feature docs.
4. `docs/ARCHITECTURE.md`, `docs/CODEMAP.md`, and `docs/WORKFLOWS.md` for system-wide context.
5. `docs/plans/*` only for future or partially implemented design work.

## Maintenance rule

When a feature ships, update the matching feature doc in the same change. Plans can then be deleted or reduced to only the unshipped follow-up.
