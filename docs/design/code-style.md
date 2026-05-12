# ExecuteSpec — code style policy

Last updated 2026-05-12 (1.0.0-M1).

## Source-of-truth

| Language    | Specification                                            | Mechanical enforcer |
|-------------|----------------------------------------------------------|---------------------|
| Java        | [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) | `mvn spotless:check` (CI gate) — auto-fix via `mvn spotless:apply` |
| TypeScript  | [Google TypeScript Style Guide](https://google.github.io/styleguide/tsguide.html) | `npm run format:check` + `npm run lint` (CI gate) — auto-fix via `npm run format` + `npm run lint -- --fix` |
| CSS / Markdown / YAML / JSON | Prettier defaults                       | `npm run format`    |

The two style guides are the canonical reference. Anything not enforced
mechanically below is **expected to follow the guide as written**. Reviewers
should call it out in PR review when style drifts.

## Tooling

### Java (`pom.xml`)

`spotless-maven-plugin` with `googleJavaFormat` is wired into the `verify` phase. PRs that skip `mvn spotless:apply` will fail CI.

```bash
# Auto-fix the codebase
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spotless:apply

# Pre-commit check (matches CI)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spotless:check
```

What spotless covers:
- Line wrapping at 100 columns, +2 indent, +4 continuation
- K&R braces, whitespace rules, single-statement-per-line
- Import ordering (java → javax → jakarta → com → org → other), then static
- Removal of unused imports
- Modifier order (`public protected private abstract static final ...`)
- Annotation placement on its own line per Google §3.3.4

### TypeScript (`frontend/package.json`)

`prettier` + `eslint` + `@typescript-eslint/*` are dev dependencies. The frontend Vite build does not run lint by default; CI runs `npm run lint` and `npm run format:check` separately so a noisy refactor doesn't block typecheck.

```bash
# Auto-format
npm --prefix frontend run format

# Auto-fix lint violations
npm --prefix frontend run lint -- --fix

# Pre-commit checks (match CI)
npm --prefix frontend run format:check
npm --prefix frontend run lint
```

What prettier covers: quotes, semicolons, indent, line length, trailing commas, JSX props formatting.

What ESLint covers: `no-var`, `eqeqeq` (`===`), `no-unused-vars`, no `var`, naming-conventions, React hooks rules, banned features.

## Documented deviations

These rules in the canonical guides are **deliberately not enforced** in this codebase. Each has a stated rationale; future contributors should not "fix" them in passing.

### Java

#### D-J1 · Wildcard imports retained

Google §3.3.1 prohibits `import …*;`. ExecuteSpec retains conventional Spring + JUnit + Java-standard wildcards (e.g. `org.springframework.web.bind.annotation.*`, `org.junit.jupiter.api.*`, `static org.junit.jupiter.api.Assertions.*`, `java.util.*`). Spotless's `removeUnusedImports` step does not expand wildcards, and a one-shot project-wide expansion would touch ~217 files for marginal benefit. New code should prefer specific imports; existing wildcards stay until the file is naturally edited.

**Future cleanup**: the OpenRewrite `org.openrewrite.staticanalysis.NoWildcardImports` recipe (with `rewrite-static-analysis` dependency added) can do this in a follow-up PR if the team decides the cleanup is worth it.

#### D-J2 · Javadoc depth

Google §7.3 says Javadoc is required on every visible class/member. ExecuteSpec requires it on:
- Top-level classes
- Public REST controllers (every endpoint method)
- Service-layer public methods that aren't trivially named (`save`, `findById` etc. don't need it)

Lombok-generated getters/setters and obvious overrides (`@Override toString()`) don't need Javadoc. Adding mechanical "Returns the X." docstrings on every getter would dilute the signal-to-noise ratio of the docstrings that do exist and matter.

#### D-J3 · `@VisibleForTesting`

Google guide doesn't mandate this annotation but the codebase uses package-private visibility liberally to expose hooks for tests. New code should add a `// VisibleForTesting` comment when intentionally widening visibility past what runtime callers need.

### TypeScript

#### D-T1 · Default exports for React components

Google TS Style §6.4 prohibits `export default`. ExecuteSpec uses default exports for top-level React components — `export default function LoginPage()`, `export default function V3EmptyProject()`, etc. — because:
1. The React community convention (Next.js routes, Vite chunk-splitting heuristics, every `lazy()` import) treats default exports as the standard contract.
2. Migrating ~200 component files + every `import LoginPage from …` would be a 400+ file mechanical change that produces zero functional improvement.
3. Named exports are still required for non-component utilities, hooks, types, and constants.

ESLint's `import/no-default-export` rule is **off** for `*.tsx` and on for `*.ts` (helpers / hooks / lib code).

#### D-T2 · `any` policy

Google guide says "avoid `any` entirely". ExecuteSpec config:
- `@typescript-eslint/no-explicit-any: warn` — new `any` is flagged but doesn't fail the build
- `@typescript-eslint/no-unsafe-*: off` — strict-mode unsafe rules are disabled until the existing `any`-soup gets cleaned

Existing `any` usage stays until each call-site is naturally edited. New code using `any` requires an explicit `// eslint-disable-next-line` with a one-line rationale.

#### D-T3 · `interface` vs `type` aliases

Google §3.4 says prefer `interface` for object shapes, `type` for unions/primitives/tuples. ExecuteSpec doesn't gate this. Both are accepted; pick whichever reads cleaner at the call-site. Reviewers may suggest one over the other but it's never blocking.

#### D-T4 · JSDoc on internal types

Google guide expects JSDoc on all public exports. ExecuteSpec requires it on:
- Public utility functions (anything in `lib/`)
- Hooks (anything in `hooks/`)
- Types/interfaces consumed across modules

Component-internal helpers, prop types, and state hooks don't need JSDoc. Same signal-to-noise rationale as D-J2.

## When the deviations should change

If the team grows past 5 engineers OR the codebase ships through a procurement audit that hard-blocks on Google style compliance, revisit each `D-*` row. The right time to invest in expanding wildcards / migrating off default exports / cleaning `any` is when there's a concrete external forcing function — not as a stand-alone polish pass.
