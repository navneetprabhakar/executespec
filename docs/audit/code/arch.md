# Audit: `arch/invariants/` — architectural-invariant enforcement (runtime; NOT the ArchUnit test pins)

Status: **DONE** · Updated 2026-05-29 · 6 files / 425 lines · all read in full

> Naming note: the INDEX headline "ArchUnit pins" is misleading — the **ArchUnit** pins (`AccessGuardCoverageTest`, etc.) live in `src/test/`, audited separately under Tier 9. This `arch/invariants/` package is the *runtime* architectural-invariant subsystem (E3.14).

## Files
- `InvariantChecker.java` (179) — text-pattern invariant checker ✓
- `InvariantController.java` (104) — list + violations REST ✓
- `ArchInvariant.java` (46) + `ArchInvariantViolation.java` (55) entities ✓ · 2 repos ✓

## What it does
Caches per-project architectural invariants from `.executespec/arch/invariants.yaml` (3 rule kinds: `exclusive-writer` = only listed services may write a table; `no-direct-call` = service A may not reference service B; `layer-isolation` = layer X may not import layer Y) and is *designed* to check them at plan-time + post-merge arch-regen, persisting `arch_invariant_violation` rows.

## Headline: the checker is built but UNWIRED — most-orphaned of the enterprise-readiness trio.
- 🟡 **`InvariantChecker` has ZERO callers** (grep-confirmed: nothing outside `arch/invariants/` references it). Unlike `contracts`/`standards`/`adr` — all wired into `RunOrchestrator` (flag-gated) — the invariant *checker* is never invoked by any pipeline. So `arch_invariant_violation` is **always empty in production**: the controller's `GET /invariants` (cached definitions) + `GET /violations` endpoints work and are tenant-gated, but no code path ever runs `check()` to produce a violation. This is closer to the dead `validator/` island than to its flag-dormant siblings — the difference is it's *small* (179 LOC of logic) and high-quality, so it's a "finish the wiring" candidate, not a "delete" one. Decision when the enterprise track activates: wire `InvariantChecker` into the plan-gate + arch-regen call sites (the entity even models both `DetectionPhase.PLAN` / `ARCH_REGEN`), or shelve it.
- 🟢 **The checker code itself is clean + secure.** `SafeYamlLoader` (no unsafe deser), pure function with a `ContentLoader` callback (unit-testable cold), reuses `contracts/DetectorUtils.matchesGlob` for `layer-isolation` (cross-package consistency). Text-pattern based — `exclusive-writer` regexes `INSERT INTO|UPDATE|DELETE FROM <table>`, the others are substring/glob matches — with the same §13 "real AST upgrade deferred" caveat as `standards/AstCheckEnforcer`. Fail-soft (parse failure → empty findings, never throws).
- 🟡 **Pattern-matching precision is coarse** (acknowledged): `no-direct-call` flags any file in the `from` service that merely *contains* the forbidden-target string anywhere (comment, string literal, unrelated token), and `exclusive-writer`'s SQL regex won't catch ORM-built writes (JPA `save()`, query builders) — only literal SQL. So even once wired it'll have false positives + false negatives until the AST upgrade. Fine for an advisory signal, not for a hard gate.
- 🟢 `InvariantController` tenant-gated (`assertCanRead` on both endpoints); forward-compat String enums (`statusEnum`/`phaseEnum`). No security defects.

## Cross-refs
Sibling enterprise-readiness subsystems (both *are* wired) → [contracts.md](contracts.md), [standards.md](standards.md), [adr.md](adr.md). Glob util shared from → [contracts.md](contracts.md) (`DetectorUtils`). True dead-island contrast → [validator.md](validator.md). Would-wire-into → [integration.md](integration.md) (`RunOrchestrator` plan gate + arch-regen). The actual ArchUnit *test* pins (`AccessGuardCoverageTest`) → Tier 9 (`src/test/`).
