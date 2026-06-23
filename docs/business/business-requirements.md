# Business Requirements Document

> Refreshed 2026-06-19 from the current code and docs. This BRD is intended for business planning and investor/customer conversations.

## Objective

Build ExecuteSpec into a trustworthy AI software foundry: a platform that helps teams develop, deploy, govern, observe, remediate, and eventually operate data workflows through AI agents with explicit approval, cost visibility, and verification evidence.

## Business Outcomes

| Outcome | Requirement |
| --- | --- |
| Increase engineering throughput | Users can delegate bounded implementation tasks and receive a PR with traceable evidence. |
| Build buyer trust | Every run exposes plan, cost, tool activity, validation, and verified-score evidence. |
| Protect gross margin | Managed-key tiers have cost caps; Team/Enterprise shift provider cost to BYOK. |
| Enable team adoption | Org, project, RBAC, audit, sharing, and billing controls exist for team use. |
| Create enterprise path | Enterprise has custom contract, DPA/SLA posture, org model controls, and security-review material. |
| Expand into software operations | The same agent platform can handle DevOps/governance, SRE triage, and data-pipeline workflows without fragmenting context. |

## Current Scope

In scope:

- Spec-to-plan-to-PR run execution.
- Plan gate with credit confirmation.
- Verified score and validation report.
- Project workbench, project chat, project documents, project memory, tech debt, PR/code review.
- Billing, top-ups, subscriptions, invoices, payment audit, and tier lifecycle.
- BYOK for Team/Enterprise and account-level provider preference.
- Released terminal CLI plus CLI/VS Code agent-session server protocol and local-hands backend support.
- Operator benchmark surfaces and benchmark tracking.

Out of scope for current external claims:

- SOC 2 completion.
- Fully implemented SAML login/enforcement.
- Dedicated tenant region as self-serve.
- Official benchmark leaderboard placement without current official artifacts.
- Autonomous merge without human review.

## Product Requirements

| Requirement | Current status | Source |
| --- | --- | --- |
| Submit natural-language spec | Live | `RunController.submit` |
| Approve plan and cost before execution | Live | `RunController.approve`, `GateStore` |
| Execute changes through controlled tools | Live | `WorkerAgent`, `ToolRegistry` |
| Validate with build/test/reviewer signals | Live | `ReviewerAgent`, `ValidationReportService` |
| Open/update PR | Live | `ScmPushService` |
| Project-aware context | Live | `codeintel/`, `memory/`, `project/` |
| Billing and quota enforcement | Live | `BillingService`, `TierEnforcementService`, `UsageTrackingService` |
| BYOK routing | Live, needs continued route proof | `ApiKeyResolver`, `ModelResolver`, provider clients |
| Terminal CLI and local-hands execution | CLI released and working; server accepts local-hands after V244 and should keep regression evidence | `RunController`, `tool/remote/`, `agent_session` |
| Benchmark display | Tracking layer live; official claims gated | `benchmark/`, `terminalbench/` |

## Expansion Product Lines

| Product line | Business requirement |
| --- | --- |
| Data Pipeline Builder | Provide a separate product feature for building data pipelines across SQL, NoSQL, warehouses, lakehouses, HDFS, Apache Iceberg, Databricks, Snowflake, and unstructured sources. ExecuteSpec owns compute/orchestration; customers own source and destination storage by default. Managed destination/storage can be priced separately. |
| Analysis Layer | Let users slice, dice, visualize, and inspect processed data after pipelines run, without making ExecuteSpec the default system of record. |
| DevOps/Governance Agent | Provide a terminal/web agent for Terraform, Docker, deployments, environment drift, policy checks, and governed operational changes. This lives inside the current product but should be packaged as a separate product line. |
| Virtual SRE | Monitor observability data, detect and cluster incidents, raise evidence-backed issues, and hand remediation work to ExecuteSpec Core to create reviewable PRs. |

## Commercial Requirements

| Tier | Business role |
| --- | --- |
| Free | Evaluation and activation. Must be generous enough to prove value but capped enough to limit managed-key exposure. |
| Pro | Solo paid conversion. Managed-key convenience with hard per-run cost cap. |
| Team | Primary self-serve revenue and margin-safe team plan. BYOK shifts model cost to customer. |
| Enterprise | Custom contract and security-review path for larger orgs. |

## Operational Requirements

- Payment-webhook processing must be idempotent and signature-verified.
- Billing pages must derive visible plans from DB lifecycle state, not hardcoded UI cards alone.
- Run credits must be charged only at plan approval and refunded on terminal failure.
- Managed-key cost caps must fail closed.
- BYOK keys must be encrypted/redacted and never echoed.
- Public claims must stay synchronized across website, app pricing, docs, and launch assets.

## Business Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Managed-key Pro gross margin compression | High | Keep $5/run cap, monitor run-cost ledger, nudge heavy users to Team BYOK. |
| Benchmark claims overreach | High | Quote only fresh official scorer artifacts; keep app tracking separate from official scoring. |
| Enterprise compliance overclaim | High | Keep one-pager honest; sell roadmap plus questionnaire support, not certifications. |
| Payment flow gaps | Medium | Complete Stripe/Razorpay sandbox abandonment and webhook-ordering proof. |
| Product-line sprawl | High | Sequence adjacent lines first: Core/CLI, DevOps/Governance, Virtual SRE, then Data Pipeline Builder. |
| Data connector burden | High | Build connector architecture and governance rules before promising broad connector coverage. |
| Local-hands regression | Medium | Keep released terminal CLI covered by end-to-end smoke evidence against server local-hands behavior. |

## Decision Inputs For Brainstorm

- Is Pro priced as a sustainable product or mainly as a conversion bridge?
- Should Team remain pure BYOK, or support a managed add-on with explicit usage pricing?
- Should Enterprise lead with reliability evidence, security posture, or model/control-plane governance?
- Which benchmark proof is credible enough for the first investor deck?
- Which product line is the first expansion story for investors: DevOps/Governance, Virtual SRE, or Data Pipeline Builder?
