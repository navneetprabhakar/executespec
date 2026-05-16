# Code style

> Source of truth: the lint and format configs in the repo. If this doc and the configs disagree, the configs win.

This document covers both the TypeScript frontend and the Java backend. Both are derived from `frontend/.eslintrc.cjs`, `frontend/.prettierrc.json`, and the `spotless-maven-plugin` block in `pom.xml`.

## 1. Frontend — TypeScript

### 1.1 Configs

| Concern         | File                                                       |
| --------------- | ---------------------------------------------------------- |
| Linting         | [`frontend/.eslintrc.cjs`](../../frontend/.eslintrc.cjs)   |
| Formatting      | [`frontend/.prettierrc.json`](../../frontend/.prettierrc.json) |
| Format excludes | [`frontend/.prettierignore`](../../frontend/.prettierignore)   |
| TS compiler     | `frontend/tsconfig.json`                                   |

Run gates:

```bash
npm --prefix frontend run lint           # eslint --max-warnings 9999
npm --prefix frontend run lint:fix       # eslint --fix
npm --prefix frontend run format         # prettier --write
npm --prefix frontend run format:check   # CI gate
```

CI runs `format:check` + `lint` on every PR (`.github/workflows/lint.yml`).

### 1.2 Prettier rules (verbatim from `.prettierrc.json`)

```json
{
  "printWidth": 100,
  "tabWidth": 2,
  "useTabs": false,
  "semi": true,
  "singleQuote": true,
  "jsxSingleQuote": true,
  "quoteProps": "as-needed",
  "trailingComma": "all",
  "bracketSpacing": false,
  "bracketSameLine": false,
  "arrowParens": "always",
  "endOfLine": "lf",
  "embeddedLanguageFormatting": "auto"
}
```

Notable:

- **`bracketSpacing: false`** — object literals are written `{foo: 1}`, not `{ foo: 1 }`.
- **`singleQuote: true` / `jsxSingleQuote: true`** — single quotes in TS and in JSX attributes (`<Btn kind='primary'/>`, not `kind="primary"`).
- **`printWidth: 100`** — 100-character line target.
- **`trailingComma: 'all'`** — trailing commas on every multi-line list, including function parameters.
- **`arrowParens: 'always'`** — `(x) => x`, never `x => x`.

### 1.3 ESLint extensions

```js
extends: [
  'eslint:recommended',
  'plugin:@typescript-eslint/recommended',
  'plugin:react/recommended',
  'plugin:react/jsx-runtime',
  'plugin:react-hooks/recommended',
  'prettier',
]
```

`prettier` last so formatting rules don't fight Prettier.

### 1.4 Rules enforced as errors

These map directly to the Google TypeScript Style sections cited in `frontend/.eslintrc.cjs`:

| Rule                                            | Level | Google TS §                       |
| ----------------------------------------------- | ----- | --------------------------------- |
| `no-var`                                        | error | §6.1 — use `const` / `let`        |
| `eqeqeq` (`{null: 'ignore'}`)                   | error | §6.13 — strict equality           |
| `no-eval`                                       | error | §3.8                              |
| `no-implied-eval`                               | error | §3.8                              |
| `no-new-func`                                   | error | §3.8                              |
| `no-with`                                       | error | §3.10                             |
| `no-debugger`                                   | error | §3.5                              |
| `no-array-constructor`                          | error | §6.5 — no `new Array()`           |
| `@typescript-eslint/no-array-constructor`       | error | §6.5                              |
| `no-new-object`                                 | error | §6.6 — no `new Object()`          |
| `no-new-wrappers`                               | error | §6.7 — no `new String()` etc.     |
| `no-compare-neg-zero`                           | error | §6.16                             |
| `@typescript-eslint/consistent-type-assertions` | error | §4.5.2 — `as` form, not `<T>foo`  |
| `no-empty` (`{allowEmptyCatch: true}`)          | error | (empty catches for analytics)     |

### 1.5 Rules enforced as warnings

| Rule                                          | Level | Notes                                                            |
| --------------------------------------------- | ----- | ---------------------------------------------------------------- |
| `prefer-const` (`{destructuring: 'all'}`)     | warn  |                                                                  |
| `default-case`                                | warn  | §6.10                                                            |
| `@typescript-eslint/no-explicit-any`          | warn  | **D-T2** — `any` is allowed during the soup-cleanup migration    |
| `@typescript-eslint/no-unused-vars`           | warn  | Ignores `^_` (so `_unused` is acceptable)                        |
| `@typescript-eslint/no-non-null-assertion`    | warn  | §3.10 — `foo!` needs a glance                                    |
| `react-hooks/rules-of-hooks`                  | warn  | Downgraded to warn while ~6 legacy violations are migrated       |
| `react-hooks/exhaustive-deps`                 | warn  |                                                                  |
| `react/self-closing-comp`                     | warn  | §3.4 — `<Br />` not `<Br></Br>`                                  |
| `no-restricted-syntax`                        | warn  | PR-L: inline async `onClick` / `onSubmit` → suggests `useAsyncAction` |

### 1.6 Rules explicitly disabled

These are off because they conflict with the codebase conventions:

| Rule                                              | Why off                                                                                                  |
| ------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `@typescript-eslint/no-unsafe-*` (5 rules)        | **D-T2** — the existing `any` soup makes these noisy; will re-enable once `any` count is in single digits |
| `react/prop-types`                                | TypeScript handles prop validation                                                                       |
| `react/display-name`                              | `React.memo` / `forwardRef` patterns are common                                                          |
| `react/no-unescaped-entities`                     | `don't` reads fine; `don&apos;t` is worse UX                                                             |
| `no-useless-escape`                               | False positives on regex character classes                                                               |
| `no-misleading-character-class`                   | False positive on the intentional emoji-stripping regex in `LiveLog`                                     |

### 1.7 Documented deviations from Google TypeScript Style

These are the four deviations enforced by `.eslintrc.cjs`:

#### D-T1 — Default exports OK for `*.tsx`

Google TS forbids default exports. We allow them on `*.tsx` page/component files because they pair well with React's `lazy()` / dynamic imports.

```tsx
// frontend/src/signal/pages/HomePage.tsx
export default function HomePage() { ... }
```

The `.eslintrc.cjs` `overrides` block reserves the right to enforce `import/no-default-export` on `src/lib/`, `src/hooks/`, `src/stores/` (currently a placeholder — not enforced today). Library code already follows it manually.

#### D-T2 — `any` is warn-mode

`@typescript-eslint/no-explicit-any` is `warn`, not `error`. The five `no-unsafe-*` rules are off entirely. This is temporary — the migration is to clean up the existing `any` count first, then promote to `error`. Test files (`**/*.test.{ts,tsx}`) have `no-explicit-any` off via override.

```ts
// allowed today
function foo(x: any) { ... }   // warn
const y = x as any;            // warn
```

#### D-T3 — `interface` vs `type` both accepted

Google TS prefers `interface` for object types and `type` for unions. ESLint enforces neither; the codebase uses both. Choose whichever reads better:

```ts
export interface RunSummary { ... }            // OK
export type Verdict = 'shippable' | 'needs-review' | 'blocked';  // OK
```

#### D-T4 — JSDoc on internal types skipped

Google TS asks for JSDoc on every exported symbol. We require it on public-API surfaces (anything in `lib/api.ts`, store actions) and skip it on internal helper types. The codebase reality:

- `lib/api.ts` exports — heavily JSDoc'd, including provenance comments (R-numbers, PR refs).
- Component prop interfaces — usually skipped unless the surface is non-obvious.
- Pure-function helpers — JSDoc'd if the function is exported from a shared module.

### 1.8 Naming conventions

These come from `eslint:recommended` defaults plus Google TS §5.

| Symbol kind                  | Pattern                       | Example                              |
| ---------------------------- | ----------------------------- | ------------------------------------ |
| React component              | `UpperCamelCase`              | `RunDetailTabs`, `VerdictPill`       |
| React component file         | `UpperCamelCase.tsx`          | `RunDetailTabs.tsx`                  |
| React hook                   | `camelCase` + `use` prefix    | `useAuthStore`, `useTierPermissions` |
| Hook file                    | `useThing.ts`                 | `useAsyncAction.ts`                  |
| Zustand store                | `useXxxStore`                 | `useRunStore`, `useUiStore`          |
| Function (any)               | `camelCase`                   | `parseErrorMessage`, `deriveVerdict` |
| Variable / let-binding       | `camelCase`                   | `verifiedScore`, `runId`             |
| Constant (module-level)      | `UPPER_SNAKE` or `camelCase`  | `STATUS_MESSAGES`, `BASE`            |
| Type / interface             | `UpperCamelCase`              | `VerdictSignals`, `RunDetail`        |
| Enum-like string union       | `'UPPER_SNAKE'` literals      | `'PLANNING' \| 'WORKING'`            |
| Boolean prop / variable      | `is` / `has` / `should` prefix| `isRetryableError`, `hasGithubPat`   |

CSS classes follow BEM-ish kebab-case in `signal.css`:

```
.signal-card
.signal-card-link
.signal-row .row-title
.signal-btn.primary
```

### 1.9 File layout

```
frontend/src/
├── App.tsx                  # Top-level route table
├── main.tsx                 # Bootstrap (mount, theme, telemetry, SW)
├── index.css                # Global styles
├── tokens.css               # CSS-variable design tokens (single source of truth)
├── signal/                  # Production "Signal" chrome — most pages live here
│   ├── components/          # Reusable primitives (Modal, Card, Button, etc.)
│   ├── hooks/               # Signal-specific hooks (useSse, useProjectMeta, ...)
│   ├── icons/               # Inline SVG icons
│   ├── lib/                 # Signal-only helpers
│   ├── pages/               # Routed pages, mirrored to `App.tsx`
│   └── signal.css           # All Signal-scoped component CSS
├── v3/                      # Retired V3 top-bar shell — kept for legacy URL redirects
├── v4/                      # Retired V4 shell — same
├── components/              # Cross-cutting React components
│   ├── auth/                # ProtectedRoute, ChangePasswordModal, etc.
│   ├── layout/              # AppLayout, Header, NotificationBell
│   ├── project/             # Project workbench components used by Signal pages
│   ├── runs/                # ScoreRing, VerdictPill, RunCard, etc.
│   ├── settings/            # SsoConfigCard, NotificationPreferences, OrgStandardsManager
│   ├── shared/              # Generic primitives (Button, Tooltip, Drawer, ...)
│   └── execution/           # ExecutionStepper etc.
├── design-system/           # Pure-function design helpers
│   ├── primitives.css       # Raw `--c-*` color primitives
│   ├── contrast.ts          # WCAG contrast helpers
│   └── contrast.test.ts     # Vitest-shape regression test (dormant)
├── pages/                   # V2 sidebar-shell pages (legacy; mostly redirected)
├── stores/                  # Zustand stores
│   ├── useAuthStore.ts
│   ├── useUiStore.ts        # Theme + UI preferences
│   ├── useRunStore.ts
│   ├── useProjectStore.ts
│   └── useMetricsStore.ts
├── hooks/                   # Shared hooks (useTierPermissions, useAsyncAction, ...)
├── lib/                     # Headless modules (api.ts, verdict.ts, telemetry, ...)
└── types/                   # Cross-cutting TS types (project.ts, ...)
```

What each top-level directory is for:

- **`signal/`** — the production chrome. All user-facing routes resolve here.
- **`v3/` and `v4/`** — historical chromes kept solely to catch legacy URLs and redirect them. New code does not go here.
- **`components/`** — React components shared across pages (run detail, project workbench, settings cards). These are imported by Signal pages.
- **`pages/`** — V2 sidebar-shell pages. Most have been redirected to Signal equivalents; the remaining few (`ExecSummaryPage`, `org/OrgProjectsPage`) are still in active use for admin/backoffice paths.
- **`stores/`** — Zustand state slices. State survives navigation across both chromes.
- **`hooks/`** — generic, non-Signal-scoped React hooks.
- **`lib/`** — pure JS modules with no React. `api.ts` is the single backend client. `analytics.ts`, `verdict.ts`, `glossary.ts`, `telemetry.ts`, etc. live here.
- **`design-system/`** — token primitives + pure design helpers. The CSS file here defines `--c-*` raw colors; `tokens.css` aliases them to semantic names.
- **`types/`** — pure TS type modules shared across stores / components.

### 1.10 Import alias

`@/` resolves to `frontend/src/`. Configured in `vite.config.ts`:

```ts
resolve: {
  alias: {'@': path.resolve(__dirname, './src')},
}
```

Use it for every cross-package import:

```ts
import {req, ApiError} from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import VerdictPill from '@/components/runs/VerdictPill';
```

Same-directory imports use relative paths.

### 1.11 Function exports

The codebase uses `export const` arrow functions consistently for `lib/`, `hooks/`, `stores/`:

```ts
export const getRun = (id: string) => req<RunDetail>(`/runs/${id}`);
export const useTierPermissions = () => {  ...  };
```

Page/component files use `export default function`:

```tsx
export default function HomePage() {  ...  }
```

This is the D-T1 default-exports allowance.

### 1.12 Patterns to follow

- **Async / await over `.then`** — every `lib/api.ts` consumer awaits the promise. `.then` chains appear only in the few binary-download helpers where readability wins.
- **`as const` for tuple-like data** — e.g. ratio thresholds in `design-system/contrast.ts`.
- **Type narrowing via discriminated unions** — `VerdictSignals.reviewerVerdict`, `Verdict`, etc.
- **`useAsyncAction` for button handlers** — `frontend/src/hooks/useAsyncAction.ts` exposes `[pending, run]` so buttons disable during the request. `no-restricted-syntax` warns on inline async `onClick`.

### 1.13 Patterns to avoid

- Field/`@Autowired` style — does not apply to TS, but listing for symmetry with the backend section: never mutate module state with side effects at import time (other than `initTelemetry()` in `main.tsx`).
- `console.log` in committed code. The lint config doesn't ban it, but PRs are expected to remove debug logs.
- New `any`. Each new `any` shows up as a lint warning that counts toward the trend tracked in CI.

## 2. Backend — Java

### 2.1 Configs

| Concern               | File                                                                                                |
| --------------------- | --------------------------------------------------------------------------------------------------- |
| Formatter             | `pom.xml` → `<plugin>spotless-maven-plugin</plugin>` (v3.4.0, google-java-format v1.22.0, GOOGLE style) |
| Build verifier        | `mvn verify` triggers `spotless:check`                                                              |
| Auto-format           | `mvn spotless:apply`                                                                                |
| ArchUnit pin          | `src/test/java/com/navneet/executespec/arch/*.java`                                                 |

Run gates:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q     # includes spotless:check
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spotless:apply   # auto-format
```

### 2.2 google-java-format settings (verbatim from `pom.xml`)

```xml
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
```

What this means:

- **GOOGLE style** — 2-space indent, 100-character line target, K&R braces, no wildcard imports.
- **`removeUnusedImports`** — applied on every `spotless:apply` and asserted on `spotless:check`.
- **Import order** — `java` first, then `javax`, then `jakarta`, then `com.*` (your code + first-party libs), then `org.*` (Spring etc.), then everything else.
- **`reflowLongStrings`** — long string literals are wrapped automatically.
- **`formatAnnotations`** — annotations are placed each on their own line above the declaration.

### 2.3 Lombok usage

Allowed and encouraged on JPA entities + DTOs:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class PipelineRun {
  @Id private UUID id;
  private String status;
  // ...
}
```

For Spring beans, use `@RequiredArgsConstructor` to get constructor-injection without writing the constructor by hand:

```java
@Service
@RequiredArgsConstructor
public class ReviewerAgent {
  private final LlmRouter llmRouter;
  private final PromptAssembler promptAssembler;
  // ...
}
```

This is the standard pattern across the codebase. Sampled:

- `agent/ReviewerAgent.java`
- `credit/LifetimeCreditService.java`
- `reviewer/AccuracyAssessmentService.java`
- `crypto/EncryptedValueConverter.java`

### 2.4 No field injection

`@Autowired` on fields is **forbidden** by convention. Use constructor injection (`@RequiredArgsConstructor` or explicit constructors).

`grep` audit — only 3 occurrences of `@Autowired` exist in `src/main/java`:

- `context/ContextStore.java`
- `test/NativeToolsTestController.java`
- `security/InputSanitizer.java`

These pre-date the convention and are tracked for migration. New code adds `@RequiredArgsConstructor` instead.

### 2.5 DTO style

Prefer Java records for DTOs:

```java
public record ReviewerRequest(
    String runId,
    String taskId,
    List<String> files,
    Path workspaceRoot) {}

public record PricingUpdateRequest(
    double inputCostPer1m,
    double outputCostPer1m,
    String notes) {}
```

Records get a generated constructor, accessors, `equals`, `hashCode`, `toString` — and they are immutable, which matters for the immutable-by-default convention on request/response DTOs.

Use Lombok `@Data` only for JPA entities that need mutators (Spring Data sets fields via reflection on rehydration). Records do not work for `@Entity` (need a no-arg constructor for Hibernate proxies).

Examples in the tree:

- `reviewer/ReviewerRequest.java` (record)
- `reviewer/ReviewerResult.java` (record)
- `api/dto/PricingUpdateRequest.java` (record)
- `api/dto/MemberUsage.java` (record)
- `api/dto/DeploymentSummary.java` (record)

### 2.6 Layering convention

The 5-layer flow:

```
@RestController  →  @Service  →  *Agent  →  *Tool  →  LlmRouter
```

- **Controller** — Spring MVC entry. Annotated `@RestController`. Validates input via `@Valid`. Delegates to a service.
- **Service** — Business logic. Spring `@Service` bean. May persist via `@Repository` interfaces and call agents.
- **Agent** — LLM-aware logic. Owns prompt assembly + LLM dispatch via `LlmRouter`.
- **Tool** — Native-tool implementation invoked by the agent during a worker loop. Owns its JSON-schema spec.
- **LlmRouter** — Provider dispatch (Anthropic / OpenAI / Gemini). Single entry point for every LLM call.

Legacy `FallbackLlmRouter` survives only for non-tool-calling agents (`AccuracyAssessmentService`, `ConversationAgent`, `testgen/*`). New agents go through `LlmRouter`.

### 2.7 Naming conventions

| Symbol kind     | Pattern        | Example                                          |
| --------------- | -------------- | ------------------------------------------------ |
| Class           | `UpperCamel`   | `RunOrchestrator`, `OutlinePlannerAgent`         |
| Method          | `camelCase`    | `submitRun`, `verifyAuditChain`                  |
| Constant        | `UPPER_SNAKE`  | `KNOWN_LEGACY_DRIFT`, `MAX_ITERATIONS`           |
| Package         | `lowercase`    | `com.navneet.executespec.reviewer`               |
| Test class      | `*Test`        | `ApiContractTest`, `AccessGuardCoverageTest`     |
| Agent class     | `*Agent`       | `WorkerAgent`, `ReviewerAgent`, `PatchAgent`     |
| Service class   | `*Service`     | `BillingService`, `FindingPatchSubmitter`        |
| Controller class| `*Controller`  | `RunController`, `OrgController`                 |
| Spec class      | `*Spec`        | (Spring Security specifications)                 |

### 2.8 Test style

Stack:

```
JUnit 5 + Mockito + Testcontainers + ArchUnit + Spring Test
```

Sample patterns:

```java
// Unit test
class VerdictDeriverTest {
  @Test
  void deriveVerdict_blocksOnFinalBuildFailed() { ... }
}

// Spring slice test
@SpringBootTest
class PipelineE2ETest extends PostgresIntegrationBase { ... }

// Repository test
@DataJpaTest
class ProjectMemoryRepositoryTest { ... }

// ArchUnit pin
class ApiContractTest {
  @Test
  void everyApiTsUrlResolvesToRegisteredBackendRoute() { ... }
}
```

Testcontainers via `PostgresIntegrationBase` provides a real Postgres for `@SpringBootTest` + `@DataJpaTest` flows. Use it whenever the test reaches JPA — H2 has caused divergences in the past.

### 2.9 Patterns that must be followed (cross-reference with `CLAUDE.md`)

- LLM calls flow through `LlmRouter` (or `FallbackLlmRouter` for legacy non-tool agents). Never call providers directly.
- Run-ownership fields (`createdByUserId`, `orgId`, `projectId`) are stamped **before** the async pipeline thread starts. Race-condition fix.
- Token-cost-control truncation limits — `readFile` 8k chars, `shell` 6k chars, `diff` 5k chars.
- Async work wraps `Thread.ofVirtual().start()` runnables in `ContextSnapshotFactory.captureAll().wrap(...)` so traceId + RequestAttributes survive thread boundaries.
- Payment-webhook signatures are verified before processing (Razorpay HMAC, Stripe `Stripe-Signature`).
- Feature flags via `FeatureFlagService.isEnabled()` — never hardcode toggles.
- Structured logging with traceId / spanId via Micrometer context propagation. MDC is auto-populated.

### 2.10 Patterns that must not be used

- Field injection (`@Autowired` on fields). Use constructor injection instead.
- Raw `String` for LLM prompts. Use prompt files under `src/main/resources/prompts/*.md` assembled via `PromptAssembler.assemble(path, vars)`.
- Direct HTTP calls to LLM providers. Always via `LlmRouter`.
- Swallowed exceptions. Log + rethrow, or handle explicitly with a comment.
- Setting run-ownership fields **after** `submitRun()` returns.

## 3. Cross-language conventions

### 3.1 Naming of HTTP routes and IDs

- Path placeholders use `{id}` / `{runId}` / `{projectId}` (no leading prefix). The frontend mirrors with `${id}` template substitutions; `ApiContractTest` normalises both forms to `{}` before comparing.
- Run IDs and project IDs are UUIDs. They are stringified at the API boundary; never expose raw `UUID` types to TypeScript.

### 3.2 Date / time

- Server returns ISO-8601 strings (`2026-05-16T10:42:08.123Z`) in every JSON payload.
- Client treats them as `string` and parses with `new Date(s)` only at the render layer.
- `Instant.now().toString()` is the canonical timestamp source on the server (see `ValidationAdvice.baseBody`).

### 3.3 Error messages

- Server error envelope: see [`docs/frontend-api-usage.md` §1.3](../frontend-api-usage.md).
- All customer-visible messages are written in plain English. Slugs (`payment_required`, `bad_request`) are for programmatic dispatch only.
- No PII in log messages. The webhook signature failure log line, for example, redacts the gateway event ID before printing.

### 3.4 Feature flags

- Backend: `FeatureFlagService.isEnabled(key)` returns `true`/`false`. Keys are namespaced (`integrations.slack.enabled`, `v2.pause.enabled`, etc.).
- Frontend: `getPublicFeatureFlags()` returns a whitelisted subset (`integrations.*`, `billing.*`, `pipeline.*`, `project.*`, `execution.view.*`). The result is stored in `useAuthStore` and read via `useFeatureFlag(key)`.
- New flags are added via Flyway migration on `feature_flag` plus a controller endpoint update. The `adminListFeatureFlags` / `adminUpdateFeatureFlag` admin surface drives them at runtime.

## 4. Editor and tooling

### 4.1 Recommended editor configs

- **TypeScript**: Prettier on save + ESLint on save. Most contributors use the VS Code Prettier + dbaeumer ESLint extensions.
- **Java**: IntelliJ IDEA with the google-java-format plugin and the `Reformat code` action bound to a hotkey. Spotless will catch anything missed at `mvn verify`.

### 4.2 Pre-commit

There is no committed pre-commit hook. The CI lint workflow is the gate: `format:check` + `lint` for TS, `mvn verify` for Java. PRs that fail either are blocked from merge.

### 4.3 Auto-fix shortcuts

```bash
npm --prefix frontend run format          # prettier --write everywhere
npm --prefix frontend run lint:fix        # eslint --fix
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spotless:apply  # google-java-format + imports
```

Running these three locally before pushing is the fastest way to avoid CI churn.

## 5. Examples grepped from the tree

### 5.1 Typed API client function

```ts
// frontend/src/lib/api.ts (line 539)
export const createRun = (body: {
  userRequest: string;
  executionMode?: 'PROTOTYPE' | 'PRODUCTION';
  // ...
  userCostCap?: number;
}) => req<{runId: string}>('/runs', {method: 'POST', body: JSON.stringify(body)});
```

### 5.2 Zustand store action

```ts
// frontend/src/stores/useRunStore.ts (line 113)
const result = await api.getRuns({
  status: filters.status,
  projectId: filters.projectId,
  scope: filters.scope,
});
```

### 5.3 React component with default export

```tsx
// frontend/src/components/runs/VerdictPill.tsx (line 54)
export default function VerdictPill({className, size = 'sm', ...signals}: VerdictPillProps) {
  const result = deriveVerdict(signals);
  if (!result) return null;
  // ...
}
```

### 5.4 Java service with constructor injection

```java
// src/main/java/com/navneet/executespec/reviewer/ReviewerAgent.java
@Service
@RequiredArgsConstructor
public class ReviewerAgent {
  private final LlmRouter llmRouter;
  private final PromptAssembler promptAssembler;
  // ...
}
```

### 5.5 Java record DTO

```java
// src/main/java/com/navneet/executespec/api/dto/PricingUpdateRequest.java
public record PricingUpdateRequest(
    double inputCostPer1m, double outputCostPer1m, String notes) {}
```

### 5.6 ArchUnit pin

```java
// src/test/java/com/navneet/executespec/arch/ApiContractTest.java
@Test
void everyApiTsUrlResolvesToRegisteredBackendRoute() throws Exception {
  Set<String> backendPatterns = collectBackendPatterns();
  Set<String> apiTsUrls = extractApiTsUrls(Files.readString(API_TS_PATH));
  // ... fails the build if any URL has no matching @*Mapping
}
```
