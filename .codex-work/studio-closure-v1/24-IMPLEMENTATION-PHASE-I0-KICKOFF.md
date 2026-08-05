# ExecuteSpec Studio — Implementation Phase I0 Kickoff

Date started: 2026-07-23  
Checkpoint: `STUDIO-CP-012`  
Phase: `I0 — Contract and repository foundations`  
Status: **planning only; not started; explicit coding approval required**

> Rollback note: repository mutations made under CP-011 were not explicitly authorised and have
> been fully removed. This document is a future implementation plan, not evidence that I0 started.

## 1. I0 exit contract

I0 is complete only when:

1. stable Studio domain and event schemas compile in every consumer;
2. Core, Service, Extension, UI, and Analytics dependency boundaries are enforceable;
3. service, UI, and extension clients are generated from one versioned API schema;
4. compatibility translation supports current `PRO_PLUS` and BYOK storage without exposing `PRO_PLUS` or `Pro+` to Studio customers;
5. `AT-<requirement-id>` is enforced as the acceptance-test tagging convention;
6. reverse-dependency tests pass;
7. no customer-visible Studio path contains `PRO_PLUS`.

This checkpoint defines possible I0 exits only. It does not start implementation or authorise code,
migration, configuration, generated-client, repository-layout, or CI changes.

## 2. Current implementation inventory

Observed in the current ExecuteSpec repository:

- the backend is a single Maven/Spring Boot repository rooted at `pom.xml`;
- the protected web client lives under `frontend/` with its own `package.json` and `tsconfig.json`;
- the five target repositories/boundaries do not yet exist as enforceable top-level modules;
- CLI/VS Code clients are already split out of this repository, so the Extension boundary must be coordinated through stable contracts rather than recreated here;
- `FeatureFlagService` already provides the service-side flag mechanism;
- backend tier logic persists and evaluates `PRO_PLUS` and BYOK in multiple entitlement/billing paths;
- frontend auth/API types still expose raw `PRO_PLUS` internally, including `frontend/src/stores/useAuthStore.ts` and `frontend/src/lib/api/core.ts`;
- `EntitlementResolver` can currently render `PRO_PLUS` as `Pro+`, which is forbidden in Studio presentation;
- the authoritative public OpenAPI document is referenced from the separate documentation repository rather than available as a root checked-in contract in this worktree;
- no current `AT-STU-*` / `AT-PRO-*` convention was found in the inspected source.

## 3. Boundary decision started

The initial extraction must preserve this dependency graph:

```text
UI ---------> Core contracts <--------- Service
Extension --> Core contracts <--------- Service APIs
Analytics <----- redacted events ----- UI / Service / Extension
```

Prohibited edges:

- Core → Service/UI/Extension/Analytics;
- UI Studio → Enterprise route or shell packages;
- Analytics → synchronous product decisions;
- Service → UI presentation types;
- Extension → managed-cloud implementation internals.

Until physical repository extraction is ready, equivalent top-level package boundaries and architecture tests are required. Moving files without dependency tests does not satisfy I0.

## 4. Initial contract freeze

The first Core contract version will freeze these public names:

### Product tiers

```text
StudioTier = FREE | PRO | MAX
KeySource = MANAGED | OWN_KEYS | HYBRID
GraduationTarget = TEAM | ENTERPRISE
```

Compatibility mapping remains an adapter concern:

```text
FREE     <-> FREE
PRO      <-> PRO
MAX      <-> PRO_PLUS
OWN_KEYS <-> BYOK key-source/billing route
```

`PRO_PLUS`, `Pro+`, and BYOK-as-tier are not legal Studio presentation values.

### Durable task vocabulary

```text
READY | WORKING | NEEDS_INPUT | COMPLETE | NEEDS_ATTENTION | PAUSED
```

Internal pipeline states remain service-private and map deterministically to this vocabulary.

### Event envelope

Every Studio event contract must include:

```text
schemaVersion
eventId
occurredAt
studioSurface
tier
personaMode
projectId
taskId?
source
stateBefore
stateAfter
outcome
recoveryUsed
latencyBucket
redactedErrorClass
```

The Analytics consumer must reject prompt text, code, secrets, client names, repository contents, document bodies, and unredacted errors.

### Acceptance tag

```text
AT-STU-<number>
AT-PRO-<number>
```

The tag is derived from the stable requirement ID and must appear in test metadata and emitted release evidence. Screen IDs such as D59 or M03 are design evidence, not acceptance identifiers.

## 5. I0 execution register

| Work item | State | Immediate evidence |
|---|---|---|
| I0.1 Current package/API/tier inventory | planning evidence only | read-only inventory may be refreshed after UX approval |
| I0.2 Core domain/event schema freeze | not started | proposed vocabulary remains in UX/product documents only |
| I0.3 Five-boundary module/repository layout | not started | conceptual dependency graph only |
| I0.4 Versioned API source of truth | not started | no Studio schema exists in the repository |
| I0.5 Generated Java/TypeScript/extension clients | not started | no generator or generated client exists |
| I0.6 Studio tier compatibility adapter | not started | mapping remains a product decision only |
| I0.7 Studio feature flags | not started | no Studio migration or flag exists |
| I0.8 `AT-*` tagging and CI enforcement | not started | naming remains a proposed acceptance convention |
| I0.9 Reverse-dependency and presentation-leak tests | not started | no Studio architecture/test gate exists |

## 6. First implementation slice

The first safe coding slice should be narrow and reversible:

1. introduce Core `StudioTier`, `KeySource`, Task-state, and event-envelope schemas;
2. add a service compatibility mapper from persisted tier/key-source values;
3. expose Studio-safe presentation values through the versioned API;
4. generate Java and TypeScript clients;
5. add tests that fail on customer-visible `PRO_PLUS` / `Pro+`;
6. add `AT-STU-*` and `AT-PRO-*` tagging support;
7. only then create the Studio route tree in I1.

No production file was moved or renamed at kickoff. Repository extraction begins only after the schema source of truth and dependency-test mechanism are fixed, otherwise five repositories would multiply drift rather than remove it.

## 7. CP-011 rollback record

The following briefly existed because “go ahead” was incorrectly interpreted as permission to code:

- canonical JSON Schema at `contracts/studio/v1/studio-contracts.schema.json`;
- dependency-free Java Core types under `studio/core/v1`;
- generated TypeScript mirror under `frontend/src/lib/contracts/studio/v1.ts`;
- deterministic generator and `--check` drift gate;
- pure compatibility adapter under `studio/compat/v1`;
- explicit `PRO_PLUS -> MAX`, `BYOK -> PRO + OWN_KEYS`, and reverse mappings;
- fail-closed handling for persisted legacy `MAX`, `TEAM`, `ENTERPRISE`, and unknown tiers;
- ArchUnit dependency rules for Core and compatibility packages;
- V340 dark rollout flag with no route or customer behavior change.

Those files, scripts, tests, package changes, migration, and repository documentation changes were
removed under CP-012. The commands below are historical evidence that the temporary slice compiled;
they are not evidence of current implementation:

```text
npm --prefix frontend run contracts:studio:check       PASS
npm --prefix frontend run format:check                 PASS
npm --prefix frontend run build                        PASS
npm --prefix frontend run lint                         PASS
mvn -DskipTests compile                                PASS
mvn -Dtest=StudioTierCompatibilityTest,StudioContractParityTest test  PASS
mvn -Dtest=FlywayMigrationInventoryTest,FlywayCompatibilityContractTest test  PASS
mvn spotless:check                                     PASS
```

No I0 item is active. Before any I0 work begins:

- the user must state explicitly that UX is accepted and coding may begin;
- implementation scope and target repository must be reconfirmed;
- only then may the earlier I0 plan be reconsidered.
