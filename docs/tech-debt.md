# Tech Debt Register

> Current debt register after the 2026-06-10 cleanup. Keep this focused on live risk, not historical audit narrative.

## High

| Item | Risk | Next action |
| --- | --- | --- |
| Preview heuristic stack | `DevDockerfileGenerator` and legacy preview modes are large, security-sensitive, and hard to reason about. | Continue preview rebuild; delete old code only when replacement phase passes. |
| Benchmark proof gap | Dashboard/tracking exists, but live harness event hooks and VM-side campaigns are not fully proven. | Close benchmark Phase 3 and run first controlled campaign. |
| Payment live proof | Unit tests pass, but gateway abandonment/webhook ordering still needs sandbox proof. | Run Stripe/Razorpay sandbox matrix. |
| Tenant ORM isolation | App-layer guards are tested, but ORM-level defense-in-depth remains deferred. | Execute C3 plan entity by entity behind flag. |

## Medium

| Item | Risk | Next action |
| --- | --- | --- |
| Residual RunOrchestrator complexity | Split reduced size, but run-end orchestration remains dense. | Continue extraction only around real seams. |
| Frontend API/core size | Shared frontend transport/types remain large and warning-prone. | Split by domain and add unit tests where churn occurs. |
| Dead Signal-era residue | Some `signal/` shared components and variant branches may now be unused. | Use import graph, delete in small verified commits. |
| Deprecated push endpoint | `push-github` compatibility path remains. | Migrate callers to SCM-neutral path, then remove. |
| Lazy dependency cycles | `ObjectProvider` use marks cycles in activity/contracts/standards areas. | Break cycles when touching those services. |
| Swallowed exceptions | Some lint/integration paths still swallow exceptions or log too weakly. | Replace with explicit handling/logging. |

## Low

| Item | Risk | Next action |
| --- | --- | --- |
| Plan-doc sprawl | Many `docs/plans` files are partially shipped; execution-only review/cleanup artifacts were removed from `docs/plans` on 2026-06-20. | Keep shipped behavior in `docs/features/` or reason-log entries, and delete stale plans only after a link scan. |
| Root doc churn | Some operational docs may still mention historical tiers/paths. | Refresh opportunistically when touching that area. |
| Branch hygiene | Old remote branches can obscure active work. | Prune after merge/audit. |

## Recently Resolved

- `/api/v2/test/**` unauthenticated production exposure.
- Cross-org write guard holes caught by access-guard coverage.
- SupplyChainScanner advertised-but-unwired defense layer.
- Saga orphan branch compensation.
- Late status writes clobbering cancelled/paused runs.
- Several billing/top-up/payment integrity bugs from the review batch.
- Legacy audit/review working docs and generated audit reports removed from the current docs surface.
