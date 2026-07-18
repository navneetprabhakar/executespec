# ExecuteSpec Enterprise Hardening and Benchmark Publication Master Plan

**Plan ID:** ES-EHB-2026-07-17
**Baseline date:** 2026-07-17 IST
**Plan state:** IMMUTABLE BASELINE — corrections require a dated amendment; execution status belongs in the live tracker
**Live tracker:** [`enterprise-hardening-benchmark-status-2026-07-17.md`](enterprise-hardening-benchmark-status-2026-07-17.md)
**Current-state audit:** [`enterprise-hardening-current-state-audit-2026-07-17.md`](enterprise-hardening-current-state-audit-2026-07-17.md)
**Primary prior audit:** [`byok-project-deep-dive-handoff-2026-07-17.md`](byok-project-deep-dive-handoff-2026-07-17.md)

## 1. Objective

Ship and prove an Enterprise-ready ExecuteSpec across the backend/Web, released CLI/OpenTUI/VS Code, and canonical documentation. Enterprise BYOK isolation, model routing, project intelligence, chat actions, usage accounting, SCM/local sources, recovery, security, and UX must be consistent and adversarially verified. Retained features must have runtime evidence; unreliable features must be corrected or disabled before release. After the product gates pass, run reproducible DeepSeek benchmarks, optional budget-gated Terminal-Bench 2.1, and bounded GLM 5.2 experiments, publish only claims allowed by official rules, and tear down all benchmark infrastructure and DNS.

## 2. Repositories, branches, and ownership

| Code | Repository | Working branch | Responsibility |
| --- | --- | --- | --- |
| B | `/Users/navneetprabhakar/Documents/antigravity/executespec` | `codex/brownfield-rca-remediation-20260717` | Backend, Web, migrations, benchmark API/UI, Terraform and runtime |
| C | `/Users/navneetprabhakar/Documents/antigravity/executespec-cli` | `codex/brownfield-rca-remediation-20260717` | Core client, CLI, OpenTUI and VS Code extension |
| D | `/Users/navneetprabhakar/Documents/antigravity/executespec-docs` | `codex/brownfield-rca-remediation-20260717` | Canonical contracts, operations, manuals, reason log and release evidence |

The active owner is **Codex / this persistent goal session**. A replacement session becomes owner only after recording its task/session identity in the live tracker. Existing user changes are preserved. Applied Flyway migrations and customer databases are immutable.

## 3. Evidence and status rules

Statuses are `NOT_STARTED`, `AUDITING`, `IMPLEMENTING`, `BLOCKED`, `VERIFYING`, `PASS`, `FAIL`, `DISABLED`, or `NOT_APPLICABLE`. `PASS` requires the completion gate in the register, not merely a unit test or commit. Evidence strength, strongest first:

1. official benchmark verifier or authenticated deployed runtime evidence;
2. independent acceptance/evaluator output and persisted database/API evidence;
3. integration/E2E tests covering the complete contract;
4. focused regression tests plus code inspection;
5. code, documentation, commit messages, screenshots, internal verified scores, or intent alone — insufficient.

Every evidence artifact must record repository commit, environment, timestamp/timezone, commands, configuration, route/provider/model, key-source class without secret material, cost, and result. Failed and contradictory evidence is retained. Secrets must never appear in commands, argv, remotes, logs, screenshots, reports, trajectories, commits, or email.

## 4. Requirement register

Every row carries the required handoff fields. “Evidence pending” means no claim is inherited from prior sessions. The live tracker owns status changes and exact artifact links.

| ID | Requirement | Initial status | Owner/session | Repos | Implementation evidence | Test/runtime evidence | Blockers | Next action | Commit/PR | Environment | Cost/spend | Completion gate |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CTRL-01 | Preserve this immutable master plan and a separate continuously updated tracker | IMPLEMENTING | Codex/active goal | B,D | This file + tracker | Link/integrity checks pending | None | Create tracker and docs-map entries | Pending | Local | $0 | Both files committed, linked, and sufficient for cold resume |
| AUD-01 | Record branches, remotes, divergence, dirty files and pushed/unpushed commits for B/C/D | AUDITING | Codex/active goal | B,C,D | Inventory pending in tracker | Git command evidence pending | None | Capture exact SHAs and remotes | Pending | Local | $0 | Reproducible inventory for all repos |
| AUD-02 | Review every branch-only/recent commit by patch, not message | NOT_STARTED | Codex/active goal | B,C,D | Patch findings pending | Diff/stat evidence pending | None | Review baseline..HEAD per commit | Pending | Local | $0 | Every in-scope patch classified, defects tracked |
| AUD-03 | Establish open PRs and CI state | NOT_STARTED | Codex/active goal | B,C,D | PR inventory pending | GitHub/gh evidence pending | Auth/network | Query exact branches | Pending | GitHub | $0 | PR targets, checks and merge state recorded |
| AUD-04 | Establish deployed backend/Web/CLI/TUI versions and compatibility | NOT_STARTED | Codex/active goal | B,C,D | Version contract pending | Authenticated runtime/npm evidence pending | Deployed access | Probe without mutation | Pending | Dev/npm | $0 | Exact tuple and compatibility matrix proven |
| AUD-05 | Establish Flyway head and clean-install/upgrade status | NOT_STARTED | Codex/active goal | B,D | Migration inventory pending | PostgreSQL integration rehearsal pending | Docker availability | Verify V279 chain and upgrade fixture | Pending | Local/Testcontainers | $0 | Fresh install + representative upgrade pass; no rewritten V-files |
| AUD-06 | Inventory effective feature flags and defaults | NOT_STARTED | Codex/active goal | B,D | YAML/migration/runtime inventory pending | SUPER_ADMIN API evidence pending | Runtime auth | Diff source vs deployed flags | Pending | Local/dev | $0 | Effective state, owner and safe default for every relevant flag |
| AUD-07 | Reconcile known failures and existing audit evidence | NOT_STARTED | Codex/active goal | B,C,D | Evidence index pending | Artifact validity audit pending | None | Classify current/stale/contradictory | Pending | Local/dev | $0 | No inherited claim lacks current-tuple provenance |
| BYOK-01 | OWN_KEYS never dispatches through global/platform credentials | NOT_STARTED | Codex/active goal | B,D | Route/key invariants pending | Negative runtime matrix pending | None | Trace all router entries | Pending | Local/dev | Provider smoke budget only after gate | Zero platform-key calls under OWN_KEYS across all features |
| BYOK-02 | Missing, stale, expired, undecryptable or unavailable key context fails closed | NOT_STARTED | Codex/active goal | B,D | Durable context/failure code pending | cache/Redis/restart/decrypt negative tests pending | None | Inspect current fail-closed patch | Pending | Local/Testcontainers | $0 | Deterministic terminal error before dispatch/spend |
| BYOK-03 | Provider/model pins survive all pipeline, retry, continuation, reviewer and background stages | NOT_STARTED | Codex/active goal | B,C,D | Route snapshot pending | per-stage ledger/runtime evidence pending | Provider budget | Enumerate dispatch sites | Pending | Local/dev | Bounded provider tests | Every call stays in pinned set after restart/retry |
| BYOK-04 | Only org-keyed and policy-allowed providers/models participate | NOT_STARTED | Codex/active goal | B,D | explicit allowlist pending | keyed/unkeyed matrix tests pending | None | Audit policy resolution | Pending | Local/dev | $0 + bounded smoke | Empty/unknown availability is fail-closed under OWN_KEYS |
| BYOK-05 | One selected Enterprise provider disables other matrices/failovers | NOT_STARTED | Codex/active goal | B,C,D | matrix eligibility pending | API/UI/router tests pending | None | Audit sparse override semantics | Pending | Local/dev | $0 | Picker, resolver and dispatch expose/use one provider only |
| BYOK-06 | Global preferences/defaults never override Enterprise policy | NOT_STARTED | Codex/active goal | B,C,D | precedence contract pending | conflicting-default tests pending | None | Build precedence matrix | Pending | Local/dev | $0 | Org policy wins on every surface |
| BYOK-07 | Step-up/down and provider switching remain inside approved set | NOT_STARTED | Codex/active goal | B,D | candidate filter pending | continuation/failover tests pending | Benchmark prior policy | Inspect candidate service | Pending | Local/dev | Bounded smoke | No automatic proposal or action escapes policy |
| BYOK-08 | Same route/key policy covers runs, chat, architecture, docs, memory, RAG, code/PR review, tests and jobs | NOT_STARTED | Codex/active goal | B,C,D | unified execution context pending | feature-by-feature route ledger pending | Broad surface | Build dispatch inventory | Pending | Local/dev | Bounded provider cohort | Every LLM/embedding call has durable actor/org/project/route/key evidence |
| BYOK-09 | ProjectProviderResolver and model overrides enforce allowlist, not sparse fallback | NOT_STARTED | Codex/active goal | B,D | resolver policy pending | unit/integration conflict tests pending | None | Inspect current branch patches | Pending | Local | $0 | Unset/disallowed cells cannot become eligible |
| BYOK-10 | DeepSeek route order is native → OpenRouter → Fireworks only within policy | NOT_STARTED | Codex/active goal | B,D | failover order pending | controlled failure evidence pending | Provider budgets | Verify route chain | Pending | Local/dev | Limited approved spend | Healthy native never reroutes; recovery order and key rules proven |
| PROJ-01 | Project CRUD, membership, effective roles and tenant isolation | NOT_STARTED | Codex/active goal | B,C,D | service guards pending | cross-role/cross-org matrix pending | Test identities | Audit all controllers/services/tools | Pending | Local/dev | $0 | Least privilege proven for all listed roles |
| PROJ-02 | Repository attach/readiness is provider-neutral and honest | NOT_STARTED | Codex/active goal | B,C,D | source state contract pending | GitHub/Bitbucket/local tests pending | Bitbucket PAT supplied separately | Audit adapter boundary | Pending | Local/dev/Bitbucket | API-only negligible | No unsupported operation is exposed as ready |
| PROJ-03 | Local folder registration, heartbeat, lease, ownership and local-run binding | NOT_STARTED | Codex/active goal | B,C,D | V278/API/client paths pending | live CLI lease/run/restart tests pending | Local terminal | Review backend+CLI patches | Pending | Local/dev | $0 + model run | Path-free, non-replayable ownership; cloud limitations explicit |
| PROJ-04 | Architecture, codemap, System Map, versions and diffs | NOT_STARTED | Codex/active goal | B,C,D | jobs/routes/artifacts pending | multi-repo/large/restart/runtime review pending | LLM spend | Audit generation pipeline | Pending | Local/dev | DeepSeek bounded | Accurate, restart-safe, source-SHA-attributed artifacts |
| PROJ-05 | Generated docs and uploaded-document RAG | NOT_STARTED | Codex/active goal | B,C,D | governed ingestion/route pending | upload/index/retrieval/policy tests pending | Embedding policy | Inspect current implementation | Pending | Local/dev | Bounded embedding/model | Proven provenance, bounds, policy and cost |
| PROJ-06 | Project chat, memory and chat-to-spec | NOT_STARTED | Codex/active goal | B,C,D | policy/session/route pending | long-session/reload/role/injection tests pending | CHAT requirements | Implement after policy audit | Pending | Local/dev | DeepSeek bounded | Durable, scoped, safe and cross-surface coherent |
| PROJ-07 | Whole-repository Code Review uses project route and linked repository | NOT_STARTED | Codex/active goal | B,C,D | no hardcoded Anthropic; admission guard pending | cross-project/provider runtime test pending | Repo fixture | Inspect patch and service | Pending | Local/dev | DeepSeek bounded | Correct route, write role, linked identity, auditable spend |
| PROJ-08 | PR Review and autofix enforce project access, repo/PR identity and confirmations | NOT_STARTED | Codex/active goal | B,C,D | guards/job context pending | negative and real PR fixture tests pending | Safe test PR | Audit current path | Pending | Local/dev/GitHub | DeepSeek bounded | Unauthorized/ad-hoc targets rejected; side effects confirmed |
| PROJ-09 | Tech debt rollup and fixes use immutable source findings | NOT_STARTED | Codex/active goal | B,C,D | remediation batch pending | dedupe/retry/partial/reload tests pending | None | Audit current service | Pending | Local/dev | DeepSeek bounded | Exact findings/SHA/route/state retained and idempotent |
| PROJ-10 | Contracts, standards, ADRs and invariants are honestly enforced | NOT_STARTED | Codex/active goal | B,C,D | flag/version/evidence contract pending | enforcement/exception tests pending | None | Inventory claims vs flags | Pending | Local/dev | $0 | UI/docs distinguish advisory vs enforced; audit trail complete |
| PROJ-11 | PR, CI state and webhooks work across supported SCMs | NOT_STARTED | Codex/active goal | B,C,D | SCM adapter/webhook state pending | signature/replay/restart tests pending | SCM credentials | Audit GitHub/Bitbucket paths | Pending | Local/dev | $0 | Authenticated, idempotent, tenant-scoped lifecycle |
| PROJ-12 | Sharing/external members/public links have expiry, revocation, audit and limits | NOT_STARTED | Codex/active goal | B,C,D | governance pending | role/link abuse tests pending | None | Audit endpoints and service guards | Pending | Local/dev | $0 | Bounded, revocable, rate-limited and redacted access |
| PROJ-13 | Exports, analytics and activity are bounded, asynchronous where required and redacted | NOT_STARTED | Codex/active goal | B,C,D | job/query/export design pending | large-history/export/restart tests pending | Object storage design | Audit current paths | Pending | Local/dev | Infra only if needed | No unbounded memory/query; downloadable evidence is safe |
| PROJ-14 | Multi-repository projects preserve identity, route, evidence and PR linkage | NOT_STARTED | Codex/active goal | B,C,D | per-repo state pending | mixed-SCM/multi-repo runtime pending | Fixtures | Build matrix | Pending | Local/dev | DeepSeek bounded | Partial failure/retry is honest and idempotent |
| PROJ-15 | Large repositories have bounded complete-enough search/index/context behavior | NOT_STARTED | Codex/active goal | B,C,D | index coverage/headroom pending | Django issue outcomes + stress metrics pending | Runtime/time | Audit search/RAG limits | Pending | Local/dev | DeepSeek bounded | Coverage/truncation visible; real issue localization/edit/test evidence |
| PROJ-16 | Bitbucket Cloud is a normal project source end to end | NOT_STARTED | Codex/active goal | B,C,D | adapter/DTO/UI pending | attach→browse→analyse→review→PR/webhook test pending | PAT may be required at runtime | Design after audit | Pending | Bitbucket Cloud/dev | API/model bounded | Supported lifecycle matches documented capability |
| PROJ-17 | Project/background jobs have durable identity, owner, route, heartbeat, cancellation, retry and idempotency | NOT_STARTED | Codex/active goal | B,D | job protocol pending | kill/restart/duplicate tests pending | Architectural breadth | Inventory every async job | Pending | Local/Testcontainers/dev | $0 + bounded model | Cross-JVM recovery without duplicate spend/artifacts |
| PROJ-18 | Local-source capability boundaries are explicit in API, Web, CLI and docs | NOT_STARTED | Codex/active goal | B,C,D | capability contract pending | denial/empty-state UX evidence pending | None | Compare surfaces | Pending | Local/dev | $0 | No cloud browse/arch/PR claim unless local hands can supply it |
| CHAT-01 | Resolve immutable ProjectChatPolicy with actor/org/project/role/capabilities/route/key/budget/confirmation/trust/audit identity | NOT_STARTED | Codex/active goal | B,D | policy type/service pending | policy resolution tests pending | BYOK/role contracts | Design from existing audit | Pending | Local | $0 | Policy bound to every turn and tool call |
| CHAT-02 | Classify tools as PROJECT_READ, PROJECT_ANALYSE, SPEND_AI or WRITE_REPO | NOT_STARTED | Codex/active goal | B,D | metadata pending | complete-tool-inventory test pending | None | Enumerate 15+ tools | Pending | Local | $0 | No unclassified model-visible tool |
| CHAT-03 | Sanitize chat input and bound payload/token stuffing | NOT_STARTED | Codex/active goal | B,D | InputSanitizer integration pending | Unicode/base64/size tests pending | None | Inspect sanitizer API | Pending | Local | $0 | Unsafe input rejected or normalized before persistence/model |
| CHAT-04 | Redact output secrets before persistence and response | NOT_STARTED | Codex/active goal | B,D | redactor integration pending | seeded-secret tests pending | None | Reuse canonical sanitizer | Pending | Local | $0 | No secret material in DB/API/logs |
| CHAT-05 | Treat history/repo/docs/memory/search/tool results as untrusted | NOT_STARTED | Codex/active goal | B,D | structured trust envelopes pending | indirect-injection corpus pending | None | Audit prompt assembly | Pending | Local/dev | Bounded model | Untrusted content cannot grant authority or alter policy |
| CHAT-06 | Re-authorize each tool invocation at service boundary | NOT_STARTED | Codex/active goal | B,D | capability guard pending | direct-service/cross-project tests pending | None | Introduce guard chokepoint | Pending | Local | $0 | Controller/UI bypass still denied |
| CHAT-07 | PROJECT_VIEWER cannot invoke paid or mutating tools | NOT_STARTED | Codex/active goal | B,C,D | role capability map pending | Web/CLI/API negative tests pending | Test users | Build role fixtures | Pending | Local/dev | $0 | Denial before spend/side effect on every route |
| CHAT-08 | Confirmation is action/provider/model/target scoped, expiring and non-replayable | NOT_STARTED | Codex/active goal | B,C,D | confirmation entity/protocol pending | replay/expiry/substitution tests pending | UX design | Inspect existing proposal protocol | Pending | Local/dev | $0 | One grant authorizes exactly one matching action |
| CHAT-09 | Per-turn token/USD/provider-call/tool-call limits plus loop controls | NOT_STARTED | Codex/active goal | B,C,D | budget enforcement pending | exhaustion/concurrency tests pending | Pricing data | Define safe defaults | Pending | Local/dev | Bounded | Hard stop is durable/auditable and never over-dispatches |
| CHAT-10 | Guardrail configuration cannot silently fail open to LOW | NOT_STARTED | Codex/active goal | B,D | safe fallback/alert pending | Redis/config outage tests pending | None | Audit resolver | Pending | Local | $0 | Failure uses safe posture and emits operator evidence |
| CHAT-11 | Immutable allow/deny/confirm/tool-result audit plus full injection suite | NOT_STARTED | Codex/active goal | B,D | append-only events pending | user/README/docs/memory/tool/Unicode/base64/cross-project corpus pending | None | Design events and corpus | Pending | Local/dev | Bounded model | Decision chain replayable without exposing secrets |
| USAGE-01 | Canonical append-only LLM usage event with all required dimensions/measures | NOT_STARTED | Codex/active goal | B,D | schema/chokepoint pending | migration/entity/router tests pending | Migration V280+ | Audit current tables | Pending | Local/Postgres | $0 | Every attempt has actor, route, tokens, cost, result, correlation |
| USAGE-02 | Record successful and failed provider attempts, retries and failovers | NOT_STARTED | Codex/active goal | B,D | attempt lifecycle pending | timeout/429/5xx/failover tests pending | Provider semantics | Inspect dispatch loop | Pending | Local/dev | Bounded smoke | Ordered attempts reconcile even if final call succeeds |
| USAGE-03 | Parse OpenAI/DeepSeek reasoning tokens correctly | NOT_STARTED | Codex/active goal | B,D | wire parser pending | provider fixture/live sample pending | Provider response availability | Add fixtures first | Pending | Local/dev | Minimal | Nonzero reasoning metadata retained when returned |
| USAGE-04 | Correct classifier/planner/phase attribution using deltas | NOT_STARTED | Codex/active goal | B,D | attribution baseline pending | multi-phase test pending | None | Inspect current patch state | Pending | Local | $0 | Phase sum equals run total without double counting |
| USAGE-05 | Remove int narrowing/overflow risk | NOT_STARTED | Codex/active goal | B,D | long counters/schema pending | >2^31 synthetic tests pending | Migration compatibility | Trace types end-to-end | Pending | Local/Postgres | $0 | No negative/wrapped token/cost values |
| USAGE-06 | Reconcile run totals, per-call events, managed-AI events and provider cost | NOT_STARTED | Codex/active goal | B,D | reconciliation service pending | deterministic mismatch tests pending | Provider invoice granularity | Define tolerances | Pending | Local/dev | Minimal | Explained reconciliation status and variance for every run/session |
| USAGE-07 | SUPER_ADMIN and ORG_ADMIN RBAC reports | NOT_STARTED | Codex/active goal | B,C,D | API/UI pending | cross-org/role tests pending | None | Specify endpoints | Pending | Local/dev | $0 | Super sees all; org admin only own org; others denied |
| USAGE-08 | Roll up by user, org, project, feature, provider/model, role and run/session | NOT_STARTED | Codex/active goal | B,C,D | bounded queries/UI pending | aggregate truth-set tests pending | Query scale | Design indexes | Pending | Local/Postgres/dev | $0 | Roll-ups equal canonical fact totals |
| USAGE-09 | CSV/JSON export with date and timezone filters | NOT_STARTED | Codex/active goal | B,C,D | export pending | timezone/pagination/escaping tests pending | None | Specify limits | Pending | Local/dev | $0 | Deterministic bounded export with no formula/secret leakage |
| USAGE-10 | Alert and recover when durable usage persistence fails | NOT_STARTED | Codex/active goal | B,D | metric/outbox/retry pending | DB outage recovery test pending | Observability setup | Audit failure path | Pending | Local/dev | $0 | Failure visible; event eventually durable or run marked unreconciled |
| SEC-01 | Credential encryption and V279 upgrade rehearsal | NOT_STARTED | Codex/active goal | B,D | current commit pending review | clean/upgrade/decrypt/rollback evidence pending | Docker/Postgres | Inspect 4c9442bd8 patch | Pending | Local/Testcontainers | $0 | Real plaintext fixture encrypted; overflow-safe; app reads it |
| SEC-02 | PAT/API-key masking, rotation and revocation | NOT_STARTED | Codex/active goal | B,C,D | lifecycle pending | create/use/rotate/revoke negative tests pending | Safe credentials | Audit all secret types | Pending | Local/dev | $0 | Revoked credentials fail promptly; plaintext returned once only |
| SEC-03 | Credentials absent from argv, remotes, logs and workspaces | NOT_STARTED | Codex/active goal | B,C,D | secret transport pending | canary-secret scans pending | None | Search process/log/git paths | Pending | Local/dev | $0 | No seeded secret appears in artifacts/process metadata |
| SEC-04 | Authorization enforced in services, not UI only | NOT_STARTED | Codex/active goal | B,C,D | guard coverage pending | direct-service/endpoint inventory tests pending | Broad controllers | Generate inventory | Pending | Local | $0 | Every protected action has service-level decision |
| SEC-05 | Public links enforce expiry, revocation, audit and rate limits | NOT_STARTED | Codex/active goal | B,C,D | controls pending | abuse/revocation/runtime tests pending | None | Audit sharing package | Pending | Local/dev | $0 | Revoked/expired denied; access bounded and attributable |
| SEC-06 | Exports are async, bounded and redacted | NOT_STARTED | Codex/active goal | B,C,D | job/object contract pending | large/export/restart/security tests pending | Storage choice | Audit current export | Pending | Local/dev | Infra if used | Safe manifest/checksum; no handler-memory blowup |
| SEC-07 | Upload MIME/content/malware/decompression/size controls | NOT_STARTED | Codex/active goal | B,D | validation pipeline pending | polyglot/bomb/mismatch/oversize tests pending | Scanner availability | Audit extractor | Pending | Local | $0 | Malicious/oversize input rejected before expensive work |
| SEC-08 | Repository prompt injection is isolated from authority | NOT_STARTED | Codex/active goal | B,D | trust/control separation pending | malicious repo corpus pending | None | Coordinate with CHAT-05 | Pending | Local/dev | Bounded model | Repo text cannot obtain tools, keys, writes or spend |
| SEC-09 | Queries have pagination/date/retention limits | NOT_STARTED | Codex/active goal | B,C,D | bounds pending | high-cardinality DB tests pending | None | Inventory list/report APIs | Pending | Local/Postgres | $0 | Explicit maximums and stable cursors everywhere required |
| SEC-10 | Cross-JVM recovery and background restart are proven | NOT_STARTED | Codex/active goal | B,D | lease/checkpoint/job semantics pending | two-JVM kill/restart evidence pending | Multi-process harness | Extend existing CAS proof | Pending | Local/dev | $0 + bounded model | No duplicate action/spend; terminal recovery evidence |
| SEC-11 | Observability, alerts and admin views cover enterprise operations | NOT_STARTED | Codex/active goal | B,C,D | metrics/dashboards pending | alert-injection/API/UI evidence pending | Monitoring stack | Build SLO/control matrix | Pending | Local/dev | Infra negligible | Org/Super views actionable without tenant leakage |
| SEC-12 | FastContext is contained and unavailable when runtime absent | NOT_STARTED | Codex/active goal | B,C,D | flags/surface removal pending | absent-runtime regression pending | Microsoft runtime unavailable | Search all exposure | Pending | Local/dev | $0 | Normal behavior unaffected; no customer promise/UI |
| SEC-13 | Sarvam remains outside normal routing | NOT_STARTED | Codex/active goal | B,C,D | registry/flags/docs pending | model-picker/router negative tests pending | None | Verify no resurrection | Pending | Local/dev | $0 | No normal candidate, fallback or picker entry |
| SEC-14 | No secrets enter evidence, screenshots, trajectories or email | NOT_STARTED | Codex/active goal | B,C,D | evidence sanitizer pending | seeded scan pending | None | Define pre-publication scan | Pending | All | $0 | Automated and manual scans pass before publication |
| QA-01 | Build persona/role/scenario matrix for all 15 named personas/roles | NOT_STARTED | Codex/active goal | B,C,D | matrix pending | coverage audit pending | Test accounts | Derive journeys | Pending | Local/dev | $0 | Every persona has expected capabilities and hostile scenarios |
| QA-02 | Adversarially test every Web feature | NOT_STARTED | Codex/active goal | B,C,D | fixes pending | browser screenshots/log/API evidence pending | Deployed candidate | Complete implementation first | Pending | Dev/local isolated | DeepSeek bounded | Happy/error/empty/stale/reconnect/a11y/mobile/permission coverage |
| QA-03 | Adversarially test released CLI/OpenTUI/VS Code features | NOT_STARTED | Codex/active goal | B,C,D | fixes pending | npm-installed/native PTY/terminal evidence pending | Terminal/VS Code | Build release tuple | Pending | Local/dev | DeepSeek bounded | Keyboard/narrow/wide/reconnect/local-hands/permissions proven |
| QA-04 | Test large repo, multi-repo, local folder and Bitbucket brownfield journeys | NOT_STARTED | Codex/active goal | B,C,D | fixes pending | outcome evidence pending | Fixtures/PAT | Select safe repos/issues | Pending | Local/dev/SCM | DeepSeek bounded | Real attach→understand→change→test→PR outcomes |
| QA-05 | Test provider failures, route switching, costs, gates, retries and continuations | NOT_STARTED | Codex/active goal | B,C,D | fixes pending | controlled fault matrix pending | Provider budget | Create deterministic failures | Pending | Local/dev | Limited | Cross-surface route/evidence consistency |
| QA-06 | Test exports/reports/confirmations/injection/secret leakage | NOT_STARTED | Codex/active goal | B,C,D | fixes pending | hostile corpus/runtime evidence pending | None | Execute security journeys | Pending | Local/dev | Minimal | No unauthorized side effect or leaked secret |
| QA-07 | Independently judge generated code and benchmark patches | NOT_STARTED | Codex/active goal | B,D | rubric pending | code/test/manual evaluator evidence pending | Generated outputs | Define acceptance rubric | Pending | Local/benchmark | Included benchmark spend | Product score never substitutes independent judgment |
| BENCH-01 | Review official current SWE-bench Lite rules before spend | NOT_STARTED | Codex/active goal | B,D | rule digest pending | official links/version hashes pending | Internet | Browse primary sources | Pending | Official docs | $0 | Dataset/env/interface/timeout/schema/trajectory/metadata/commands/prohibitions documented |
| BENCH-02 | Review official current Terminal-Bench 2.1/Harbor rules before spend | NOT_STARTED | Codex/active goal | B,D | rule digest pending | official links/version hashes pending | Internet | Browse primary sources | Pending | Official docs | $0 | Execution/submission/publication/repro requirements documented |
| MODEL-01 | Switch working model to GPT-5.6 Luna only after product hardening and Enterprise QA | NOT_STARTED | Navneet approval if tool cannot switch | B,C,D | gate record pending | model identity evidence pending | Model switch may require user | Stop at gate if needed | N/A | Codex | $0 | Luna confirmed before paid benchmark provisioning/execution |
| CANARY-01 | Run deterministic mixed 10-case SWE-bench Lite locally on native DeepSeek Pro | NOT_STARTED | Codex/future Luna phase | B,D | frozen config pending | official verifier results pending | BENCH/MODEL gates | Select cases after rules | Pending | Local | DeepSeek, record actual | 10/10 terminal with valid patches/trajectories; failures independently reviewed |
| CANARY-02 | Track canary through benchmark API and `/benchmark` UI | NOT_STARTED | Codex/future Luna phase | B,D | hooks pending | reload/SSE/export screenshots/API pending | Tracking readiness | Verify before dispatch | Pending | Local | Included | Durable progress survives browser/backend restart |
| CANARY-03 | Freeze evidence-based prompt/tool/timeout/context/concurrency config before cloud | NOT_STARTED | Codex/future Luna phase | B,D | config manifest pending | canary comparison pending | CANARY results | Review patches/verifiers manually | Pending | Local | No extra beyond canary | Accepted config committed with hashes and rationale |
| INFRA-01 | Review existing benchmark Terraform/docs before provisioning | NOT_STARTED | Codex/future Luna phase | B,D | audit pending | fmt/init/validate/plan pending | MODEL/CANARY gates | Inspect module and prior evidence | Pending | Local/Terraform | $0 | Isolated disposable plan has no dev/prod impact |
| INFRA-02 | Provision isolated static-IP benchmark VM/DB/Redis/Docker/Harbor/monitoring with idle stop | NOT_STARTED | Codex/future Luna phase | B,D | Terraform state pending | health/load/security checks pending | Approval/gates/budget | Apply only after gates | Pending | GCP benchmark | Record hourly/total | All resources pinned, monitored and teardown-managed |
| INFRA-03 | Pin commits, images, datasets, prompts, routes, timeouts, limits, concurrency and machine details | NOT_STARTED | Codex/future Luna phase | B,C,D | manifest pending | digest/version validation pending | None after infra | Generate run manifest | Pending | Benchmark | $0 metadata | Reproduction needs no chat history |
| INFRA-04 | Update only benchmark Cloudflare DNS and record old/new identity | NOT_STARTED | Codex/future Luna phase | B,D | Terraform/DNS record pending | DNS/TLS/API/UI/SSE evidence pending | Cloudflare auth | Plan exact record | Pending | Cloudflare/benchmark | Negligible | dev/prod untouched; zone/record/IP/time captured |
| INFRA-05 | Tear down DNS/IP/VM/storage/runtime resources after runs | NOT_STARTED | Codex/future Luna phase | B,D | destroy/restore pending | cloud inventory and DNS proof pending | Runs must finish | Execute mandatory teardown | Pending | GCP/Cloudflare | Stop all spend | Terraform/cloud provider show no orphaned resources |
| TRACK-01 | Register every SWE/TB/GLM run through benchmark API/UI | NOT_STARTED | Codex/future Luna phase | B,D | harness hooks pending | task/event evidence pending | Adapter readiness | Audit event producers | Pending | Local/benchmark | Included | No benchmark exists only in terminal logs |
| TRACK-02 | Persist queue/progress/result/provider/model/token/cost/time/ETA/failure/trajectory/submission fields | NOT_STARTED | Codex/future Luna phase | B,C,D | schema/UI pending | API/UI truth-set test pending | Migration if needed | Compare current DTO/schema | Pending | Local/benchmark | $0 | DB/API/UI agree after reload |
| TRACK-03 | Prove SSE reconnect, browser reload, backend restart and DB rebuild | NOT_STARTED | Codex/future Luna phase | B,D | recovery pending | kill/restart multi-hour simulation pending | Environment | Build deterministic test | Pending | Local/benchmark | $0 | Tracking does not depend on one process/browser/terminal |
| PAR-01 | Inventory every concurrency/pool value across Terraform, Harbor, launchers, executors, providers, DB/Redis and SSE | NOT_STARTED | Codex/future Luna phase | B,C,D | inventory pending | config resolution evidence pending | None | Search and trace effective values | Pending | Local/benchmark | $0 | No hidden concurrency remains undocumented |
| PAR-02 | Run bounded concurrency 1/2/4 and only safe 8 canaries | NOT_STARTED | Codex/future Luna phase | B,D | configs pending | throughput/quality/error/resource/cost evidence pending | Provider/VM budget | Gate each level | Pending | Benchmark | Record actual | Separate evidence-backed SWE/TB/GLM values |
| PAR-03 | Adaptively reduce on 429/5xx/memory/error pressure | NOT_STARTED | Codex/future Luna phase | B,D | controller pending | fault/load tests pending | None | Design after baseline | Pending | Local/benchmark | $0 | Reduction is bounded, durable and visible |
| TB-01 | Propagate one Harbor absolute deadline through adapter/server/worker/LLM/remote tools | NOT_STARTED | Codex/future Luna phase | B,C,D | deadline contract pending | boundary/clock tests pending | BENCH rules | Audit all timeout layers | Pending | Local/benchmark | $0 | Every child uses min(config, remaining-reserve) |
| TB-02 | Reserve finalizer/artifact/trajectory/upload/event/cleanup time and stop impossible calls | NOT_STARTED | Codex/future Luna phase | B,D | shutdown budget pending | slow/fault tests pending | None | Model phase budget | Pending | Local/benchmark | $0 | Controlled terminal result precedes Harbor kill |
| TB-03 | Time-based checkpoints/earlier finalizer/continuous trajectories and timeout classification | NOT_STARTED | Codex/future Luna phase | B,D | checkpoints pending | kill/slow-tool/provider tests pending | None | Update adapter/worker | Pending | Local/benchmark | Minimal | Zero missing trajectories and explicit timeout class |
| TB-04 | Qualify timeout failures below 2% before full TB | NOT_STARTED | Codex/future Luna phase | B,D | remediation pending | sufficiently sized canary cohort pending | Budget/time | Run after fixes | Pending | Benchmark | Record actual | <2% timeout-caused failures; no unexplained Harbor kill |
| DS-01 | Full official SWE-bench Lite with native DeepSeek Pro | NOT_STARTED | Codex/future Luna phase | B,D | frozen config pending | all official verifier artifacts pending | All prior gates | Dispatch full suite | Pending | Benchmark | Track actual | Every dataset case terminal, valid trajectory and cost |
| DS-02 | Independently validate every SWE result and aggregate | NOT_STARTED | Codex/future Luna phase | B,D | validator pending | patch/verifier/reconciliation evidence pending | DS run | Review all results | Pending | Benchmark/local | Included | No missing/duplicate/invalid case; aggregate recomputed |
| DS-03 | Publish SWE-bench result according to official current rules | NOT_STARTED | Codex/future Luna phase | B,D | submission package pending | official validation/submission receipt pending | Rule/quality gate | Publish only compliant claim | Pending | Official channel | Possible submission only | Public artifact links to config/trajectories/metadata |
| DS-04 | Budget-gate full TB 2.1 after SWE publication | NOT_STARTED | Codex/future Luna phase | B,D | spend forecast pending | balance/infrastructure evidence pending | Budget decision | Calculate before dispatch | Pending | Benchmark | TBD | Written go/no-go; no silent overspend |
| DS-05 | If approved, full TB 2.1 and compliant publication with valid trajectories | NOT_STARTED | Codex/future Luna phase | B,D | frozen TB config pending | all verifier/trajectory/submission evidence pending | DS-04 + TB gates | Dispatch only if pass | Pending | Benchmark/official | Track actual | Full suite reconciled and official publication accepted |
| GLM-01 | Deterministic 10-case SWE and 10-case TB subsets on GLM 5.2 via OpenRouter/Fireworks | NOT_STARTED | Codex/future Luna phase | B,D | fixed subset/config pending | verifier/trajectory evidence pending | DeepSeek phase complete | Run comparable subsets | Pending | Benchmark | ≤$25 each provider account | Clearly labelled focused experiments, not full claims |
| GLM-02 | Increase to 20 each only when quality and budget justify | NOT_STARTED | Codex/future Luna phase | B,D | decision pending | first-10 metrics pending | Budget | Record go/no-go | Pending | Benchmark | Stay ≤ caps | Explicit evidence-based gate |
| GLM-03 | Track provider spend independently and stop before cap | NOT_STARTED | Codex/future Luna phase | B,D | reservation ledger pending | account/usage reconciliation pending | Provider cost metadata | Enforce hard caps | Pending | Benchmark/providers | $25 OpenRouter + $25 Fireworks maximum | No account exceeds its cap |
| FIX-01 | Every QA/benchmark defect has RCA, root fix, regression and affected rerun | NOT_STARTED | Codex/active goal | B,C,D | RCA register pending | rerun evidence pending | Findings emerge later | Maintain live register | Pending | All | Track actual | No open P0/P1; no symptom-only closure |
| REL-01 | Keep backend, clients and docs synchronized at logical verified checkpoints | NOT_STARTED | Codex/active goal | B,C,D | commit map pending | cross-repo compatibility tests pending | None | Commit only after gates | Pending | Local/GitHub | $0 | Tuple manifest references pushed SHAs and passing evidence |
| REL-02 | Raise/update PRs only after development and QA gates | NOT_STARTED | Codex/active goal | B,C,D | PRs pending | CI/QA evidence pending | All product gates | Prepare PR packet | Pending | GitHub | $0 | Reviewable PRs with complete evidence and no unrelated rewrites |
| REL-03 | Release only after all required product gates pass | NOT_STARTED | Navneet + Codex | B,C,D | release notes pending | final completion audit pending | Full programme | Run requirement-by-requirement audit | Pending | Dev/prod/npm | TBD | Every retained feature proven; mandatory blockers zero |
| REL-04 | Final docs, RCAs, screenshots, costs, commands and user/operator manuals are complete | NOT_STARTED | Codex/active goal | B,C,D | artifacts pending | link/render/secret scans pending | Final tuple | Reseal docs/evidence | Pending | Local/dev | $0 | Cold reviewer can reproduce claims safely |
| COMM-01 | On genuine unattended approval/input gate, email Navneet without secrets and stop idle spend | NOT_STARTED | Codex/active goal | D | email template pending | sent-message evidence only if triggered | Email tool may be unavailable | Use only at real gate | N/A | Email | Stop new spend | Email includes phase, work, blocker, VM/hour, provider spend, safe action and doc paths |

## 5. Execution sequencing and hard gates

1. **Control gate:** CTRL-01, AUD-01 through AUD-07. No code-change claim before patch/current-state audit.
2. **Isolation gate:** BYOK-01 through BYOK-10, PROJ-01 through PROJ-18, CHAT-01 through CHAT-11, USAGE-01 through USAGE-10 and SEC-01 through SEC-14. Global keys must deliberately be present in negative tests so isolation is actually exercised.
3. **Product verification gate:** QA-01 through QA-07 on a pinned candidate tuple. A unit-test-only feature remains unproven.
4. **Benchmark rules gate:** BENCH-01 and BENCH-02 using current official primary sources. No paid benchmark dispatch before both pass.
5. **Model gate:** MODEL-01. If this session cannot change to GPT-5.6 Luna, stop and request Navneet's approval; do not provision or dispatch paid benchmarks.
6. **Local canary gate:** CANARY-01 through CANARY-03. Freeze the accepted configuration in git.
7. **Infrastructure/tracking/parallelism gate:** INFRA, TRACK and PAR rows. Cloudflare changes are benchmark-record-only and reversible.
8. **TB timeout gate:** TB-01 through TB-04 before a full TB 2.1 run.
9. **Publication sequence:** full native-DeepSeek SWE Lite → independent validation → compliant publication → TB budget gate → optional full TB/publication → GLM focused subsets.
10. **Closure gate:** FIX, REL and mandatory teardown. The final completion audit must inspect every row's authoritative evidence and treat missing or indirect evidence as incomplete.

## 6. Verification strategy

- **Static:** patch review, provider/model/credential dispatch inventory, generated endpoint/tool inventory, migration immutability, feature-flag and concurrency inventories.
- **Focused tests:** negative RBAC/BYOK, route loss, overflow, reasoning-token fixtures, confirmation replay, prompt injection, upload abuse and SCM identity.
- **Integration:** PostgreSQL/Redis/Docker, two-process recovery, provider-wire fixtures, GitHub/Bitbucket Cloud, local-hands reconnect and benchmark tracking restart.
- **Runtime:** authenticated Web/API and released npm CLI/OpenTUI on a pinned dev candidate; native DeepSeek for product flows; deliberately failing routes for recovery evidence.
- **Independent quality:** inspect code, diffs, builds/tests and official verifier outputs. ExecuteSpec's own headline score is diagnostic only.
- **Operational:** metrics/alerts, long-session/restart, concurrent load, resource pressure, DNS/TLS/SSE, idle shutdown and complete teardown.

## 7. Cost and safety envelope

- Product audit and deterministic tests start at zero provider/infrastructure spend.
- Native DeepSeek is the primary product-test and full SWE provider; every dispatch must be recorded in the live tracker.
- OpenRouter and Fireworks GLM experiments each have a hard maximum of **$25 per provider account**.
- Full Terminal-Bench 2.1 is optional and requires a post-SWE written budget gate.
- No paid benchmark infrastructure may start before BENCH, MODEL and CANARY gates.
- Unattended infrastructure must stop accepting new work, idle-shutdown, and be torn down on completion or genuine approval blockage.

## 8. Amendment protocol

Do not rewrite the baseline requirements to fit implementation. If official rules, product decisions, or discovered dependencies require a change, append an amendment containing date/time, affected IDs, old contract, new contract, reason, approver, cost impact and compatibility impact. Status and evidence continue to change only in the live tracker.

**Evidence authority:** the requirement rows above are the immutable creation-time baseline and
deliberately retain their original statuses. They are not a current progress report. The live tracker
is authoritative for local verification, deployed/runtime gates, spend and blockers. As of the
2026-07-18 local checkpoint, the tracker records `mvn -q verify` 3,243/3,243, Web 61 files/269 tests
plus build/format/lint, Python 39/39 plus bytecode compilation, V297 upgrade/clean-install validation,
and the 116-requirement crosswalk; none of that substitutes for the still-open deployed/live gates.

## Amendments

### A2 — 2026-07-18 IST: post-benchmark cross-model/provider escalation experiment

**Approved by:** Navneet Prabhakar in the active goal conversation.

**Affected IDs:** PAR-02, PAR-03, DS-02, DS-04/05, GLM-01/02, and the live
continuation/provider-policy requirements.

After the current benchmark campaigns close, run a separate, explicitly labelled
routing experiment to test whether a cheap-first route can obtain most of the
quality of the stronger model without paying the stronger-model price on every
task. This is an experiment, not permission to change production defaults or to
override Enterprise/BYOK provider pins.

#### Hypothesis

1. Start eligible tasks on native DeepSeek V4 Pro (cost-efficient workhorse).
2. Escalate only when deterministic signals indicate that the current attempt is
   stuck or low quality: no meaningful progress, repeated tool/test failure,
   failed build, unresolved reviewer findings, low completion confidence, or a
   benchmark-verifier failure.
3. Use GLM 5.2 through the approved OpenRouter route as the stronger escalation
   candidate, subject to the existing confirmation, benchmark-prior, provider-key,
   tier, and per-run spend policies.
4. Compare against DeepSeek-only and GLM-only controls on the same frozen tasks.

#### Required cohort and evidence

- Use a frozen, stratified cohort covering simple, medium, multi-file, and
  failure/recovery tasks; reuse task IDs across controls.
- Run three arms: DeepSeek-only, GLM-only, and DeepSeek→GLM escalation. Keep
  prompt, tools, timeout, context budget, and verifier versions identical.
- Record per task: provider/model for every call, escalation trigger, attempts,
  completion outcome, verified score, tests/build/reviewer evidence, latency,
  input/output/cache/reasoning tokens, provider-reported cost, and duplicate-spend
  checks. Preserve the full hashed trajectory for every arm.
- Report quality uplift, cost per resolved task, p50/p95 latency, escalation rate,
  false-escalation rate, recovery success, and timeout/error rates. Include 95%
  confidence intervals where the cohort size permits; do not infer superiority
  from a single task or headline score.

#### Safety gates

- Do not run this until the active SWE/TB benchmark runs are terminal and their
  ledgers are reconciled.
- Do not enable automatic escalation in production from this experiment alone.
- Enterprise/TEAM/BYOK single-provider policy and explicit per-run route pins must
  remain hard constraints; a candidate outside the approved provider set is a
  denial, not a fallback.
- Start with recommendation-only/advisory mode, then a bounded canary requiring
  explicit approval for GLM escalation. Promote an automatic policy only if it
  improves resolved quality at an agreed cost ceiling with no material increase
  in duplicate spend, timeout, or policy-escape failures.

**Deliverable:** a versioned routing-evaluation report, raw manifests/trajectories,
reconciled cost ledger, and a go/no-go decision for model-matrix/continuation
defaults. The existing cheap-first production setting remains unchanged until
that decision is recorded.

### A1 — 2026-07-18 IST: benchmark order, full Pro scope and model-gate waiver

**Approved by:** Navneet Prabhakar in the active goal conversation.

**Affected IDs:** MODEL-01, CANARY-01..03, DS-01..06, GLM-01..04, DS-04/05, TB-01..04.

- The working-model downgrade to GPT-5.6 Luna is waived for this session. Benchmark provider/model
  identity is still fixed independently and does not inherit the Codex working model.
- Paid benchmark order is now:
  1. deterministic GLM 5.2 through OpenRouter on the exact SWE-bench Lite 10-case set;
  2. full SWE-bench Lite 300 with native DeepSeek V4 Pro;
  3. full public SWE-bench Pro 731 with native DeepSeek V4 Pro.
- The original conditional full Terminal-Bench 2.1 requirement is retained behind its timeout,
  trajectory, official-rule and written budget gates. It was reordered, not deleted.
- The original focused GLM Terminal-Bench subset is also retained as a conditional 10-case
  experiment after TB controls pass; expansion to 20 remains evidence/budget gated.
- The corrected native-DeepSeek Lite-10 canary remains immutable evidence. It scored 6/10 under the
  official v4.1 scorer after zero-spend patch recovery, but it did not retain a complete frozen
  reproduction manifest. Therefore the GLM-10 run must prove the stricter tracker, trajectory,
  budget, deadline and manifest controls before the full suites.
- Cost impact: OpenRouter remains capped at $25 for the account and requires a lower fail-closed
  campaign cap before dispatch. DeepSeek full-suite spend must be forecast and tracked separately.
- Compatibility impact: no product/API compatibility waiver. All P0/P1 product gates, independent
  output review, persona QA, final documentation and infrastructure/DNS teardown remain mandatory.

The live tracker owns the exact status and evidence for these added rows. This amendment does not
authorize provider spend by itself.

### A2 — 2026-07-18 IST: deterministic zero-omission checkpoint gate

**Approved by:** Navneet Prabhakar's explicit instruction that no goal-list item may be missed.

**Affected IDs:** CTRL-01, CTRL-02 and every immutable/amended requirement ID.

- `scripts/verify-enterprise-goal-crosswalk.py` encodes the complete canonical range of **116**
  gates: 113 immutable baseline rows plus `CTRL-02`, `DS-06` and `GLM-04` introduced through
  additive scope control/amendment A1.
- The command fails on a missing, extra or duplicate requirement; master-plan drift; tracker drift;
  or a requirement row missing any of its 13 mandatory evidence columns.
- An incomplete or blocked row remains valid scope. The control prevents deletion; it does not turn
  missing evidence into completion.
- Run the gate before every synchronized checkpoint and again during the final completion audit.
