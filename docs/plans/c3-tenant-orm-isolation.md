# C3 — tenant ORM read-isolation — handoff

> **Status:** DEFERRED (user: "skip for now, go in detail later"). Audit item **C3**
> (`docs/audit/FINDINGS-2026-05-29.md`). This doc scopes the detailed session.
> **Hard requirement already met:** C2 (app-layer access guards) is the finding's stated hard
> requirement and it is **closed** — `AccessGuardCoverageTest` pins every tenant-scoped controller
> method to a guard + write-handlers to `assertCanWrite`, `KNOWN_LEGACY_DRIFT` is empty (35/35). C3
> is **defense-in-depth on top of that**, not a missing control.

## What C3 is

A query that forgets its `orgId`/`projectId` predicate today leaks cross-tenant rows — the
app-layer guard catches the *endpoint*, but a new repository method or an ad-hoc query is only as
safe as its author. C3 pushes the tenant predicate **down to the ORM** so it's automatic: a
Hibernate `@FilterDef`/`@Filter` enabled per-session from the authenticated org, applied to every
tenant-owned entity. Even a forgotten `WHERE org_id = ?` then returns only the caller's rows.

## Why it's high-risk (and was deferred, not rushed)

A blanket `@Filter` across the ~78 entities can **silently** break correct behaviour — the failure
mode is "returns fewer rows than expected," which tests rarely assert and which looks like an empty
list, not an error. The landmines, all of which need explicit exemptions:

1. **SUPER_ADMIN global reads** — admin dashboards, payment audit, tier console read cross-org by
   design. The filter must be *disabled* on these paths.
2. **Public share-links (V175/sharing)** + **shared report tokens** — read a run/report with **no
   authenticated org** in context. The filter would null-out the org and return nothing.
3. **Cross-org `ProjectMember` reads (V175 + V198)** — a PROJECT_VIEWER from org B legitimately
   reads org A's project. A naive single-org filter breaks this — the predicate must be
   "org_id IN (my org + projects I'm a member of)," not "org_id = my org."
4. **Async / system threads** — the pipeline runs on virtual threads with a *captured* (or absent)
   SecurityContext; cron jobs (`MonthlyRunCounterResetJob`, cleanup sweepers, `StartupRunResumer`)
   have no user context at all. The filter must default to *disabled* (not empty-org) off-request.
5. **Native queries + JPQL aggregates** — `@Filter` only applies to entity HQL loads, **not**
   native SQL or some projection queries (e.g. the `topUsersByRunCount`/`aggregateCostAndTokens`
   `Object[]` queries). Those keep needing explicit predicates; the filter is not a substitute.

## Safe rollout (the detailed session)

1. **Define the filter on a tenant-scoped base** (or per-entity): `@FilterDef(name="tenantFilter",
   parameters=@ParamDef(name="orgId", type=String))` + `@Filter(name="tenantFilter",
   condition="org_id = :orgId")` on entities that carry `org_id`.
2. **Enable it from one chokepoint** — a Hibernate `Session`-opening interceptor / an
   `@Around` on the OSIV boundary / a `Filter`-enabling aspect — that reads the org from
   `SecurityContext` and enables the filter, **and explicitly does NOT enable it** for the exempt
   paths (admin, public-share, system threads). Model the exemption set on the existing
   `READ_ONLY_WRITE_HANDLERS` allowlist pattern in `AccessGuardCoverageTest`.
3. **Roll out entity-by-entity behind a flag** (`security.orm-tenant-filter.enabled`, default OFF),
   highest-value entities first (`PipelineRun`, `AgentTask`, `Project`, `ValidationReport`).
4. **Test hard, both directions** — for each enabled entity: (a) a same-org read returns rows,
   (b) a cross-org read returns **zero**, (c) SUPER_ADMIN/share-link/ProjectMember paths still
   return the expected rows. An ArchUnit-style pin that every `org_id`-bearing entity is either
   `@Filter`-ed or explicitly allowlisted, mirroring `AccessGuardCoverageTest`.
5. **Leave native/aggregate queries with explicit predicates** — document that `@Filter` is not
   their net.

## Recommendation

Do it as a dedicated, heavily-tested session, one entity tier at a time, flag-gated. Until then C2
remains the enforced control (and it is comprehensive at the app layer). The value of C3 is catching
the *next* forgotten predicate before it ships — worth it, but only if rolled out with the
exemptions above, because a silent over-filter is worse than the gap it closes.
