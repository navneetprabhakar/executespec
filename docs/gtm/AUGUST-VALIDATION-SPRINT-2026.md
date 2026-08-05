# ExecuteSpec August validation sprint

**Window:** 3–31 August 2026  
**Objective:** prove or reject a narrow non-US acquisition motion using qualified conversations, real-repository evaluations, paid pilots, and collected revenue. Impressions, followers, and sign-ups without repository activity do not count as traction.

## Repository truth used

This brief is grounded in the current marketing site, canonical product docs, and current release evidence.

- The implemented product contract is: spec → plan and cost gate → controlled execution → build/test/reviewer evidence → pull request. The internal score is a summary of evidence, not proof of correctness or mergeability. See [`README.md`](../../README.md), the [PRD](../../../executespec-docs/backend/product/PRD.md), and [run-execution contract](../../../executespec-docs/backend/features/run-execution.md).
- The public walkthrough is explicitly synthetic. It is UI evidence, not a customer result, production measurement, or benchmark result. See [`website/index.html`](../../website/index.html).
- The 22 July brownfield audit proves local source, test, access-control, approval-gate, and UI contracts. It explicitly records live model execution as blocked before dispatch and does not validate DeepSeek coding quality. See [`BROWNFIELD_VALIDATION_REPORT_2026-07-22.md`](../../BROWNFIELD_VALIDATION_REPORT_2026-07-22.md).
- The benchmark layer and local cohorts are not official or broadly publishable performance proof. Official scoring and external artifacts remain separate gates. See [benchmark boundaries](../../../executespec-docs/backend/features/benchmarks.md) and [release readiness](../../../executespec-docs/backend/operations/release-2.0-risk-readiness.md).
- Current enterprise language can credibly cover plan approval, model/key policy, tenant guards, tool activity, evidence provenance, and DPA/SLA review. SOC 2 completion, end-to-end SAML enforcement, a self-serve dedicated region, and official benchmark standing cannot be claimed. See the [enterprise one-pager](../../../executespec-docs/backend/product/enterprise-one-pager.md).

## Narrow ICP and painful job

**Primary ICP:** founder, CTO, Head of Engineering, or delivery lead at a 10–75 engineer software-development agency in South Africa, Nigeria, or Kenya that:

- delivers work through GitHub pull requests for external clients;
- has Java/Spring, TypeScript/Node, or Python repositories with a repeatable local build or test command;
- already experiments with coding agents, or has engineers using them informally;
- has review capacity, fixed-price delivery margin, or client-proof pressure;
- can nominate one bounded, non-sensitive backlog item and an engineer to review the output in August.

**Challenger ICP:** the same buyer at an English-capable Brazilian nearshore agency. Keep this as a separate cohort so Portuguese-language and regional trust effects do not contaminate the African experiment.

**Painful job:** “Delegate a bounded backlog item to an agent without handing a client an opaque diff, losing control of spend, or making a senior engineer reconstruct what was tested.”

This is narrower than “ship features autonomously.” It is also testable in one week on one repository.

### Why these regions are hypotheses worth testing

- GitHub’s 2025 Octoverse reports 6.89 million developers in Brazil and continued developer growth across Nigeria and Kenya. Its 2024 report counted more than 1.1 million developers in Nigeria, 664,000 in South Africa, and 393,000 in Kenya. These are developer-supply signals, not proof of ExecuteSpec demand. [GitHub Octoverse 2025](https://github.blog/news-insights/octoverse/octoverse-a-new-developer-joins-github-every-second-as-ai-leads-typescript-to-1/) and [GitHub Octoverse 2024](https://github.blog/news-insights/octoverse/octoverse-2024/).
- Kenya is explicitly promoting digital outsourcing, and South Africa’s government describes IT services and business-process outsourcing as target digital sectors. These support the agency hypothesis; they do not establish willingness to pay for this product. [Kenya ICT Ministry](https://ict.go.ke/kenya-launches-digital-outsourcing-initiative-create-jobs-youth) and [Invest South Africa](https://www.investsa.gov.za/key-sectors/digital-economy-ict/).
- Brazil’s official statistics reported information and communication growth led partly by software development, custom software, hosting, and IT consulting in 2025. Again, this supports market activity, not product demand. [IBGE](https://agenciadenoticias.ibge.gov.br/en/agencia-press-room/2185-news-agency/releases-en/45897-volume-of-services-down-0-4-in-december-and-closes-2025-with-a-2-8-increase).

## Proof available today

| Safe to demonstrate | What it proves | What it does not prove |
|---|---|---|
| Synthetic product walkthrough | Current intended workflow and UI | Customer use, reliability, model quality, or time saved |
| Plan and cost approval gate | A human can inspect and approve before worker execution | Estimate accuracy in every production workload |
| Build/test/reviewer evidence ledger with provenance | The product distinguishes deterministic, LLM, hybrid, and formula evidence | Correctness, mergeability, or customer acceptance |
| Local brownfield validation: 4,678 backend tests; 390 frontend tests; 996 CLI tests; exercised tenant isolation, idempotency, and approval persistence | Current source/contract quality for the audited branch | Deployed reliability or real-repository coding quality |
| Bounded three-provider 3/3 fixture cohorts in release evidence | The exact small fixtures passed held-out checks | General provider ranking, benchmark parity, or production quality |
| BYOK and provider/model controls in code and product contract | Customer-controlled provider-spend path exists | Lower total cost for a particular customer |

## Claims to avoid in August

- “PRs your team actually merges,” “merge-ready,” “verified production code,” or any guaranteed outcome.
- Any customer count, testimonial, case study, time saved, merge rate, or revenue claim without fresh attributable evidence.
- “10–20× cheaper,” “frontier quality at a fraction of the cost,” or savings against another tool without a reproducible same-task comparison.
- Official Terminal-Bench or SWE-bench standing, SOTA/parity language, or a broad provider-quality ranking.
- “Every run executes syntax, type, lint, build, and unit tests.” The actual evidence depends on repository/tool resolution and what fired.
- “Deterministic verdict” if the audience could reasonably interpret the entire result as deterministic; parts of the ledger use LLM review or hybrid evidence.
- SOC 2 completion, end-to-end SAML, dedicated-region availability, physical tenant isolation, or customer-owned-VPC availability as shipped self-serve capabilities.
- Jira/Confluence, multi-repo, autofix, project memory, or broad enterprise governance as the lead. Even where code exists, this sprint is testing one job, not selling the inventory.

The current public hero—“AI that ships pull requests your team actually merges”—is ahead of available proof. Do not amplify it in social or outreach during this sprint. A safer temporary sentence is: **“Turn a bounded engineering spec into a pull request with a plan gate and inspectable build, test, review, and token evidence.”**

## Positioning hypothesis

> For software-delivery agencies that already use coding agents but still pay a senior-engineer review tax, ExecuteSpec turns one bounded backlog item into a pull request with an approved plan, visible model spend, and inspectable validation evidence.

This is a hypothesis. August must answer three questions:

1. Do agency leaders recognize the review/evidence problem strongly enough to take a call?
2. Will they connect a real repository and allocate an engineer to review one run?
3. After seeing the result, will they pay to repeat the workflow?

## Offer and CTA

**Working offer: “One-repo evidence sprint.”**

1. 25-minute fit call.
2. Customer selects one low-risk, 2–6 hour human-sized backlog item in a non-sensitive repo or approved private fork.
3. ExecuteSpec inspects the build/test contract, produces a plan and cost estimate, and waits for customer approval.
4. Forge supervises one run. The customer receives the branch/PR, diff, build/test/review evidence, and token ledger.
5. A customer engineer answers four questions: useful enough to review, safe enough to continue, amount of rewriting required, and whether they would pay for another three runs.

**CTA:** “Bring one bounded backlog item and the engineer who would review it. I’ll show you the plan and evidence before asking you to continue.”

Do not promise a free pilot publicly. Proposed commercial rule: the first proof run is a founder-approved evaluation; continuation uses published Team pricing ($100/month minimum for two seats, customer keys) or a separately approved paid concierge pilot. Forge must choose the pilot price before outbound begins.

## Channel plan

### LinkedIn pillars

1. **The review-tax problem:** where coding-agent output becomes senior-engineer verification work.
2. **Inspectable handoff:** plan gate, cost visibility, and evidence provenance—shown with synthetic or explicitly approved material.
3. **Build-in-public truth:** what is proven, what is still unproven, and what a real-repo evaluation will test.
4. **Agency operating questions:** fixed-price margin, client acceptance, test evidence, and reviewer capacity.

Use Forge’s personal account only if he explicitly approves the employment-safe bio and boundary. Otherwise use the ExecuteSpec company account. Two posts per week are enough.

### X pillars

Use the same four pillars in shorter form, plus substantive replies to agency, engineering-leadership, testing, and coding-agent discussions. Three original posts and up to five evidence-based replies per week are enough. Do not chase generic AI-news reach.

### Founder-led outreach

- Build a hand-curated list of 50 agencies: 30 across South Africa/Nigeria/Kenya and 20 in Brazil.
- Prioritize warm introductions, founders who post about AI-assisted delivery, and agencies whose public engineering material shows GitHub, automated tests, and overseas client delivery.
- Every note needs one company-specific observation. No bulk sequencing, scraped personal emails, or generic “try my tool” message.
- Ask for a 25-minute problem interview first. Introduce the repository sprint only after confirming the pain and fit.
- Cap at 15 new personalized contacts per week. Follow up once after 4–5 business days, then stop.

## Weekly cadence and founder-time budget

| Week | Operator work | Forge work | Exit signal |
|---|---|---|---|
| 3–7 Aug | Finalize proof packet, 15-account list, 2 LinkedIn + 3 X drafts, 5 warm-intro asks, 10 direct notes | Approve public boundary and offer; post manually; take up to 3 calls | At least 3 positive replies and 2 qualified calls booked |
| 10–14 Aug | Add 15 accounts based on week-one learning; run first evaluations; publish one evidence artifact only if approved | Up to 3 calls; supervise up to 2 repo runs | At least 2 real repositories accepted and 1 completed review |
| 17–21 Aug | Narrow to the best region/message; second outreach wave; send paid continuation proposals | Up to 3 calls; supervise up to 2 runs; approve proposals | At least 3 completed trials total and 2 commercial proposals |
| 24–31 Aug | Follow up only on active opportunities; collect payment; conduct loss interviews; write stop/go memo | Close or decline; one 60-minute decision review | At least 1 collected payment and evidence for the decision |

Assumed Forge load: **4 hours/week**, excluding product fixes. If the real budget is lower, reduce prospect volume before reducing trial quality.

## Experiment ledger

One row per message/audience/channel combination; never blend regions or radically different offers.

| ID | Hypothesis | Audience | Message | Channel | CTA | Spend/time | Sent/reach | Replies | Qualified conversations | Repos accepted | Trials completed | Useful PRs | Pilots | Revenue collected | Learning/next change |
|---|---|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| AUG-AF-01 | Evidence-led handoff is painful for African agencies | SA/NG/KE agency CTOs | Review tax + one-repo evidence sprint | Founder LinkedIn + warm/direct | 25-min fit call | 0 paid; 3h | 0 | 0 | 0 | 0 | 0 | 0 | 0 | $0 | Pending |
| AUG-BR-01 | The same pain exists in English-capable Brazilian nearshore agencies | Brazil agency founders/CTOs | Review tax + one-repo evidence sprint | Founder LinkedIn + direct | 25-min fit call | 0 paid; 2h | 0 | 0 | 0 | 0 | 0 | 0 | 0 | $0 | Pending |
| AUG-CONTENT-01 | Honest proof/gap posts generate qualified inbound | Non-US agency engineering leaders | What is proven vs what needs a real repo | LinkedIn/X | Nominate one repo task | 0 paid; 2h | 0 | 0 | 0 | 0 | 0 | 0 | 0 | $0 | Pending |

Definitions:

- **Qualified conversation:** agency matches the ICP; buyer or strong champion attends; confirms a current review/evidence pain; can nominate a repository and reviewer within 14 days.
- **Repository accepted:** scope, repository, reviewer, build/test command, and data-handling route are agreed.
- **Trial completed:** the customer receives a PR/branch and evidence, including failures; an internal green score is not enough.
- **Useful PR:** the customer reviewer says it is worth reviewing further or merging with bounded edits. Record rewrite effort and exact objections.
- **Pilot:** a dated, scoped continuation with owner and commercial terms. “Keep me posted” is not a pilot.
- **Revenue:** collected cash, not verbal intent, invoice sent, credits, or pipeline value.
- **Vanity only:** impressions, followers, likes, profile views, generic sign-ups, and unqualified replies.

## Stop/go scorecard for 31 August

### Go

Continue for 60 days only if all are true:

- at least **10 qualified conversations** across at least two non-US countries;
- at least **5 repository evaluations accepted** and **3 completed**;
- at least **2 useful PRs** by customer judgment, with no more than bounded edits;
- at least **2 paid continuation proposals** and **1 collected payment** (published Team minimum or an approved pilot fee);
- at least one buyer agrees to an attributable case study later, conditional on pilot success.

### Conditional extension—maximum two weeks

Allow one two-week extension only if there are 6–9 qualified conversations, at least 3 accepted repositories, at least 1 useful completed trial, and a named buyer with a dated payment decision. The memo must identify one specific bottleneck and one change. Do not extend for impressions or “interest.”

### Stop or pivot

- **Message/market failure:** fewer than 6 qualified conversations after 50 personalized contacts plus 10 warm-intro asks.
- **Trust/offer failure:** at least 8 qualified conversations but fewer than 2 repository acceptances.
- **Product failure:** five completed trials but fewer than 2 useful PRs, or repeated inability to finish a supervised real-repo run.
- **Commercial failure:** at least 3 useful PRs but zero paid continuation after a direct price ask.

These diagnoses lead to different decisions; do not “fix marketing” when real-repository output is the failure.

## Minimum access and safe mechanism

| Need | Minimum access | Safe mechanism |
|---|---|---|
| Repository/product truth | Read access to current repos and approved docs | Existing workspace; no production secrets |
| LinkedIn drafting | None | Draft locally; Forge posts manually after approval |
| LinkedIn assisted posting later | One signed-in session | Forge signs in himself in the in-app browser; approve each post/DM action. Prefer an official OAuth connector if one becomes available |
| X drafting | None | Draft locally; Forge posts manually after approval |
| X assisted posting later | One signed-in session or official OAuth | Forge signs in himself; no password, cookie export, recovery code, or session token sharing; approve each action |
| Outreach | Public company/person role and profile URL | Hand-curated list; Forge sends manually or approves each browser action; no bulk automation |
| Funnel measurement | Read-only activation-funnel and UTM reports | Product read-only/admin report or sanitized weekly export; no database password or raw user tokens |
| Trial operation | Dedicated evaluation org and least-privilege GitHub access | Customer-approved repo/fork, scoped GitHub app/PAT, approved provider key path, named reviewer, deletion/retention agreement |
| Ledger | Local Markdown/CSV initially | No secrets; names, roles, company, public URLs, consent/status, and aggregate outcomes only. Move to a Drive Sheet via OAuth only if collaboration requires it |

No paid ads are proposed. They would buy clicks before the repository-trial funnel is understood.

## Week-one drafts—approval required

### LinkedIn 1 — validation call

Most coding-agent demos stop at “the code was generated.”

The harder part starts after that: a senior engineer still has to reconstruct the plan, check what changed, run the right tests, and decide whether the diff is safe enough to hand to a client.

I’m testing a narrower workflow with ExecuteSpec in August:

- one bounded backlog item;
- plan and estimated model spend approved before execution;
- a pull request with build, test, review, and token evidence;
- a customer engineer decides whether the result is actually useful.

I’m looking for a small number of software-delivery agencies in South Africa, Nigeria, Kenya, and Brazil willing to test this on a real, low-risk repository task.

No benchmark pitch. No “replace your engineers” claim. The test is whether the PR is worth reviewing and whether you would pay to run the workflow again.

If this is a problem you own, reply or message me with the stack and the kind of backlog item that keeps getting deferred.

### LinkedIn 2 — proof boundary

An internal score is not proof that AI-generated code is correct.

ExecuteSpec has a 0–100 verification summary, but the useful part is underneath it: which checks were deterministic, which came from an LLM reviewer, which tests actually ran, whether the build passed, and what evidence is still missing.

The current product walkthrough is synthetic. The local contract and security test suites are strong. Broad real-repository coding quality is still what needs customer evidence.

That is the August goal: run bounded tasks on real repositories, show the failures as well as the passes, and ask the reviewer one question—“is this worth continuing?”

I would rather have three uncomfortable repository reviews than 30,000 impressions.

### X 1

coding-agent output is cheap. senior review time is not.

in august i’m testing one narrow ExecuteSpec workflow with software agencies outside the US: bounded spec → approved plan/cost → PR → inspectable build/test/review evidence.

the success metric is not a demo score. it is whether their engineer finds the PR worth reviewing.

### X 2

an AI “verified score” can easily become the model grading its own homework.

the only defensible version is an evidence index: actual diff, build result, tests that ran, reviewer provenance, missing checks, token spend.

the summary should never hide the ledger.

### X 3

current ExecuteSpec proof:

- plan + cost approval gate
- PR-oriented workflow
- build/test/review evidence with provenance
- local contract, tenant-isolation and UI validation

current gap:

- enough independently reviewed, real-repository outcomes

that gap is the august sprint.

### X 4

i’m looking for 5 software-delivery agencies in south africa, nigeria, kenya or brazil for a supervised real-repo test.

bring one low-risk backlog item and the engineer who would review it. i’ll show the plan and evidence before asking you to continue.

reply with your stack or DM me. no mass trial, no benchmark pitch.

## Week-one outreach drafts—approval required

### Warm introduction request

Hey [First name],

I’m running a bounded August validation sprint for ExecuteSpec with software-delivery agencies outside the US.

The narrow problem is not code generation. It is the review tax after an agent produces a diff—what changed, what actually passed, what it cost, and whether the result is safe enough to hand to a client.

I’m looking for founders or engineering leaders at 10–75 engineer agencies in South Africa, Nigeria, Kenya, or Brazil who can test one low-risk backlog item on a real repository. This is a supervised evaluation, not a broad product launch.

If one person comes to mind, would you be comfortable forwarding a short note? Please don’t introduce anyone unless the problem is relevant.

### Direct note to an agency founder/CTO

Hey [First name],

I noticed [specific public observation about their delivery model, stack, engineering post, or client work].

I’m testing a narrow workflow for software agencies: give an agent one bounded backlog item, approve the plan and model budget first, then get back a pull request with the build, tests, review evidence, and token ledger visible.

I’m not asking you to evaluate a benchmark or move a project. I want to learn whether the review/evidence problem is real in your delivery workflow. If it is, the next step is one supervised run on a low-risk repo task with your engineer reviewing the result.

Worth a 25-minute problem conversation next week? If this is not a current pain, a direct “not relevant” is useful too.

### One follow-up, then stop

Hey [First name] — one follow-up, then I’ll close the loop.

The fit is narrow: client-delivery team, existing GitHub repo with a repeatable build/test command, and one bounded backlog item an engineer can review in August.

If review effort after AI-generated changes is not a current problem, no action needed. If it is, I can show the evidence contract in 25 minutes before any repository access is discussed.

## Decisions and fresh evidence required from Forge

Only these items cannot be settled from the repository:

1. **Prospect-readiness:** may August prospects use the current dev environment, or must invitations wait for a named release gate? Current release evidence still contains blocked/limited rows.
2. **Commercial offer:** choose one—published Team pricing after one founder-approved proof run, or a fixed paid concierge pilot. If fixed, set the amount and included runs.
3. **Public identity boundary:** personal LinkedIn/X, ExecuteSpec company accounts, or both; and which employment/founder facts are explicitly approved for public use.
4. **Public proof packet:** provide any fresh approved customer PR, reviewer quote, paid invoice, official benchmark artifact, or live production run. Without it, all week-one copy stays hypothesis-led as drafted.
5. **Founder capacity:** confirm or replace the assumed four hours per week and maximum number of supervised repository runs.

