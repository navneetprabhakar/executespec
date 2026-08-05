# ExecuteSpec Studio — Current-State Parity Audit

Date: 2026-07-23  
Authority: current ExecuteSpec code and migration head V304

## Result

The platform has a strong agent/run/repository/verification/billing substrate. It does **not** yet have a complete casual app-builder product. Professional Studio is nearer because repository, agent-session, run, PR, CI, preview, deployment, cost, and evidence primitives already exist.

| Capability family | Current state | Evidence in current product | Studio gap |
|---|---|---|---|
| Agent conversation and durable sessions | Existing | Console/IDE agent session routes, durable turns and action events | repackage around Project/Task/Output; remove enterprise jargon |
| Plan gate, execution, pause/cancel/resume | Existing/partial | V2 pipeline, plan approval, run lifecycle APIs | Studio authority wording, durable Version semantics, graceful recovery |
| GitHub and Bitbucket | Existing/partial | GitHubRepoService, BitbucketRepoService, provider-neutral SCM push | Studio source onboarding, dirty-local safety, client boundary |
| Local hands, CLI, VS Code | Existing/partial | PAT-only local execution and split client contracts | Studio handoff and local/cloud continuity UI |
| Checks, test generation, on-demand run, CI | Existing/partial | background test generation, run-tests API, CI failure autofix, evidence ledger | impact selection, suite validation UI, browser/visual/a11y matrix, client-readable proof |
| Preview and deployment | Existing/partial | preview/start, Cloud Run deployment, health-related run evidence | immutable Studio Release, domains, health timeline, one-click rollback contract |
| Billing and AI credits | Existing | Free/Pro/Pro+/BYOK/Team/Enterprise, usage periods and top-ups | customer-facing Free/Pro/Max packaging and intent-bound upgrade continuity |
| Provider/model control and BYOK | Existing | account preference, org/user keys, YAML model matrix | Studio wording, Max placement, safe provider failure states |
| Share links and external collaboration | Existing/partial | public read-only links and cross-org project invites | preview share/remix semantics and professional client review scope |
| Project docs, RAG, memory, architecture | Existing/partial | pgvector docs, project memory, architecture and generated docs | casual explanation, client isolation, sanitised personal reuse |
| Visual app canvas and element editing | Missing | Figma input exists only as a source/reference | live responsive canvas, selection, contextual edit, source-consistent changes |
| Managed app data/auth/storage/functions | Missing as Studio product | platform infrastructure does not expose a safe app-resource builder | resource model, permissions, migrations, quotas, failure/recovery |
| Durable Studio versions | Partial | git/run snapshots and sync endpoints | user-facing checkpoint, compare, restore, experiment/branch |
| Studio releases and rollback | Partial | deployments exist | immutable release object, environment binding, post-deploy proof, rollback |
| Client workspace and scope baseline | Missing | org/project boundaries are not the proposed freelancer contract | hard client isolation, ScopeBaseline, ChangeRequest, offboarding |
| Handoff bundle and client approval | Missing | reports/docs/export pieces exist | review package, mapped feedback, approval record, complete handoff |
| Verified gamification | Missing | no Skill/Quest/Achievement product objects | evidence-backed milestones, opt-out, no spend/activity incentives |

## Conservative counts

| Classification | Requirement families | Meaning |
|---|---:|---|
| Existing | 2 | underlying product contract substantially exists |
| Existing/partial | 13 | strong primitive exists but Studio packaging or recovery is incomplete |
| Partial | 6 | fragments exist; end-to-end contract does not |
| Missing | 7 | new product capability required |

The exact 173-row matrix inherits these family statuses. No item is labelled “existing” merely because a similarly named backend endpoint exists.

