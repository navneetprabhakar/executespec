# ExecuteSpec Studio — I0 Contract Slice

Checkpoint: `STUDIO-CP-012`  
Date: 2026-07-23  
Status: **ROLLED BACK — historical audit only; no current implementation**

> This document records an implementation attempt made without sufficiently explicit coding
> permission. Every repository mutation described below was removed. Nothing in this document
> authorises future coding.

## Outcome

The temporary slice described below no longer exists in the ExecuteSpec repository. It is retained
only so the session handoff explains what happened and why it was reversed.

## Contract authority

- Canonical schema: `contracts/studio/v1/studio-contracts.schema.json`.
- Java Core mirror: `src/main/java/com/navneet/executespec/studio/core/v1/`.
- Generated web mirror: `frontend/src/lib/contracts/studio/v1.ts`.
- Generator: `scripts/generate-studio-contracts.mjs`.
- Drift check: `npm --prefix frontend run contracts:studio:check`.

The schema freezes Studio tiers, billing routes, key sources, persona modes, durable task states,
the account profile, and the redacted analytics event envelope. BYOK invariants exist in both the
schema and the Java account profile.

## Persistence compatibility decision

| Persisted value | Studio projection | Reason |
|---|---|---|
| `FREE` | Free / managed route | direct individual tier |
| `PRO` | Pro / managed route | direct individual tier |
| `PRO_PLUS` | Max / managed route | current storage identity for the $100 individual plan |
| `BYOK` | Pro / BYOK route / own keys | billing and key route, not a presentation tier |
| legacy `MAX` | rejected | historical Team meaning; unsafe to relabel |
| `TEAM` / `ENTERPRISE` | rejected | different product surface |
| unknown/null | rejected | fail closed on contract drift |

Reverse translation maps Studio Max to persisted `PRO_PLUS`; no Studio-facing contract contains
`PRO_PLUS` or `Pro+`.

## Boundary enforcement

- `studio/core/v1` may depend only on Java and itself.
- `studio/compat/v1` may depend only on Java, itself, and Studio Core.
- The web contract is generated, not manually maintained.
- Schema/Java enum parity is a compiled test.
- The Studio TypeScript mirror is tested for forbidden persisted tier names.

These are the first dependency gates, not the complete five-repository boundary. Service, UI,
Extension, and Analytics enforcement remains an I0 exit requirement.

## Rollout safety

Migration V340 seeds `studio.surface.enabled=false`. The flag is intentionally unused until the I1
route shell exists. It controls exposure only; disabling it must never mutate billing identity,
shared Core behavior, or Enterprise availability.

## Verification

```text
StudioTierCompatibilityTest   PASS
StudioContractParityTest      PASS
Flyway migration inventory    PASS
Flyway compatibility gate     PASS
TypeScript generation check   PASS
Frontend format check         PASS
Frontend production build     PASS
Frontend lint                 PASS
Backend compile               PASS
Java Spotless                 PASS
```

## Current boundary

Continue UX review only. Do not create or edit application code, migrations, configuration,
generated clients, repository structure, CI, or production documentation until the user explicitly
states that the UX is accepted and coding may begin.
