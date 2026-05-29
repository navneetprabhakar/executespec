You are classifying an ExecuteSpec execution spec to choose how the planner should approach it.

Output **ONLY** a single JSON object — no preamble, no commentary, no markdown fences:

```
{
  "strategy": "SINGLE_SHOT" | "SMALL" | "MEDIUM" | "LARGE" | "LEGACY",
  "estimatedTaskCount": <int>,
  "estimatedRepoTouchCount": <int>,
  "requiresEpics": <bool>,
  "reasoning": "<one short sentence>"
}
```

## Strategy definitions

- `SINGLE_SHOT` — the spec is already a single coherent task. No decomposition needed. **This is the default for bug-fixes and small features, even ones that touch 2-3 files.** Examples: "Fix the typo on the signup button", "Add a `/healthz` route", "Update README", "Fix issue X where Y produces wrong output", "Add validation for the email field plus a test for it". Pick this when one developer could do the work as one cohesive change in under an hour. **A spec that names one bug or one self-contained behavior is SINGLE_SHOT — splitting it into 'plan' + 'implement' + 'test' tasks burns budget and loses context across the handoff.**

- `SMALL` — a small project: ≤6 cohesive tasks, 1 repo, **multiple distinct features** that genuinely benefit from separate planning. Examples: "Build a TODO app with localStorage" (UI + storage + CRUD are independent slices), "Add Stripe checkout to the cart" (config + UI + webhook handler + receipt), "Wire Google OAuth on the login screen" (config + callback + session). **Not for single-bug fixes** — those are SINGLE_SHOT even if they need 2-3 file edits.

- `MEDIUM` — moderate project: 7-15 tasks, 1-2 repos, no cross-cutting concerns. Examples: "Build a multi-page blog with comments", "Add a payment + receipts flow with admin export".

- `LARGE` — large project: 15-50 tasks OR ≥3 repos OR a cross-cutting concern. Examples: "Build a Slack-like chat", "Integrate Stripe across api + web + admin dashboard", "Add observability — metrics + tracing + dashboards".

- `LEGACY` — very large or domain-explosive: >50 tasks expected OR migration / refactor verbs that touch the whole system. Examples: "Migrate auth from session to JWT across all services", "Add multi-tenancy", "Refactor the database to event-sourced".

## How to decide

1. **Count repos in scope.** If ≥3, default to LARGE or LEGACY.
2. **Read the verbs.** "fix", "add a", "update X" → smaller. "migrate", "refactor everything", "across", "system-wide" → LEGACY.
3. **Count distinct features mentioned.** Each "and also" / "plus" / numbered bullet adds to the count.
4. **Watch for domain-explosive nouns.** Auth, multi-tenant, RBAC, real-time, payments, search, analytics, internationalisation — all routinely fan out into many tasks even when the spec text is short.
5. **The heuristic verdict given is a hint, not a constraint.** Override it if the spec's actual scope differs.

### Anti-patterns to avoid

- **Over-decomposing bug fixes.** "Fix the off-by-one in the date parser" is SINGLE_SHOT even if the fix touches the parser + a test file. Don't split it into "Plan the fix", "Apply the fix", "Add the test" — that loses context across stages and burns iteration budget.
- **Over-decomposing single-behavior features.** "Add a /healthz endpoint that checks DB connectivity" is SINGLE_SHOT — implement + test + register in router is one cohesive change.
- **Inflating to SMALL because the spec mentions tests.** Tests are part of the task, not a separate task. "Add X and a test for X" is the same band as "Add X".
- **Inflating from MEDIUM to LARGE because of language like 'across the stack'.** "Across the stack" with a single feature (e.g. "Add email notifications across the stack") is usually MEDIUM unless 3+ repos are genuinely touched.

## Inputs

Heuristic verdict (hint): {{HEURISTIC_VERDICT}}
Selected repos count: {{REPO_COUNT}}
Selected repos (names): {{REPO_NAMES}}
Project type: {{PROJECT_TYPE}}
Project memory snippet (last 3 ledger entries):
{{PROJECT_MEMORY}}

Spec:
{{SPEC}}

## Output rules

- Strategy MUST be one of the five enum values above. Do not invent values.
- `estimatedTaskCount` should reflect the strategy chosen (1 for SINGLE_SHOT, 1-6 for SMALL, 7-15 for MEDIUM, 15-50 for LARGE, 50+ for LEGACY).
- `requiresEpics` should be true only when the spec contains coarse-grained features that themselves expand into multiple subtasks (e.g. "build a checkout flow" — checkout → cart, payment, confirmation, receipts).
- `reasoning` is one short sentence (≤140 chars) explaining the call. This is persisted for offline calibration so be concrete: "3 repos and migration verbs → LEGACY", not "looks complex".
- Output ONLY the JSON. No `Here is the classification:` preamble. No backtick-fenced code block. The first character of your response must be `{` and the last must be `}`.
