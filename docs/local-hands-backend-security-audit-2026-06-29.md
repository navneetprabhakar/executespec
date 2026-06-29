# Local-Hands Backend Security Audit - 2026-06-29

Scope: backend support for CLI/TUI local-hands execution (`POST /api/runs localExecution=true`,
run SSE/replay, `POST /api/runs/{id}/tool-results`, agent-session run confirmation, model catalog,
managed-AI read endpoints, and chat web fetch tools).

## Findings

| Area | Current evidence | Status |
| --- | --- | --- |
| Local-hands submit auth | `RunController.submit` rejects `localExecution=true` unless `worker.local-hands.enabled` is on and the auth source is PAT. The flag check happens before idempotency lookup, quota, managed-AI start, usage, and async submit side effects. | Hardened |
| Local-hands submit replay | `RunIdempotencyService.Entry` stores `runId` plus `localExecutionAccepted`; `RunController`, `AgentRunSubmissionGate`, and linked-turn `AgentRunConfirmationService` replay echo the original acceptance bit on duplicate submit. | Hardened |
| Backend capability discovery | `GET /api/v3/client-capabilities` gives split CLI / VS Code clients an authenticated runtime compatibility contract: current auth source, local-hands flag state, whether this request can submit local-hands, endpoint paths, typed event support, tool-result limits/outcomes, Sarvam-disabled provider catalog metadata, guarded web fetch, governance, and managed-AI support. | Hardened |
| Tool-result auth and tenancy | `RemoteToolController.submitToolResult` requires `RunAccessGuard.requireRunWriteAccess` and PAT auth before accepting a result. | Hardened |
| Tool-result envelope | Server normalizes malformed replies to bounded `INTERNAL` errors, caps result content/message text, redacts common secret tokens, rejects body/path run-id mismatch, rejects terminal runs, and maps duplicate/unknown results to deterministic HTTP status codes. | Hardened |
| Pending local tool cancel | `RunLifecycleService.cancel` unregisters the remote tool session before completing SSE, so pending calls receive a deterministic `TOOL_RESULT` cancellation event. Pipeline `finally` still unregisters as an idempotent backstop. | Hardened |
| SSE typed result contract | `RunEvent.toolResult` emits typed `callId`, `toolName`, `permissionKind`, `status`, and `reason` for accepted, timeout, interrupted, and session-close outcomes. | Hardened |
| SSE tool-call display metadata | `RunEvent.toolCall` includes bounded/redacted typed `argsJson`, original arg length, and truncation marker for replay/display clients. | Partially hardened |
| Web fetch guardrails | `WebSearchChatTools` limits schemes, ports, user-info, fragments, query length, suspicious query keys, secret-looking URLs, DNS-resolved private/loopback/link-local/site-local/metadata hosts, redirects, response size, and non-text content. Results are marked untrusted and sanitized. | Verified existing |
| Sarvam provider safety | `ModelAccessService` disables provider `sarvam`; `/api/models/available` and `/api/models/me` filter disabled providers/models. Sarvam YAML remains dark metadata only. | Verified existing |
| Managed-AI read surfaces | `/api/billing/managed-ai/{balance,events,policy}` resolve from the authenticated user and do not take run/org ids from the request. | Verified existing |
| Local CLI/TUI persona | Built the split CLI locally, captured the live TUI launcher/persona routes with screenshots, then executed a real local-hands run against this patched backend. Run `e47e60b8-8955-42aa-9437-0dd8d9bb8a53` completed as `LOCAL_EXECUTION`, produced code/tests in the disposable hotel repo, passed `npm test` 6/6, exported evidence/diff, and verified revert refusal for a file changed after the run. | Verified locally |
| Deployed dev smoke | Deployed commit `a599c799d42fbad003e006a78fa94e2faeadf883` to `executespec-dev-vm`, verified `/api/v3/client-capabilities` through the rebuilt CLI against `https://dev.executespec.ai`, then ran a real deployed-host local-hands run `b14e8437-b16c-4332-bfc9-b35685ed8daa` against a disposable hotel fixture. It completed `LOCAL_EXECUTION`, wrote code/tests locally, passed `npm test` 5/5, exported evidence/diff, and proved guarded revert by deleting generated files while skipping a post-run-changed log file. | Verified on dev |
| Dependency vulnerability scan | Direct OSV query over 429 resolved Maven JAR dependencies found 29 vulnerability records across 13 dependency coordinates. Affected coordinates include `tomcat-embed-core@10.1.54`, `mcp-core@0.18.2`, `postgresql@42.7.10`, `org.eclipse.jgit@6.9.0.202403050737-r`, `bcpkix-jdk18on@1.83`, `bcprov-jdk18on@1.83`, `junrar@7.5.8`, `jackson-databind@2.21.2`, `opentelemetry-api@1.49.0`, `commons-lang3@3.17.0`, `netty-handler@4.1.132.Final`, `netty-codec@4.1.132.Final`, and `opentelemetry-extension-trace-propagators@1.49.0`. | Existing repo dependency risk |

## Residual Risks

- Legacy `TOOL_CALL.payload` still carries the raw tool-call frame for existing CLI clients that
  execute from `payload`; typed replay metadata is bounded/redacted, but full payload redaction would
  require a coordinated client protocol change.
- This pass verified the backend contract locally and on the deployed dev host, but did not run a
  production smoke.
- Cross-tenant behavior is covered by guards and focused tests around tool-results/local submit, but
  this audit did not add a new exhaustive endpoint-by-endpoint access-guard matrix beyond the
  existing `AccessGuardCoverageTest` and targeted controller tests.
- Dependency vulnerabilities reported by OSV were not remediated in this local-hands backend hardening
  pass; they need separate dependency triage because several are shared platform/runtime libraries.
- OWASP dependency-check was attempted locally, but the unauthenticated NVD update path was too slow
  for this pass (`Downloaded 10,000/361,586 (3%)` after several minutes). Sonatype OSS Index was also
  attempted and returned `401 Unauthorized`, despite Maven exiting successfully.

## Verification

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -Dtest=RunIdempotencyServiceTest,AgentRunSubmissionGateTest,AgentRunConfirmationServiceTest,AgentSessionServiceActionLifecycleTest,RunControllerLocalExecutionAuthTest,RemoteToolControllerTest,RemoteToolDispatcherTest,RunEventContractTest,RunLifecycleServiceContinuationApprovalTest test`
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -Dtest=EntitlementsControllerTest,AgentApiOpenApiCoverageTest test`
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -Dtest=WebSearchChatToolsTest,ModelAccessServiceTest,ModelControllerTest,ManagedAiControllerTest test`
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q`
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test -q`
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn org.owasp:dependency-check-maven:check -DskipTests -DfailBuildOnCVSS=11 -Dformat=HTML -Dformat=JSON` — interrupted after NVD feed throttling made completion impractical without an API key.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn org.sonatype.ossindex.maven:ossindex-maven-plugin:audit -DskipTests` — external service returned `401 Unauthorized`.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q dependency:list -DincludeTypes=jar` + direct `https://api.osv.dev/v1/querybatch` scan of 429 resolved Maven JARs — completed, 29 vulnerability records.
- `npm run build -w executespec-core && npm run build -w executespec` in the split CLI repo.
- Split CLI doctor capability probe wiring: `node --test cli/test/doctor-command.test.mjs`,
  `node --test cli/test/w2-t27-backend-capability-api.test.mjs`, `npm test -w executespec`, and
  `npm test -w executespec-core`.
- Live patched-backend capability smoke: `executespec doctor --validate-backend-capability --format json
  --host http://localhost:8080` against the local Spring Boot server returned `backendFeatureDetection=true`,
  `featureDetection=client-capabilities`, `contractVersion=2026-06-local-hands-v1`, `authSource=PAT`,
  `localHands.currentRequestCanSubmitLocalHands=true`, `toolResultContract.successContentMaxChars=60000`,
  and `providerCatalog.disabledProviders=["sarvam"]`. Evidence JSON:
  `output/local-hands-client-capabilities-live-20260629.json`; secret-pattern scan found no `es_pat_`,
  `sk-`, `ghp_` / `github_pat_`, or `anthropic-` token patterns.
- Dev deploy smoke: `CONFIRM_DEPLOY=YES scripts/deploy-local.sh --deploy` deployed
  `a599c799d42fbad003e006a78fa94e2faeadf883` to `executespec-dev-vm`; health returned
  `{"status":"UP"}` and provider check reported Anthropic/OpenAI/Google/OpenRouter/DeepSeek configured.
- Deployed capability smoke: `executespec doctor --validate-backend-capability --format json --host
  https://dev.executespec.ai` returned `backendFeatureDetection=true`,
  `featureDetection=client-capabilities`, `contractVersion=2026-06-local-hands-v1`, `authSource=PAT`,
  `localHands.currentRequestCanSubmitLocalHands=true`, `toolResultContract.successContentMaxChars=60000`,
  and `providerCatalog.disabledProviders=["sarvam"]`. Evidence JSON:
  `output/local-hands-client-capabilities-dev-20260629.json`.
- Real deployed-host local-hands run `b14e8437-b16c-4332-bfc9-b35685ed8daa` against
  `/tmp/es-dev-hotel-localhands.OWr7YH`; disposable repo verification:
  `/tmp/es-dev-hotel-localhands.OWr7YH/output/deployed-verify.log` (`npm test`, 5/5).
- Deployed evidence export/diff/revert logs:
  `/tmp/es-dev-hotel-localhands.OWr7YH/output/deployed-evidence-export.json`,
  `/tmp/es-dev-hotel-localhands.OWr7YH/output/deployed-diff-view.log`, and
  `/tmp/es-dev-hotel-localhands.OWr7YH/output/deployed-revert.log`.
- Live TUI screenshot capture via `scripts/strict-qa/qa_tui_live_local.mjs`; manifest:
  `/Users/navneetprabhakar/Documents/antigravity/executespec-cli/output/qa-live-localhands-20260629/manifest-localhands.json`.
- Real local-hands run `e47e60b8-8955-42aa-9437-0dd8d9bb8a53` against the patched local backend;
  disposable repo verification: `/tmp/es-hotel-localhands.JhXKav/output/verify.log` (`npm test`, 6/6).
- Evidence export: `/tmp/es-hotel-localhands.JhXKav/output/evidence-export.json`; diff and revert logs:
  `/tmp/es-hotel-localhands.JhXKav/output/diff-view.log` and `/tmp/es-hotel-localhands.JhXKav/output/revert.log`.
- Persona PDF: `output/pdf/ExecuteSpec_Local_Hands_Persona_QA_2026-06-29.pdf` (9 pages, rendered with
  Poppler and spot-checked as PNG pages under `tmp/pdfs/local-hands-qa/rendered/`).
- `git diff --check`

`mvn spotless:check` currently fails on unrelated repo formatting debt outside this branch's final
diff; it was not used as a release gate for this scoped backend audit.
