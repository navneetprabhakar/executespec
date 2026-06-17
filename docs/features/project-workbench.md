# Project Workbench

## What Exists

The project workbench is the persistent workspace layer around runs. It provides linked repositories, generated code intelligence, uploaded document RAG, project memory, project chat, tech-debt rollups, code review, PR review, sharing, contracts, standards, and project-scoped activity.

## Feature Map

| Capability | Status | Backend | Frontend |
| --- | --- | --- | --- |
| Project CRUD and repo linking | Live | `auth/service/ProjectService.java`, `repo/` | `frontend/src/ide/IdeProjects.tsx`, `IdeProjectTabs.tsx` |
| Per-project codemap | Live | `codeintel/CodeMapGenerator.java`, `ProjectCodeMap` | Project code/architecture tabs |
| Per-repo architecture docs | Live | `codeintel/RepoArchitectureGenerator.java` | Architecture tab |
| Cross-repo architecture synthesis | Live | `codeintel/ArchitectureDocGenerator.java` | Architecture tab |
| Uploaded-doc RAG | Live | `project/ProjectDocumentService`, `rag/`, pgvector | Project settings/docs surfaces |
| Generated docs | Live | `ProjectDocGeneratorService` | Project docs/download surfaces |
| Project memory ledger and compaction | Live | `memory/RunLedgerAgent`, `MemoryCompactionService` | Project overview/chat context |
| Project chat tools | Live | `memory/chattools/ProjectChatTools` | Project chat |
| Tech-debt rollup and category fix | Live | `project/techdebt/` | Project overview |
| PR review and autofix | Live | `prreview/` | PR review surfaces |
| Whole-repo code review and autofix | Live | `codereview/` | Code quality surfaces |
| Cross-org invites | Live, org flag gated | `sharing/ProjectInviteService` | Project members/settings |
| Public share links | Live | `sharing/ProjectShareLinkService` | Share-link surfaces |
| Contracts and standards | Partially live | `contracts/`, `standards/`, flags V210-V213 | Project enterprise surfaces |
| Tiered codemap selection | Flag-gated default OFF | `codeintel/TieredCodemapSelector` | Planner context only |

## Important Boundaries

- Project workspace files on disk are cache; DB blobs and repository state are authoritative.
- Cross-org project sharing is opt-in per org through `org.external_sharing_enabled`.
- Accepted cross-org invitees see the host project's runs, repos, and chat per their project role, and inherit the host org's highest active tier as an effective entitlement (without mutating their personal billing tier) — `EntitlementResolver`/`TierEnforcementService`, RL-2026-06-046. Private repo source listing for invitees reads through the linking user's GitHub credentials (RL-2026-06-047).
- Public share links are separate from cross-org member invites.
- Project chat tools are advisory and tool-enabled, but write actions still flow through guarded endpoints.
- Contracts/standards are partially wired; planner injection and enforcement are flag-gated surfaces, not always-on product guarantees.

## Current Gaps

- Tenant ORM read-isolation (`C3`) remains deferred; app-layer guards are the hard requirement and are tested.
- Some enterprise governance features are useful only after flags are enabled and project data exists.
- Frontend bundle splitting and frontend unit-test depth remain debt outside this feature surface.
