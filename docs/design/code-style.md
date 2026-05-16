# Code Style

> Style guide for both halves of the ExecuteSpec codebase: the React 18 + TypeScript 5 frontend
> (`frontend/`) and the Java 21 + Spring Boot 3.5 backend (`src/main/java/...`).
>
> Source of truth: `frontend/.eslintrc.cjs`, `frontend/.prettierrc.json`, `frontend/tsconfig.json`,
> `pom.xml` Spotless block, `lombok.config`. When this doc disagrees with the linter, the linter
> wins — update this doc.

---

## TypeScript / React (frontend)

### Toolchain

| Concern        | Tool                                         | Version (per `frontend/package.json`)    |
| -------------- | -------------------------------------------- | ---------------------------------------- |
| Compiler       | `typescript`                                 | `^5.3.0`                                 |
| Linter         | `eslint`                                     | `^8.57.1`                                |
| Parser         | `@typescript-eslint/parser`                  | `^8.59.3`                                |
| Lint plugins   | `@typescript-eslint/eslint-plugin`, `eslint-plugin-react`, `eslint-plugin-react-hooks` | `^8.59.3` / `^7.35.0` / `^4.6.2` |
| Formatter      | `prettier`                                   | `^3.3.3`                                 |
| Bundler        | `vite`                                       | `^5.1.0`                                 |
| Styles         | `tailwindcss` + `@tailwindcss/postcss`       | `^4.3.0` / `^4.3.0`                      |
| Test framework | n/a — no test runner is wired in `package.json` today; verification lives on the backend side. |

The lint gate in CI is `npm --prefix frontend run format:check` (Prettier) plus
`npm --prefix frontend run lint` (ESLint). `lint` is invoked with `--max-warnings 9999`, so warnings
do not fail the build today — only errors do. Treat warnings as red anyway; CI will tighten this gate
once the warning backlog is cleared.

### `tsconfig.json` highlights

```json
{
  "target": "ES2020",
  "module": "ESNext",
  "moduleResolution": "bundler",
  "jsx": "react-jsx",
  "strict": true,
  "noUnusedLocals": false,
  "noUnusedParameters": false,
  "noFallthroughCasesInSwitch": true,
  "isolatedModules": true,
  "allowImportingTsExtensions": true,
  "resolveJsonModule": true,
  "noEmit": true,
  "baseUrl": ".",
  "paths": { "@/*": ["./src/*"] }
}
```

Notes:

- `strict: true` is on across the board. `noImplicitAny`, `strictNullChecks`,
  `strictFunctionTypes`, etc. are all enforced.
- `noUnusedLocals` and `noUnusedParameters` are intentionally `false`. We rely on ESLint
  (`@typescript-eslint/no-unused-vars`) for that signal instead — it allows the `_unused`
  prefix-to-ignore convention, which the TS compiler does not. (Deviation D-T2 — see below.)
- `@/*` alias resolves to `./src/*`. Use it for cross-folder imports
  (e.g. `import { runUtils } from '@/signal/lib/runUtils'`).
- A composite reference to `tsconfig.node.json` covers `vite.config.ts` + `tailwind.config.ts`
  so the build tools type-check separately from app code.
- `noEmit: true` — Vite handles the transpile; `tsc` is run only as a type-check gate
  (`npm run build` calls `tsc && vite build`).

### ESLint custom rules

Pulled directly from `frontend/.eslintrc.cjs`. Each rule is paired with the rationale baked into the
config or the rule itself.

| Rule                                           | Severity | Why                                                                                                       |
| ---------------------------------------------- | -------- | --------------------------------------------------------------------------------------------------------- |
| `@typescript-eslint/no-explicit-any`           | `warn`   | We keep `any` legal but conspicuous — see Deviation D-T2.                                                 |
| `@typescript-eslint/no-unused-vars`            | `warn`   | Variables and args prefixed `_` are ignored. Keeps `try { } catch (_e) {}` clean.                         |
| `react/react-in-jsx-scope`                     | `off`    | React 18 + `jsx: react-jsx` doesn't need `import React from 'react'`.                                     |
| `react/prop-types`                             | `off`    | We type props with TypeScript, never PropTypes.                                                           |
| `react-hooks/rules-of-hooks`                   | `error`  | Mechanical correctness — hooks at top of component, no conditionals.                                      |
| `react-hooks/exhaustive-deps`                  | `warn`   | Surfaces missing-deps in `useEffect` etc. Frequent false positives, so warn — not error.                  |

`extends`: `eslint:recommended`, `plugin:@typescript-eslint/recommended`, `plugin:react/recommended`,
`plugin:react-hooks/recommended`, `prettier` (last, so Prettier-conflicting rules are disabled).

`settings.react.version` is `detect` so the React plugin picks up the 18.x major.

### Prettier opinions

`frontend/.prettierrc.json`:

```json
{
  "semi": true,
  "singleQuote": true,
  "jsxSingleQuote": true,
  "trailingComma": "all",
  "printWidth": 100,
  "tabWidth": 2,
  "arrowParens": "always",
  "endOfLine": "lf"
}
```

- Always-on semicolons. Single quotes for strings and JSX attribute values
  (`<div className='signal-card'>`, not `"signal-card"`).
- `trailingComma: 'all'` — every multi-line literal gets a trailing comma. Makes diffs minimal
  when you add a new item.
- 100-column wrap, 2-space indent, LF line endings (cross-platform safe).
- `arrowParens: 'always'` — even single-arg arrows wear parens: `(x) => x * 2`.

Run `npm --prefix frontend run format` to autofix. CI runs `format:check`; a failure means run
`format` locally and commit the diff.

### Documented deviations from Google TypeScript Style

We follow Google's TS Style Guide as a base except for the items below. Citations link to the
specific lines in our config that encode the deviation.

| ID    | Deviation                                                                                | Where it lives                                       | Rationale                                                                                                                                            |
| ----- | ---------------------------------------------------------------------------------------- | ---------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| D-T1  | Default exports are allowed for top-level page / shell components (`.tsx`).              | No ESLint `import/no-default-export` rule is configured. | Page components in `frontend/src/signal/pages/*` and a few shells (`AuthLayout.tsx`, `Shell.tsx`) use default export so React Router can lazy-import them by file path. Named exports remain required everywhere else. |
| D-T2  | `any` is warn-mode, not error-mode. `unknown` is the preferred escape hatch.             | `@typescript-eslint/no-explicit-any: 'warn'`         | Some legacy adapters (e.g. the union-shape helpers in `frontend/src/signal/lib/runUtils.ts`) deliberately cast through `unknown` and accept `any` at narrow boundaries — the file's own comment block flags the trade-off. New code should still reach for `unknown`. |
| D-T3  | We use `interface` for object-prop shapes and `type` for unions / mapped / function types. | Convention, not lint-enforced.                       | `interface Foo` lets us merge a later `interface Foo extends ...`. `type Status = 'a' \| 'b'` for unions because `interface` can't express that. Google's style guide prefers `interface`; we narrow it. |
| D-T4  | JSDoc on internal types is skipped. Public/exported APIs should still carry a one-line JSDoc. | Convention, not lint-enforced.                       | Types describe themselves once they're typed. Comments on local interfaces drift; comments on exported helpers (`runUtils.ts`, `Primitives.tsx`) stay. |

### Naming conventions

| Construct                            | Convention            | Examples                                                                |
| ------------------------------------ | --------------------- | ----------------------------------------------------------------------- |
| React component file                 | `UpperCamelCase.tsx`  | `FileIcon.tsx`, `Primitives.tsx`, `Shell.tsx`                            |
| React component export               | `UpperCamelCase`      | `function FileIcon({ filename }: Props) { ... }`                         |
| Hook file                            | `useThing.ts`         | `useSignalTheme.ts`, `useProjectMeta.ts`                                 |
| Hook export                          | `useThing`            | `export function useSignalTheme(rootRef) { ... }`                        |
| Zustand store file                   | `xxxStore.ts`         | `authStore.ts`, `uiStore.ts`                                             |
| Zustand store hook export            | `useXxxStore`         | `useAuthStore`, `useUiStore`                                             |
| Plain helper module                  | `lowerCamelCase.ts`   | `runUtils.ts`, `telemetry.ts`, `api.ts`                                  |
| Constants                            | `SCREAMING_SNAKE_CASE` | `STORAGE_KEY`, `LIVE_STATUSES`, `MOBILE_TABS`                            |
| TypeScript types/interfaces          | `UpperCamelCase`      | `RunView`, `FileEntry`, `IconProps`                                      |
| CSS class names (Signal scope)       | `kebab-case`          | `.signal-card`, `.signal-row`, `.pill-dot.mint`                          |
| CSS custom property                  | `--kebab-case`        | `--signal-bg`, `--accent-blue-text`, `--motion-press`                    |

### File layout (`frontend/src/`)

```
frontend/src/
├── App.tsx                # React Router route table
├── main.tsx               # Entry point: telemetry, SW registration, theme bootstrap, render
├── index.css              # Global stylesheet — primitives → tokens → Tailwind v4
├── tokens.css             # Design tokens (Graphite + Signal palette, light + dark)
├── signal/                # Production chrome (the only active chrome)
│   ├── signal.css         #   Signal-scoped CSS — every component class
│   ├── components/        #   Reusable primitives (Card, Row, Chip, Button, Modal, ...)
│   ├── pages/             #   Route-level pages (HomePage, ResultPage, ProjectComposePage, ...)
│   ├── hooks/             #   Signal-scoped hooks (useSignalTheme, useProjectMeta, ...)
│   ├── icons/             #   Inline-SVG icon set (icons.tsx)
│   └── lib/               #   Signal-scoped helpers (runUtils.ts)
├── design-system/         # Cross-cutting design assets — primitives.css, contrast.ts
├── stores/                # Zustand stores (authStore, uiStore, ...)
├── hooks/                 # App-wide hooks (not Signal-specific)
├── lib/                   # App-wide helpers (api.ts, telemetry.ts, ...)
└── types/                 # Shared TypeScript types
```

Rules of thumb:

- New page components belong under `frontend/src/signal/pages/`.
- New reusable primitives belong under `frontend/src/signal/components/`.
- Cross-cutting helpers that aren't tied to the Signal chrome belong under
  `frontend/src/lib/`, `frontend/src/hooks/`, or `frontend/src/stores/`.
- CSS classes shipped from `signal.css` are scoped under `.signal-app` so they cannot leak
  into legacy V2/V3 shells. New Signal classes follow that convention.

### Imports

- Absolute paths via the `@/` alias for anything outside the current folder:
  `import * as api from '@/lib/api'`. Relative paths (`./foo`) for siblings.
- Side-effect imports (CSS) go last:

  ```ts
  import { Outlet } from 'react-router-dom';
  import { useSignalTheme } from '@/signal/hooks/useSignalTheme';

  import '@/signal/signal.css';
  ```

- Type-only imports use `import type` so the bundler can drop them:
  `import type { ProjectDto } from '@/lib/api'`.

### React patterns

- Functional components only. No class components (the codebase already migrated off them — see
  the `class-components-to-hooks` migration recipe for what we'd do to your code).
- Hooks at the top of the component, before any conditional return.
- Lift state to the closest common ancestor. Reach for a Zustand store
  (`frontend/src/stores/*`) only when the state is genuinely cross-page.
- Memoization (`useMemo` / `useCallback`) is opt-in, not default. Add it when a render profile
  shows it matters — don't sprinkle it.
- Side effects (network calls, subscriptions) go in `useEffect`. Cleanup the subscription with
  the returned function — see `useSignalTheme.ts` for the canonical pattern.

### Mobile-first responsive

Every UI change must verify on a 375×667 viewport (iPhone SE) and a 768×1024 viewport (iPad).
Patterns:

- Tap targets ≥ 44×44px on mobile (`.hit-target` utility, or `min-height: 44px` on the element).
- Use `100dvh` not `100vh` for full-screen layouts so the iOS Safari URL bar collapse doesn't
  cut your content. `.h-screen-dvh`, `.min-h-screen-dvh` utilities live in `index.css`.
- Respect safe-area insets: `.safe-area-bottom`, `.safe-area-top`, `.sticky-bottom-bar`.
- Form inputs need `font-size: 16px` minimum on mobile to prevent iOS auto-zoom on focus —
  the `@supports (-webkit-touch-callout: none)` block in `index.css` enforces this globally.

---

## Java (backend)

### Toolchain

| Concern        | Tool                                       | Version (per `pom.xml`)                  |
| -------------- | ------------------------------------------ | ---------------------------------------- |
| JDK            | OpenJDK                                    | 21 (`<java.version>21</java.version>`)   |
| Build          | Maven                                      | follows the wrapper                      |
| Parent BOM     | `spring-boot-starter-parent`               | `3.5.14`                                 |
| Framework      | Spring Boot                                | `3.5.x`                                  |
| LLM SDK        | Spring AI                                  | `1.1.6`                                  |
| Boilerplate    | Lombok                                     | managed (optional)                       |
| Formatter      | `com.diffplug.spotless:spotless-maven-plugin` | `3.4.0` (Dependabot bumped 2.43 → 3.4) |
| Format engine  | `google-java-format` (style `GOOGLE`)      | `1.22.0`                                 |
| Test framework | JUnit 5 (Jupiter), Mockito, Testcontainers, ArchUnit | `5.17.0` Mockito, `1.4.2` ArchUnit |
| Coverage       | JaCoCo                                     | `0.8.14`                                 |

On macOS dev: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` before any Maven command. The
project's Bash quickstart documents this explicitly, and the Spotless plugin enforces the format
during `mvn verify`.

### Formatter (Spotless)

Bound to the `verify` phase via the `spotless-check` execution. From `pom.xml`:

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>3.4.0</version>
  <configuration>
    <java>
      <includes>
        <include>src/main/java/**/*.java</include>
        <include>src/test/java/**/*.java</include>
      </includes>
      <googleJavaFormat>
        <version>1.22.0</version>
        <style>GOOGLE</style>
        <reflowLongStrings>true</reflowLongStrings>
      </googleJavaFormat>
      <removeUnusedImports/>
      <importOrder>
        <order>java,javax,jakarta,com,org,</order>
      </importOrder>
      <formatAnnotations/>
    </java>
  </configuration>
</plugin>
```

What this means in practice:

- **Format engine**: `google-java-format` with the `GOOGLE` style (4-space continuation,
  2-space indent, ~100-col soft wrap, dot at end of line for chains).
- **Long strings**: `reflowLongStrings = true` allows the formatter to break very long literal
  strings across multiple lines.
- **Unused imports**: stripped automatically. Don't leave them — Spotless will fail the build.
- **Import order**: groups in this exact order, each separated by a blank line:

  ```
  1. java.*
  2. javax.*
  3. jakarta.*
  4. com.*       (third-party + first-party com.navneet.executespec.*)
  5. org.*
  6. (everything else — trailing comma in 'java,javax,jakarta,com,org,')
  ```

- **Annotation formatting**: `formatAnnotations` normalises one-annotation-per-line on
  declarations.

Run `mvn spotless:apply` to autofix; `mvn verify` (the `spotless-check` execution) is the gate.

### Project coordinates

- `groupId`: `com.navneet`
- `artifactId`: `executespec`
- Base Java package: `com.navneet.executespec`
- Top-level package layout: flat (`com.navneet.executespec.<feature>`). The historical
  `v2.*` / `v3.*` namespace prefixes were retired in the 2026-05-07 unification pass — do not
  reintroduce them.

### Dependency injection

We use Lombok-generated constructor injection across every Spring-managed bean.

```java
@Service
@RequiredArgsConstructor
public class RunOrchestrator {

  private final SequentialExecutor sequentialExecutor;
  private final ContextStore contextStore;
  private final RunCostTracker runCostTracker;

  public RunRecord submit(RunRequest request) {
    // ...
  }
}
```

Rules:

- **Use `@RequiredArgsConstructor`** on the class. All collaborators are `private final` fields.
  Lombok generates the constructor; Spring picks it up automatically.
- **Never field-inject (`@Autowired` on a field)**. It defeats constructor immutability, makes
  the class harder to unit-test, and hides circular dependencies.
- **`@Qualifier` propagation**: `lombok.config` at the project root sets
  `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier`. This
  means `@Qualifier("namedBean")` on a `private final` field is copied through to the generated
  constructor argument, so Spring can resolve named beans (we use this for named `ChatModel`
  beans per provider).

### Records for DTOs

Public DTOs that travel over the API surface or between layers use Java records.

```java
public record RunRequest(
    String userRequest,
    String aiProvider,
    ExecutionMode executionMode,
    RunMode runMode,
    boolean tddMode,
    Double userCostCap,
    String figmaUrl,
    List<UUID> selectedRepoIds) {}
```

- Records are immutable, give you `equals`/`hashCode`/`toString` for free, and the canonical
  constructor lets you validate inputs in one place if needed.
- Use records for any DTO that holds data only. Use a regular class when behaviour belongs on
  the object (e.g. `RunOrchestrator`, services, entities — see below).
- Record components match the JSON field names that the frontend already sends — keep them
  `lowerCamelCase`. Don't add `@JsonProperty` unless you genuinely need to alias.

### Entities (JPA)

Entities are regular classes (not records — JPA needs a default constructor and mutable state).
Lombok's `@Data` / `@Builder` are common:

```java
@Entity
@Table(name = "pipeline_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineRun {

  @Id
  @GeneratedValue
  private UUID id;

  @Enumerated(EnumType.STRING)
  private RunStatus status;

  @Column(name = "verified_score")
  private Double verifiedScore;
  // ...
}
```

- Use `@Data` only on entities. On services/agents, `@RequiredArgsConstructor` is enough —
  generating `equals`/`hashCode` on a service is a bug factory.
- Use `@Builder` when callers want named-arg construction (most entities). Pair with
  `@NoArgsConstructor` (JPA requires it) and `@AllArgsConstructor` (Lombok-friendly).

### Naming conventions

| Construct                       | Suffix / convention   | Examples                                                              |
| ------------------------------- | --------------------- | --------------------------------------------------------------------- |
| Agent                           | `*Agent`              | `WorkerAgent`, `ReviewerAgent`, `OutlinePlannerAgent`                  |
| Service                         | `*Service`            | `BillingService`, `ProjectWorkspaceService`                            |
| REST controller                 | `*Controller`         | `RunController`, `BillingController`                                   |
| Security specification          | `*Spec`               | `PipelineRunSpec` (Spring Data JPA Specifications)                    |
| LLM tool                        | descriptive verb noun | `ReadFileTool`, `WriteFileTool`, `BuildProjectTool`                    |
| Repository                      | `*Repository`         | `PipelineRunRepository`                                                |
| DTO record                      | `*Dto` or `*Request` / `*Response` | `RunRequest`, `RunResponse`, `ProjectDto`                  |
| Flyway migration                | `V{n}__description.sql` | `V199__add_external_sharing_flag.sql`                                |

### Imports

The import order is enforced by Spotless (`java,javax,jakarta,com,org,`). One typical block:

```java
package com.navneet.executespec.agent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.tool.ToolRegistry;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.model.ChatModel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
```

- One blank line between groups.
- No `import package.*` wildcards — google-java-format will keep them, but every IDE config in
  the repo is set to expand them.

### Patterns that MUST be followed

These are pinned by tests, lint rules, or established convention — breaking them is a build
failure or a code-review block.

1. **All LLM calls go through `LlmRouter`** (or `FallbackLlmRouter` for the legacy non-tool agents).
   Never call provider SDKs directly. The router writes cost-tracking rows for every call.

2. **Run ownership fields stamped before the async pipeline thread starts.** Set
   `createdByUserId`, `orgId`, `projectId` on the `RunRecord` before you call
   `RunOrchestrator.submit(...)` — otherwise the async thread reads partially-initialised state
   and races on tenant guards.

3. **Sliding-window LLM context: first 2 + last 18 messages.** The conversation snapshots
   under `v2:agent-ctx:{runId}:{taskId}` keep this shape. Don't reshape it.

4. **Token-cost truncation limits**: `readFile` 8k chars, `shell` 6k chars, `diff` 5k chars.
   Constants in the relevant `*Tool.java`.

5. **Webhook signatures verified before processing.** Razorpay HMAC, Stripe `Stripe-Signature`.
   Never trust the body before the signature check returns true.

6. **Feature flags via `FeatureFlagService.isEnabled(key, ...)`** — never hardcode toggles.
   Flags live in YAML + DB; the service caches per-process.

7. **Structured logging with trace context.** Micrometer auto-populates MDC with `traceId` and
   `spanId`. Use SLF4J's `log.info("event={} runId={}", "phase.transition", runId)` so
   structured-logging encoders can pick the fields up.

8. **Async work wraps `Thread.ofVirtual().start()` runnables** in
   `ContextSnapshotFactory.captureAll().wrap(runnable)` so `traceId` and `RequestAttributes`
   survive the thread boundary.

9. **Tool-calling is provider-native.** Strict JSON schemas live in `tool/` — every tool entry
   declares its schema. Do not parse XML `<tool_call>` blocks in V2.

10. **LLM prompts live in external files.** `src/main/resources/prompts/*.md` (rollback archives
    `.txt`). Assemble via `PromptAssembler.assemble(path, vars)` — it resolves
    `{{include:fragment}}` markers from `prompts/fragments/` and substitutes `{{VAR}}`. Never
    inline a multi-page prompt as a `String` constant in an agent.

### Patterns that MUST NOT be used

- **Field injection (`@Autowired private SomeService thing;`)** — see DI section.
- **Raw `String` LLM prompts hardcoded in Java** — use the prompt files + `PromptAssembler`.
- **Direct HTTP calls to LLM providers** — go through `LlmRouter`.
- **Swallowed exceptions.** `catch (Exception e) {}` is a code-review block. Log + rethrow, log
  + translate, or handle the case explicitly.
- **Setting run ownership fields after `submitRun()` returns** — the async thread has already
  started; you'll race on tenant guards.

### Database migrations

- Flyway: `src/main/resources/db/migration/V{n}__description.sql`. Sequential, monotonic
  integer. Current head is documented in the root `CLAUDE.md`.
- **Never modify an applied migration.** Add a follow-up `V{next}__fix_blah.sql` instead.
- New migrations cross-linked from the touching package's `CLAUDE.md` and from the entity
  row that uses them. The doc-refresh checklist enforces this.
- Local check: `ls src/main/resources/db/migration/V*.sql | tail -5` before creating a new one,
  so you pick the right next number even if a feature branch is racing.

### Tests

| Layer / concern               | Framework / library                                | Notes                                                                                            |
| ----------------------------- | -------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| Unit                          | JUnit 5 Jupiter (`spring-boot-starter-test`)       | Surefire runs these on `mvn test`.                                                               |
| Mocking                       | Mockito (`5.17.0`, override SB default for Java 25) | Use `MockitoExtension` rather than the legacy runner. Prefer constructor injection over `@InjectMocks`. |
| Integration / Testcontainers  | `spring-boot-testcontainers`, `testcontainers/postgresql`, `testcontainers/junit-jupiter` | Tagged `@Tag("integration")`. `mvn test` excludes them; CI runs them under the `-Pintegration` profile. |
| Architecture                  | ArchUnit (`com.tngtech.archunit:archunit-junit5:1.4.2`) | Pins like `AccessGuardCoverageTest` enforce that every tenant-scoped controller method calls a tenant access guard. New violators fail the build. |
| Spring Security               | `spring-security-test`                             | `@WithMockUser`, `MockMvc` request-builders with auth.                                           |

Test layout mirrors source layout — `src/test/java/com/navneet/executespec/<feature>/<X>Test.java`.

The Surefire excluded groups list is `e2e,integration,chaos`, so `mvn test` runs unit + ArchUnit
tests only. Integration tests run under `-Pintegration`.

### JaCoCo

- Plugin: `org.jacoco:jacoco-maven-plugin:0.8.14`.
- Bound to the `test` phase: `prepare-agent` + `report`.
- Excluded packages: `com.navneet.executespec.api.dto.**`, `com.navneet.executespec.**.dto.**`,
  `com.navneet.executespec.ExecutespecApplication.class`. DTOs are pure data — coverage there
  is noise.

---

## Common to both

### Commit hygiene

- One feature per branch. Ship every slice on the same branch — one PR per work session, not per
  slice.
- Doc refresh in the same commit as the code (root `CLAUDE.md`, per-package `CLAUDE.md`,
  `docs/CODEMAP.md`, etc.). The doc-refresh checklist is enforced for every non-trivial commit.
- `skip doc-refresh: trivial typo` is the only escape hatch, and only for typo-grade changes.

### Encoding / line endings

- UTF-8 everywhere.
- LF (`\n`) line endings. Prettier enforces this for the frontend (`endOfLine: 'lf'`).

### Never commit

- Real API keys (Anthropic, OpenAI, Google, GitHub PATs, Stripe / Razorpay live secrets,
  Gmail app passwords). The `.env.template` documents what should go in `.env`. All YAML
  profiles take secrets via environment variable resolution — every fallback in the YAML must be
  a placeholder, not a real value.
- Files prefixed with `.env`, `.secrets`, `credentials.json`. Use the env-resolution chain
  instead.

### Build commands

```bash
# Backend full verify (Spotless gate + unit tests + ArchUnit)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q

# Backend autofix formatting
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spotless:apply

# Frontend build (TS check then bundle)
npm --prefix frontend run build

# Frontend autofix
npm --prefix frontend run format
npm --prefix frontend run lint:fix

# Frontend gate (what CI runs)
npm --prefix frontend run format:check
npm --prefix frontend run lint
```
