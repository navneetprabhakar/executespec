# ExecuteSpec Product Discovery Audit

**Owner:** Navneet Prabhakar (Forge)  
**Purpose:** Durable, meaningful checkpoints from the ExecuteSpec product-discovery conversation.  
**Location:** `~/Downloads/executespec-product-discovery-audit.md`  
**Started:** 2026-07-19 (Asia/Kolkata)

## Checkpointing convention

- Record a checkpoint after a substantive product decision, scope change, prioritisation, or journey/feature closure.
- Summarise meaning and rationale; do not copy the raw transcript.
- Preserve disagreements, rejected directions, constraints, and unresolved questions.
- Keep implementation detail out until the product structure requires it.
- Use sequential identifiers: `CP-001`, `CP-002`, and so on.

---

## CP-001 — Audience, product boundaries, feature taxonomy, and journeys

**Date:** 2026-07-19  
**Status:** Active product-discovery baseline

### Context

Conversations with senior and general users suggest ExecuteSpec can serve three materially different audiences:

1. Enterprise engineering organisations — the primary target.
2. Professional individuals — freelancers, consultants, and day-to-day developers doing serious work without enterprise administration.
3. Casual vibe coders — outcome-focused users who want to create and publish without understanding repositories, models, agents, or delivery pipelines.

The existing coding engine, planning, verification, delivery, governance, and project-context machinery provide substantial leverage. Professional packaging is closer to the current product. Casual usage requires new product behaviour in addition to new packaging.

### Decisions

#### 1. Use two product experiences, not three

- **Studio** serves professional individuals and casual builders.
- **Console** serves teams and enterprises.
- Casual and professional usage should coexist through progressive disclosure rather than explicit user modes.
- A project should be able to mature from casual creation into professional work without migration or regeneration.

Working URL model:

- `studio.executespec.ai`
- `console.executespec.ai`

Account, billing, client review, API, and published applications are supporting routes or infrastructure—not additional product surfaces.

#### 2. Keep the current monorepo and shared backend for now

- Do not split repositories or backend services during product discovery.
- Eventually, Studio and Console should have clearly separated frontend folders and independent builds.
- Do not restructure the backend until feature and journey boundaries are understood.
- Repository and module design is deliberately parked.

#### 3. Categorise features before assigning them to users

The shared feature spine is:

1. **Define** — prompts, attachments, clarification, templates, plans, estimates, approvals.
2. **Build** — agents, tools, local/cloud work, visual editing, multi-repo work, app backends and integrations.
3. **Understand** — search, code intelligence, architecture, memory, RAG, standards, ADRs, contracts.
4. **Verify** — builds, tests, browser journeys, reviewer, security checks, evidence and risk.
5. **Ship** — Git/PR, previews, deploys, domains, environments, rollback, monitoring and autofix.
6. **Collaborate** — projects, teams, external sharing, comments, approvals, activity and notifications.
7. **Control** — identity, RBAC, BYOK, model policy, usage, audit, SSO/SCIM, compliance and operations.
8. **Engage** — onboarding, progression, quests, app-health milestones, remixing and community. This is cross-cutting.

The working sequence is:

`Feature inventory → remove duplication → existing/partial/missing → keep/close/later → map to journeys → map to users → design experiences`

#### 4. Organise UX around user intent, not system concepts

Users enter through five primary journeys:

1. Create something new.
2. Change or improve something.
3. Fix something broken.
4. Understand something.
5. Review or approve work.

These converge into one lifecycle:

`Start → Align → Work → Inspect → Decide → Deliver → Continue`

Supporting journeys exist across the lifecycle:

- Recover from failure or restore a safe state.
- Collaborate and collect decisions.
- Govern policy before work and retain evidence afterward.

“Run” remains an internal execution record, especially useful in Console, but should not be the primary user mental model. Users think in terms of a change, fix, question, review, or delivered outcome.

#### 5. Casual parity and differentiation

Casual parity requires more than prompt-to-code:

- Persistent live preview
- Visual selection and editing
- Managed database/auth/storage/functions
- Browser-level testing and automatic repair
- Checkpoints and rollback
- One-click publish and sharing
- Custom domains, SEO, analytics, payments, integrations, templates, and assets

ExecuteSpec should not build a proprietary backend-as-a-service first. A typed provider abstraction with one initial managed backend integration is the pragmatic path.

Differentiation should be:

- A prototype can graduate into professional and governed software.
- Completion is backed by evidence rather than agent assertion.
- Code ownership and export are defaults.
- Recovery is explicit and trustworthy.
- Technical evidence is translated into Working, Tested, Secure, and Launch-ready states.

Gamification should reward verified outcomes, not prompts, tokens, generated lines of code, or spend. The proposed progression is:

`Idea → Prototype → Working → Tested → Published → Launch-ready → Live`

#### 6. Enterprise closure principle

The enterprise product already has broad feature coverage. The dominant gap is operational proof, hardening, and coherence—not lack of breadth.

The initial enterprise promise should be narrower than the total implemented surface. A credible first boundary is GitHub-first, GCP-first, BYOK-capable, plan-gated, evidence-backed delivery with clear exclusions for unfinished SCM, large-repository, identity, recovery, and certification claims.

### Parked or rejected directions

- Three separate user-facing products and URLs.
- Separate account, review, and administration products.
- A hostname-conditioned monolithic UI serving every audience.
- Immediate repository split or backend microservices decomposition.
- Mapping features directly to personas before building a neutral inventory.
- Gamification based on activity, streak pressure, token usage, or spending.
- Treating every existing enterprise surface as launch-complete.

### Open questions

1. What is the complete de-duplicated feature inventory under the eight feature categories?
2. Which lifecycle stage does each feature support?
3. Where does the current journey break despite backend capability existing?
4. Which features are existing, partial, or missing?
5. Which features must be kept, closed for launch, or deferred?
6. What exact supported boundary defines Enterprise v1?
7. Only after the above: which features and depth belong in Studio versus Console?

### Next step

Build the complete feature inventory under the eight categories, then map each feature to the seven-stage lifecycle. Do not assign features to user types yet.

---

## CP-002 — Enterprise knowledge-to-work and operations journeys

**Date:** 2026-07-19

### Input considered

Additional possible enterprise journeys:

- Understand and create technical and product documentation.
- Confluence and Jira activities.
- Pull today's work directly from Jira.
- Use Slack as an interface.
- Understand the state of deployed systems through cloud-provider access and provider CLI tools.
- Run brainstorming sessions.

### Product interpretation

These should not become six unrelated top-level journeys. They form two coherent loops, with Slack acting as an access channel across them.

#### 1. Knowledge-to-work loop

`Brainstorm → Record decision → Create/update docs → Create/select Jira work → ExecuteSpec delivery → Evidence → Update Jira/Confluence`

- **Technical and product documentation:** core extension; pursue near-term. Separate understanding, authoring, drift detection, review, and publication. Every generated or updated document must retain source references and freshness evidence.
- **Jira activities:** core extension; pursue near-term as work intake and status/evidence feedback, not as a replacement Jira UI.
- **Today's work:** present a readiness-ranked Jira queue rather than automatically executing every assigned issue. Readiness includes repository mapping, acceptance criteria, dependency/blocker state, permissions, and policy.
- **Confluence:** connector and publication destination, not a standalone product journey. Declare a system of record per document; avoid uncontrolled bidirectional synchronization.
- **Brainstorming:** useful only when it produces durable outputs—decisions, assumptions, alternatives, unresolved questions, ADR/PRD changes, or Jira work. Generic chat alone is not differentiated.

#### 2. Operations loop

`Observe environment → Diagnose/drift → Propose change → Approve → Execute controlled action → Verify → Audit/rollback`

- **State of systems:** strategically relevant but a separate future product track because it introduces a materially larger authority and risk boundary.
- Start with read-only environment intelligence: inventory, health, deployment state, configuration drift, incidents, and cost signals.
- Cloud CLIs are an implementation mechanism, not the product contract. Prefer typed, allow-listed actions backed by short-lived federated identity and least-privilege roles.
- Mutating operations require explicit scoped approval, preconditions, verification, audit evidence, and a rollback path.

### Surface interpretation

- **Slack is a satellite interface**, not a third primary surface and not a replacement for Console.
- Appropriate Slack actions: notifications, concise status, project questions, bounded commands, approve/reject, and deep links.
- Inappropriate Slack actions: rich code/evidence inspection, broad configuration, unrestricted environment operations, or decisions whose context cannot fit safely in the conversation.

### Tentative sequence

1. Structured brainstorming that produces decisions and artifacts.
2. Documentation understanding, authoring, drift detection, and governed publication.
3. Jira work intake, readiness-ranked “today” view, and evidence/status write-back.
4. Confluence read/publish within the documentation journey.
5. Slack as a bounded satellite after action dispatch, approvals, identity mapping, and delivery reliability are mature.
6. Environment intelligence as a separate read-only-first track; mutation later.

### Open decisions

1. Which document classes are repo-first and which are Confluence-first?
2. What exact fields and policies define a Jira issue as ready for execution?
3. Which actions are permitted in Slack, and which always deep-link to Console?
4. Which cloud provider and environment type should anchor the first read-only environment-intelligence slice?
5. What evidence and rollback contract is mandatory before any environment mutation is allowed?

### Next step

Add the knowledge-to-work and operations loops to the neutral journey inventory, then map their required capabilities into the existing eight feature categories. Keep persona and surface assignment downstream of that inventory.

---

## CP-003 — Context fabric and personalization architecture

**Date:** 2026-07-19

### Corrections to capability status

- Jira Cloud and Confluence Cloud integrations already exist in the codebase and were tested historically. Treat them as revalidation, hardening, and productization work—not greenfield connectors.
- Slack already has a direct OAuth installation, project-channel mapping, notifications, verbosity controls, and delivery logging. Marketplace distribution is an optional installability/GTM step rather than the core capability.
- PagerDuty and ServiceNow inbound/outbound adapters already exist as part of the planned operations journey. They currently provide incident-triggered analysis and result write-back, not a complete environment-state model.

### Architectural decision

Personalization, memory, and retrieval are separate concerns. Do not select RAG, GraphRAG, or text lookup as one universal solution.

Use a layered context fabric:

1. **Structured deterministic lookup** for identity, tenant, permissions, explicit user preferences, project configuration, work-item IDs, service IDs, environment IDs, and live source state.
2. **Exact/lexical retrieval** for identifiers, filenames, configuration keys, error strings, Jira keys, incident numbers, and quoted text.
3. **Semantic RAG** for unstructured prose: Confluence pages, Jira descriptions/comments, product documents, technical documents, runbooks, retrospectives, and historical summaries.
4. **Typed relationship expansion** for connections among user, team, project, repository, service, environment, document, work item, deployment, incident, decision, and run. Build explicit edges first; add graph-aware retrieval only for queries that require multi-hop reasoning.
5. **Live API/tool retrieval** for “today’s work” and current system state. These are temporal operational queries and must not be answered from a stale embedding index.

### Personalization model

Personalization should be a governed, inspectable memory model rather than embeddings over chat history.

- Separate explicit preferences from inferred preferences.
- Store scope: user, team, project, service, environment, and session.
- Store provenance, confidence, timestamps, expiry, sensitivity, and the evidence that produced an inferred memory.
- Let users view, edit, disable, and delete personal memories.
- Never let inferred preferences override org policy, project standards, security controls, or the current explicit request.

Recommended precedence:

`Security/org policy → current request and approval → project/service conventions → explicit user preferences → inferred preferences`

### Current-code implication

- Existing document retrieval already uses semantic retrieval with a governed lexical fallback; this is a suitable foundation.
- Existing project memory is a compacted project snapshot plus recent run ledgers. It is useful project history, but too coarse to serve as the personal-memory model by itself.
- The integration hub currently submits webhook-triggered runs without a project binding. A source-to-project/service/environment binding is required before integrations can inherit the correct repositories, memory, policy, and evidence boundary.

### Product implication

The cross-surface differentiator should be continuity of context:

`Same user + same project/service + same governed memory`, whether the entry point is Console, CLI, Jira, Confluence, Slack, PagerDuty, or ServiceNow.

The user should feel that ExecuteSpec remembers their working preferences and understands the current organisational context, while every retrieved fact remains attributable, permission-scoped, fresh, and correctable.

### Next step

Define the context object model and precedence rules before designing more connector journeys. Then map each existing connector to the scopes, entities, relationships, freshness rules, and permissions it contributes.

---

## CP-004 — Master enterprise integration catalog

**Date:** 2026-07-19

### Catalog structure

Integrations are one ecosystem attached to the shared context fabric, not separate product surfaces. Every connector must declare five independent capabilities:

1. Read/context retrieval.
2. Trigger/event ingestion.
3. Governed action or write-back.
4. Artifact/evidence publication.
5. Identity, permission, freshness, and audit behavior.

### Anchor integrations

- **Source control:** GitHub, GitLab, Bitbucket Cloud/Data Center, Azure Repos.
- **Planning and work:** Jira Cloud/Data Center, Azure Boards, GitHub Issues, GitLab Issues.
- **Knowledge and documents:** Confluence Cloud/Data Center, Microsoft SharePoint/OneDrive, Google Drive/Docs.
- **Design and product context:** Figma and FigJam.
- **Communication:** Slack and Microsoft Teams.
- **ITSM and incidents:** ServiceNow and PagerDuty.
- **Cloud and runtime:** AWS, Microsoft Azure, Google Cloud, Kubernetes.
- **Delivery:** GitHub Actions, GitLab CI/CD, Jenkins, Azure Pipelines, Argo CD, Terraform/Terraform Cloud.
- **Observability:** Datadog, Splunk, Dynatrace, New Relic, Grafana/Prometheus, Elastic, Sentry.
- **Security and quality:** SonarQube/SonarCloud, Snyk, GitHub Advanced Security, Wiz, Veracode, Checkmarx.
- **Identity and access:** Microsoft Entra ID, Okta, Google Workspace.
- **Artifacts and secrets:** JFrog Artifactory, Sonatype Nexus, major cloud container registries, HashiCorp Vault, and cloud-native secret managers.

### Expansion ecosystem

- **Work management:** Linear, Asana, monday.com, ClickUp.
- **Knowledge:** Notion, Box.
- **Product discovery and collaboration:** Miro, Productboard, Aha!.
- **Incident operations:** incident.io, FireHydrant.
- **Delivery:** CircleCI, Buildkite.
- **Observability:** Honeycomb, AppDynamics.
- **Security:** Semgrep, Prisma Cloud, Aqua Security.
- **Identity:** Ping Identity, OneLogin.
- **Customer/support adjacency:** Zendesk and Salesforce, when customer feedback or support incidents become engineering inputs.

### Existing-code status

The current repository already contains meaningful implementation for GitHub, Bitbucket, Jira, Confluence, Slack, ServiceNow, and PagerDuty. These are the first productization and revalidation set. Figma is the next anchor addition because it connects product/design intent to implementation and visual verification.

### Figma journey

`Figma/FigJam node → design context and variables → mapped code components → implementation plan → code and preview → visual comparison → evidence/comment back`

Figma context should include selected nodes, component hierarchy, variants, variables/tokens, assets, annotations, version, and links to Jira/document/code entities. Do not treat a whole Figma file as an undifferentiated embedding corpus.

### Prioritization

1. **Revalidate and productize existing:** GitHub, Bitbucket, Jira, Confluence, Slack, ServiceNow, PagerDuty.
2. **Add enterprise anchors:** Figma, GitLab, Azure DevOps, Teams, SharePoint, AWS/Azure/GCP, Kubernetes, Datadog/Splunk, Entra/Okta.
3. **Add ecosystem breadth through a connector SDK/MCP path:** the remaining catalog, based on customer demand rather than bespoke roadmap commitments.

### Guardrail

Do not claim native support merely because an MCP server exists. Native support requires ExecuteSpec-owned identity mapping, authorization, scope binding, provenance, freshness, action confirmation, observability, failure handling, and audit evidence.

---

## CP-005 — Adversarial assessment before enterprise UX

**Date:** 2026-07-19

### Adversarial verdict

The direction is strategically coherent only under one narrow product contract:

> Assemble governed context, propose or execute bounded engineering work, and return verifiable evidence.

If documentation, enterprise search, personal memory, integrations, Slack, design generation, and operations become independent product lines, ExecuteSpec becomes an unfocused combination of enterprise-search assistant, Jira bot, coding agent, and SRE bot.

Do not proceed into detailed enterprise UX until the context and authority contracts below are settled. Journey-level UX exploration can continue, but detailed screens would otherwise encode unstable concepts.

### Critical product risks

1. **Horizontal-platform sprawl:** supporting every famous enterprise tool can become a connector checklist with no deep workflow. Native support must be limited to integrations that complete an ExecuteSpec journey.
2. **Unclear buyer and promise:** coding delivery, knowledge management, and production operations have different owners, budgets, risk tolerances, and proof requirements. Operations must remain a separately gated track.
3. **Personalization versus governance:** user preference can silently conflict with org policy, project standards, or the current task. Explicit precedence and scope are mandatory.
4. **Creepy or incorrect memory:** inferred preferences without provenance, confidence, expiry, and user controls will reduce trust rather than create continuity.
5. **False “today” and “current state”:** embeddings and compacted memory cannot answer live work or environment questions reliably. These require source-of-truth queries with freshness timestamps.

### Critical technical and security risks found in the current implementation

1. **Missing scope binding:** integration-triggered runs are submitted with no project binding, so the correct repositories, project memory, service, environment, and policy cannot be inherited.
2. **Global actor substitution:** manual Jira/Confluence triggers authenticate the caller but submit through a configured global integration-runner identity rather than preserving the initiating actor and scope.
3. **Cross-tenant integration-run listing:** the current integration-run list requires authentication but is not filtered by org/user/project.
4. **Credential architecture drift:** newer org/user credential storage coexists with application-level adapter configuration and clients. Status can report an org integration as available while the execution adapter is bound to different global credentials.
5. **Source-ACL flattening:** Jira/Confluence reference extraction uses org credentials. If those credentials can read more than the initiating user, retrieved context can exceed the user’s source-system permissions.
6. **Prompt injection through integrations:** raw Jira, Confluence, Slack, incident, runbook, and design content is untrusted input. It must remain a labelled evidence block and never become executable instruction or authority.
7. **Non-durable write-back:** general outbound integration updates and Slack notifications are dispatched through in-memory application events and executors. Restarts, queue saturation, or process failure can lose delivery despite local logs.
8. **ServiceNow unsigned-webhook path:** the current adapter accepts a webhook when both the signature header and configured secret are absent. A public webhook endpoint must fail closed.
9. **Unsafe lifecycle automation:** PagerDuty and ServiceNow contain auto-resolve paths, but the broader evidence, authority, approval, and rollback model for operational mutation is not established.

### Context architecture risks

- Pure text lookup misses paraphrased organisational knowledge.
- Pure vector RAG returns semantically similar but stale or authority-inappropriate content.
- Full GraphRAG creates ontology and synchronization complexity before core entity bindings are stable.
- Combining structured, lexical, vector, graph, and live retrieval on every request creates unacceptable latency, cost, and debugging difficulty.
- Relationship edges must distinguish authoritative links from model-inferred links. Inferred edges cannot authorize actions.

### Adversarial scenarios the architecture must survive

1. A Jira issue links an obsolete Confluence page and a newer Figma revision. The system must show the conflict, not silently choose one.
2. An incident note contains instructions to expose credentials. The system must treat it as evidence, not an instruction.
3. A Slack user asks to approve work but lacks project authority despite belonging to the connected workspace. The action must be denied.
4. A user changes teams. Personal preferences may follow them; project history and restricted context must not.
5. A webhook is replayed after a restart. Run creation and external write-back must remain idempotent and attributable.
6. An environment changes after indexing. “State of system” must use live evidence and declare observation time.
7. Figma and production code intentionally diverge for accessibility or platform constraints. Visual mismatch alone must not be treated as a defect.

### Gates before detailed UX

1. Define the canonical context entities, scopes, relationships, precedence, freshness, and provenance model.
2. Define explicit and inferred personal-memory schemas plus user controls.
3. Define the connector capability contract: read, subscribe, trigger, act, publish, identity, and audit.
4. Define external-identity mapping and source-level permission enforcement.
5. Define durable inbox/outbox, retry, idempotency, and reconciliation behavior.
6. Define the action authority matrix for Console, CLI, Slack, Jira, ServiceNow, PagerDuty, and cloud environments.
7. Select a narrow productization set: existing integrations plus Figma; defer broad catalog implementation.
8. Build adversarial context-retrieval and cross-tenant test suites before claiming personalization or multi-source understanding.

### Proceed / park

- **Proceed:** context-fabric specification, source bindings, governed personal memory, Jira/Confluence revalidation, Figma design-context spike, read-only operations context.
- **Proceed cautiously:** Slack interactive actions and marketplace distribution after identity and durable-delivery contracts.
- **Park:** dedicated graph database, broad connector catalog implementation, autonomous cloud mutation, automatic incident resolution, and generic enterprise-search positioning.

---

## CP-006 — Product category reframing

**Date:** 2026-07-19

### Correction

ExecuteSpec is no longer being defined as a coding agent. Enterprise buyers are looking for a software solution spanning the software lifecycle. The coding agent is a critical execution engine inside the product, not the product category or boundary.

### Revised category

Tentative product category:

> **An AI-native enterprise software execution platform—a system of action across the software lifecycle.**

Existing enterprise systems remain systems of record:

- Jira/Azure Boards for planned work.
- Confluence/SharePoint for organisational knowledge.
- Figma for design intent.
- GitHub/GitLab/Bitbucket/Azure Repos for source and review.
- CI/CD and cloud platforms for delivery and runtime state.
- PagerDuty/ServiceNow for incidents and operational workflows.
- Slack/Teams for communication and lightweight interaction.

ExecuteSpec connects context from those systems, helps people reason and decide, performs governed work, verifies the outcome, and writes evidence back.

### Lifecycle boundary

`Intent → Understand → Decide → Plan → Build/change → Verify → Release → Operate/recover → Learn/document`

The current coding agent primarily powers **Build/change** and parts of **Verify**. Documentation, Jira/Confluence, Figma, brainstorming, operational state, PagerDuty, ServiceNow, Slack, personal memory, and the context fabric cover the rest of the lifecycle.

### Product objects

“Run” is an implementation/execution record, not the top-level product object. The enterprise product needs a lifecycle object model centred on:

- **Objective/work:** the outcome being pursued.
- **Context:** governed evidence gathered from connected systems.
- **Decision:** alternatives, approvals, rationale, and unresolved questions.
- **Action:** bounded work performed by a person or agent.
- **Change:** code, document, design, configuration, infrastructure, or operational mutation.
- **Evidence:** verification, provenance, freshness, policy and outcome proof.
- **Learning:** updated documentation, memory, work status, and operational knowledge.

### Revised adversarial boundary

Breadth across the lifecycle is not automatically product sprawl. It becomes sprawl when capabilities do not share the same objective, context, authority, action, and evidence model—or when ExecuteSpec tries to replace the connected systems of record.

The platform remains coherent when every capability contributes to the same loop:

`Bring context → reach a decision → perform governed work → verify → update systems of record`

### Surface implication

Do not create separate products for documentation, planning, coding, operations, and integrations. Keep one enterprise Console with lifecycle-oriented modules and progressive permissions. Slack, Teams, Jira, IDE/CLI, and other integrations are entry points or execution channels, not additional primary surfaces.

### Next step

Redo the enterprise domain model and adversarial gates using the software-lifecycle platform boundary, then define the enterprise journeys around objectives and outcomes rather than coding runs.

---

## CP-007 — Cross-team engineering intelligence and universal query

**Date:** 2026-07-19

### New first-class capability

Cross-team context is part of the enterprise product boundary. Multiple team members need to understand current and planned work, ownership, dependencies, historical decisions, subject-matter expertise, and collaboration opportunities.

Create a dedicated Console capability, tentatively named **Engineering Intelligence**, with a primary interaction called **Ask ExecuteSpec**.

The correct product promise is:

> All authorised software-lifecycle information is queryable, attributable, fresh, and actionable.

It must not promise unrestricted access to all organisational information.

### Queryable domains

- **People and teams:** declared roles, ownership, current responsibilities, relevant experience, availability signals that policy permits, and organisational boundaries.
- **Current and planned work:** objectives, Jira/Azure Boards work, milestones, dependencies, blockers, overlaps, approvals, and expected delivery windows.
- **Software assets:** repositories, services, APIs, data contracts, architecture, environments, deployments, and ownership.
- **Knowledge and design:** Confluence/SharePoint documents, ADRs, PRDs, runbooks, Figma/FigJam designs, standards, and generated documentation.
- **Decisions:** alternatives considered, rationale, approvers, unresolved questions, exceptions, and superseded decisions.
- **History:** code changes, reviews, releases, incidents, remediation, operational outcomes, ExecuteSpec actions, and evidence.
- **Expertise:** evidence-backed subject-matter knowledge derived from ownership, authored documents, code changes, reviews, incidents, and recent successful work.
- **Collaboration opportunities:** overlapping objectives, dependent changes, reusable work, potential reviewers, experts, handoffs, and teams affected by a proposal.

### Example queries

- Who is currently changing checkout, and what is planned next?
- Which teams will be affected by this API change?
- Who has recent evidence of expertise in Kafka schema evolution?
- Why was Redis selected, and is that decision still current?
- What changed after the last payment-service incident?
- Which planned initiatives overlap with this proposal?
- Who should review this change, and why?
- What work is blocked on my team, and by which external dependencies?

### Information architecture

Use a **Software Work Graph** as the logical domain model—not necessarily a dedicated graph database.

Core nodes include user, team, objective, work item, document, decision, design, repository, service, API, environment, deployment, incident, action, change, and evidence. Edges must be typed, sourced, timestamped, permission-scoped, and classified as authoritative or inferred.

Universal query combines:

1. Structured queries for ownership, assignments, plans, statuses, permissions, and dates.
2. Exact/lexical retrieval for identifiers and literal evidence.
3. Semantic retrieval for prose and fuzzy questions.
4. Relationship expansion for cross-team and multi-hop questions.
5. Live connector calls for current work and operational state.

### Answer contract

Every answer must show:

- Direct answer or explicit uncertainty.
- Source citations and links.
- Observation time/freshness.
- Scope used for the answer.
- Whether relationships or expertise claims are declared or inferred.
- Conflicts between sources.
- Available next actions, subject to authority.

### Subject-matter expertise guardrail

Do not create an opaque employee-ranking or productivity-scoring system. Expertise suggestions must expose their evidence and recency—for example code ownership, relevant reviews, document authorship, incident resolution, or recent delivery—and allow correction. Private activity and inferred performance judgments must not be exposed to managers or peers as facts.

### Collaboration actions

From a query result, authorised users may propose a shared objective, request review, invite a collaborator, create a handoff, link overlapping work, notify an affected team, or create/update a work item. ExecuteSpec must not silently assign work or expose private schedules.

### Surface decision

Engineering Intelligence is a dedicated feature within the existing enterprise Console, not another URL/product. A universal query entry can be available throughout Console, while a full-page Intelligence workspace supports deeper exploration, source inspection, saved queries, and collaboration actions.

### Next step

Add people, teams, objectives, plans, expertise evidence, and collaboration relationships to the enterprise domain model and authority matrix before designing the Intelligence UX.

---

## CP-008 — Software Work Graph domain layers

**Date:** 2026-07-19

### Graph strategy

Do not create isolated knowledge graphs for code, documents, discussions, and work systems. Create one permission-aware, temporal **Software Work Graph** with domain layers. The value comes from relationships across layers: a Jira item links to a Figma node, decision, repository change, deployment, incident, team, and expert.

The graph stores normalized entities, typed relationships, source pointers, access scope, freshness, and provenance. Full document bodies, complete diffs, raw logs, and entire conversations remain in their source or retrieval store rather than being duplicated as graph nodes.

### Required domain layers

1. **Code and source history**
   - Repositories, branches, commits, pull/merge requests, files, symbols, APIs, tests, dependencies, releases, tags, and CODEOWNERS.
   - Commit history, blame/annotation, authorship, reviewers, changed symbols, issue links, and deployment lineage.

2. **Documents and knowledge**
   - Confluence, SharePoint/OneDrive Word documents, Google Drive/Docs, Notion, repository wikis/pages, README and Markdown files, ADRs, runbooks, PDFs, and uploaded documents.
   - Document, section, version, author, owner, citations, supersession, linked requirements, designs, services, and decisions.
   - Standalone Word/PDF files are uploaded sources; SharePoint/OneDrive files retain their live source identity and permissions.

3. **Work and planning**
   - Jira/Azure Boards/GitHub or GitLab issues, epics, stories, tasks, sprints, milestones, acceptance criteria, status, assignees, dependencies, blockers, and comments.
   - Jira comments sit at the intersection of work and discussion context; they remain attached to the work item and retain author, timestamp, edit history, and permissions.

4. **Discussion and collaboration**
   - Slack/Teams threads, selected channel discussions, document comments, PR reviews, design comments, meeting notes/transcripts, and explicit brainstorming sessions.
   - Slack/Teams search should be conditional and query-time under the initiating user’s authority. Do not centrally ingest every message by default. Only explicitly captured decisions or curated threads become durable shared knowledge.

5. **Design and product intent**
   - Figma/FigJam files, frames, components, variants, variables, assets, annotations, prototypes, comments, versions, and Code Connect mappings.
   - Product requirements, outcomes, metrics, experiments, roadmap relationships, and design-to-code links.

6. **People, teams, ownership, and expertise**
   - Users, teams, roles, memberships, service/repository ownership, CODEOWNERS, on-call rotations, declared skills, and evidence-backed expertise.
   - Expertise evidence comes from relevant work, reviews, documents, incidents, and ownership; it is not a productivity score.

7. **Architecture and dependency context**
   - Services, components, APIs, events, schemas, data contracts, packages, databases, queues, cloud resources, consumers, dependencies, and invariants.
   - Links architecture intent to actual code, owners, deployments, incidents, and standards.

8. **Delivery and release context**
   - CI/CD workflows, builds, test results, artifacts, security scans, approvals, feature flags, deployments, releases, rollback events, and environment promotion.

9. **Runtime and operations context**
   - Services, environments, cloud resources, health, SLOs, alerts, incidents, PagerDuty, ServiceNow, dashboards, configuration drift, remediation, and post-incident learning.
   - High-volume logs, metrics, and traces remain in observability systems; the graph stores pointers, summarized findings, time windows, and relationships.

10. **Decisions, governance, and policy**
    - Decisions, alternatives, rationale, approvers, ADRs, standards, exceptions, risk acceptances, compliance controls, security findings, and supersession history.

11. **ExecuteSpec objectives, actions, and evidence**
    - Objectives, context bundles, plans, approvals, agent/human actions, runs, changes, validation results, costs, evidence, outcomes, and learnings.
    - This layer connects the platform’s actions back to every external system of record.

12. **Customer and product-feedback context — optional/gated**
    - Support tickets, product feedback, feature requests, customer-impact signals, and experiment results from systems such as Zendesk or Salesforce.
    - Include only when an organisation explicitly connects these sources and grants engineering visibility.

### Cross-cutting graph contract

Every node and edge must carry:

- Tenant and visibility scope.
- Source system and immutable source identifier.
- Source URL/pointer.
- Authoritative versus inferred classification.
- Provenance/evidence references.
- Observed-at time plus valid-from/valid-to where applicable.
- Last synchronized time and freshness state.
- Confidence for inferred relationships.
- Sensitivity and retention policy.
- Schema/version for safe evolution.

Inferred edges can assist discovery but can never grant access, approve work, establish ownership, or authorize an action.

### Retrieval posture

- Graph traversal answers relationships and multi-hop questions.
- Structured source queries answer status, assignments, plans, and current state.
- Lexical search answers identifiers and exact text.
- Vector retrieval answers semantic questions over prose.
- Live tools answer volatile operational questions.

### Initial implementation order

1. People/team/ownership, project, repository, service, objective, and work-item spine.
2. Code/PR/commit and Jira/Confluence relationships.
3. Decisions, Figma, documentation versions, and expertise evidence.
4. Delivery/deployment and PagerDuty/ServiceNow incidents.
5. Conditional Slack/Teams and meeting context.
6. Optional customer/product-feedback sources.

---

## CP-009 — Startup lens: platform vision versus market-entry wedge

**Date:** 2026-07-19

### Assessment

The long-term enterprise platform vision is broadly covered. The startup strategy is not yet complete. Attempting to build the full Software Work Graph, broad integration catalog, delivery platform, enterprise intelligence, and operations product before proving a narrow paid workflow would create excessive time-to-value, integration maintenance, security scope, and sales complexity.

### Proposed entry wedge

The strongest initial wedge reuses the product’s existing execution and verification strength:

> **Turn approved work from Jira/Confluence/Figma into a verified change and return evidence to the team’s existing systems.**

Initial closed loop:

`Jira objective + linked Confluence/Figma context → clarification/plan → approval → implementation → tests/review/preview → PR → evidence and status write-back → searchable history`

This produces a measurable outcome rather than asking customers to purchase an abstract knowledge graph or generic AI assistant.

### Initial customer profile

- Cloud-first software/product organisations with roughly 50–500 engineers.
- Existing GitHub, Jira, Confluence, Slack, and increasingly Figma usage.
- Enough cross-team coordination pain to value context, but less procurement and integration complexity than the largest regulated enterprises.
- Initial buyer: VP Engineering, Head of Engineering, Platform Engineering leader, or engineering-productivity leader.
- Primary users: engineering managers and developers; PM/design/reliability participants enter through linked objectives and approvals.

### Initial native integration boundary

- GitHub.
- Jira.
- Confluence.
- Slack notifications and bounded actions.
- Figma design context.
- One CI path and preview/deployment path.

GitLab, Bitbucket, Azure DevOps, Teams, SharePoint, ServiceNow, PagerDuty, cloud/observability breadth, and marketplace distribution remain expansion paths or design-partner-specific requirements. Existing implementations can be revalidated without making every connector part of the default launch promise.

### Product expansion sequence

1. **Delivery wedge:** objective/context to verified PR and evidence write-back.
2. **Engineering Intelligence:** query the work, code, decision, ownership, and evidence graph generated by real usage plus selected existing sources.
3. **Cross-team coordination:** overlap detection, expert/reviewer discovery, dependency and handoff workflows.
4. **Operations:** incident to diagnosis, proposed remediation, verified change, and learning; read-only state before mutation.
5. **Enterprise breadth:** identity, governance, regional deployment, additional native connectors, data controls, and procurement requirements.

These can become modules of one Console; they should not launch as separate products.

### Data flywheel

Do not require a complete organisation-wide graph before the product becomes useful. Each completed objective should create high-quality graph facts—work item, context, decision, code change, reviewers, evidence, deployment and outcome. Intelligence becomes richer as a consequence of delivered work, while selective source ingestion provides cold-start context.

### Moat hypothesis

The connector catalog, coding agent, vector index, and graph schema are individually reproducible. The potential moat is the permission-aware longitudinal record linking organisational intent, decisions, actions, changes, verification evidence, operational outcomes, and people/teams across the lifecycle. It becomes defensible only through repeated trusted usage and workflow adoption, not by architecture alone.

### Startup invalidation risks

- Customers will not grant the permissions required for cross-system context.
- Engineers do not trust agent-created changes enough to adopt the workflow repeatedly.
- The graph has a cold-start problem and provides weak value before substantial ingestion.
- Native connector maintenance consumes the engineering team.
- The buyer cannot connect product value to cycle time, quality, incident cost, or headcount leverage.
- Existing systems’ built-in AI features are “good enough” for disconnected tasks.
- Enterprise procurement cycles exceed startup runway.

### Required proof metrics

- Ready-work-to-PR cycle time.
- Accepted PR/change rate.
- First-pass build/test success.
- Human review effort and rework.
- Time spent gathering context before work begins.
- Duplicate or conflicting work detected.
- Time to identify an appropriate reviewer/SME.
- For later operations: incident investigation and remediation time.

### Startup gates before broad build-out

1. Recruit 5–10 design partners matching the initial profile.
2. Prove repeated use of the closed-loop delivery journey, not one-off demos.
3. Validate that customers will connect GitHub, Jira, Confluence, Slack, and Figma with sufficient permissions.
4. Establish measurable ROI and a clear budget owner.
5. Confirm that the graph-derived Intelligence experience improves because of delivery usage.
6. Expand native integrations only when multiple paying customers require the same connector/workflow.

---

## CP-010 — Engineering Intelligence is P1

**Date:** 2026-07-19

### Priority correction

The cross-team **Engineering Intelligence** capability—including the queryable Software Work Graph—is a **P1 feature**, not a distant expansion or optional intelligence layer.

### Revised sequencing

- **P0:** Close and productize the core enterprise delivery loop: objective/context intake → decision/approval → governed execution → verification → PR/outcome → evidence write-back.
- **P1:** Make lifecycle information queryable across teams: current/planned work, ownership, history, decisions, documents, code context, expertise evidence, dependencies, overlaps, collaboration opportunities, and authorised next actions.
- **P2:** Deep operational intelligence and broader connector expansion, followed by carefully governed mutation.

### P1 boundary

P1 does not require every graph layer or enterprise connector to be complete. It requires a useful graph spine and trustworthy query experience across the initial native integration set:

- People, teams, ownership, objectives, projects, repositories, services, and work items.
- Git/PR/commit/code-history context.
- Jira work and comments.
- Confluence/repository documentation.
- Figma design context.
- ExecuteSpec decisions, actions, changes, and evidence.
- Conditional Slack context where authorised.

PagerDuty, ServiceNow, deployment, environment, and broader observability layers can enrich the same graph incrementally without blocking P1 launch.

### P1 success criteria

- Users can ask cross-team questions and receive attributable, permission-correct answers.
- Answers expose sources, freshness, conflicts, and declared versus inferred relationships.
- The system can identify work overlap, dependencies, ownership, reviewers, and evidence-backed SMEs.
- Query results lead into bounded collaboration or delivery actions.
- The feature provides useful value with the initial integration set and does not require complete organisation-wide ingestion.

---

## CP-011 — Missing practices from high-performing engineering teams

**Date:** 2026-07-19

### Core gap

The current platform direction covers context, coordination, execution, verification, documentation, and operations. The major missing theme is **engineering flow and delivery-system improvement**. High-performing teams optimise small-batch flow, feedback speed, deployability, platform self-service, production outcomes, and learning—not merely coding throughput.

### Missing product capabilities

1. **Value-stream and flow intelligence — P1 candidate**
   - Trace objectives from idea through design, decision, implementation, review, release, customer outcome, and learning.
   - Measure queue time versus active time, blocked time, rework, review latency, batch size, long-lived branches, handoffs, WIP, deployment lead time, and recovery.
   - Detect bottlenecks and oversized work; recommend smaller independently valuable slices.
   - Team/system-level measurement only. Do not rank individual developer productivity.

2. **Paved roads and golden paths — P1/P2**
   - Service/application templates, standard architecture patterns, approved libraries, CI/CD baselines, observability defaults, security controls, ownership metadata, runbooks, and deployment configuration.
   - Self-service creation of repositories, services, environments, pipelines, secrets bindings, dashboards, and documentation through governed templates.
   - Teams can deviate through an explicit exception/rationale path; the platform must not turn golden paths into mandatory bureaucracy.

3. **Progressive delivery and release safety — P1/P2**
   - Feature flags, canary/blue-green/rolling deployments, automatic health evaluation, staged promotion, rollback, and release evidence.
   - Backward-compatible database/schema changes and coordinated migration checks.
   - “Code complete” is not “delivered”; the platform follows the objective through production verification.

4. **Architecture fitness and contract evolution — P1 enrichment**
   - Executable architecture invariants and fitness functions.
   - API/event/schema compatibility, consumer impact, contract testing, version adoption, deprecation plans, and migration tracking.
   - Cross-team change simulation before implementation and release.

5. **Product outcomes and experimentation — P2**
   - Record the hypothesis and expected metric behind an objective.
   - Link flags/experiments, rollout cohorts, product analytics, customer feedback, and actual outcome to the delivered change.
   - Close or revise work when the intended outcome is not achieved.

6. **Software supply-chain integrity — enterprise P1/P2**
   - Dependency inventory, automated upgrades, SBOM, build provenance, artifact signing, vulnerability and license policy, secret scanning, and release attestations.
   - Evidence should flow into the same verification and query model.

7. **Operational readiness and service maturity — P1/P2**
   - Ownership, SLOs, on-call, dashboards, alerts, runbooks, capacity, backup/restore, disaster recovery, rollback, and dependency readiness.
   - A service maturity view should expose missing capabilities and evidence, not produce a vanity score.

8. **Test environments, data, and reproducibility — P1/P2**
   - Fast reproducible developer environments, ephemeral test environments, safe test-data provisioning, deterministic builds, flaky-test detection, and environment-parity evidence.

9. **Developer-friction intelligence — P1 enrichment**
   - Surface slow builds, flaky tests, painful setup, approval wait, environment wait, repeated access requests, noisy alerts, and review queues.
   - Diagnose system friction without turning tool telemetry into employee surveillance.

10. **Learning-to-standard loop — P1/P2**
    - Convert incidents, retrospectives, review findings, and failed changes into updated tests, runbooks, standards, templates, checks, and training material.
    - Track remediation actions to completion and detect repeated failure patterns.

### Practices the product should enable but not attempt to replace

- High-trust, blameless culture.
- Team autonomy and clear ownership.
- Cross-functional collaboration.
- Leadership prioritisation and WIP discipline.
- Synchronous or timely human review.
- Deliberate learning and mentoring.

These are organisational behaviours. ExecuteSpec can provide visibility, workflows, evidence, and reminders, but must not claim to manufacture culture or score individuals.

### Highest-priority additions

1. Add **Flow Intelligence** to the P1 Engineering Intelligence boundary.
2. Add **Golden Paths/Paved Roads** as the reusable execution standards that convert organisational knowledge into action.
3. Extend verification through **progressive delivery and production outcome**, not just PR/build/test completion.
4. Add the **learning-to-standard loop** so incidents and failures permanently improve future work.

---

## CP-012 — Final feature-set gate and Context Operations

**Date:** 2026-07-19

### Final missing foundation

Stop adding new software-lifecycle feature areas. One cross-cutting P1 foundation remains: **Context Operations**—the enterprise administration and trust layer for the Software Work Graph and Engineering Intelligence.

### Context Operations capabilities

- Connected-source inventory with owner, scope, credential mode, granted permissions, and last validation.
- Ingestion/synchronisation status, lag, freshness SLA, webhook/delta-cursor health, failures, retries, and reconciliation.
- Graph coverage by team, project, repository, service, document source, work system, and time range.
- Source lineage from every normalized node/edge and query answer back to the immutable source identifier and observation time.
- Source-ACL synchronisation, permission-drift detection, access-denial audits, and delegated-identity status.
- Conflict detection across systems of record, with explicit authority and resolution rules.
- Reindex/rebuild, quarantine, source disconnect, retention, legal hold, deletion, and downstream purge—including embeddings, graph facts, caches, summaries, and derived memories.
- Inferred-edge review, correction, expiry, and confidence controls.
- Personal-memory view/edit/delete/disable controls.
- Query-quality feedback, answer tracing, golden-query evaluation, stale-answer detection, and retrieval diagnostics.
- Cost, latency, corpus size, retrieval-mode, and live-source dependency telemetry.

Context Operations is not another primary surface. It is an administrative area inside Console plus visible freshness/provenance indicators in every Intelligence answer.

### Optional later addition

**Engineering economics/FinOps** can be P2: cloud and AI spend, cost per objective/change/service, cost regressions, capacity, and business outcome linkage. It is useful but does not block the initial platform definition.

### Feature discovery status

The enterprise feature map is sufficiently broad to freeze feature discovery and move into definition. Remaining work is not more brainstorming; it is resolving product contracts and scope.

### Finalisation checklist before UX

1. Lock the product statement: AI-native enterprise software execution platform / system of action.
2. Lock P0, P1, and P2 boundaries and explicit exclusions.
3. Define the initial customer profile, buyer, paid wedge, and measurable ROI.
4. Define lifecycle stages and canonical objects: objective, context, decision, action, change, evidence, and learning.
5. Define the Software Work Graph entities, edge semantics, temporal model, authority, and inference rules.
6. Define personal/team/project/org context scopes and precedence.
7. Define the connector capability contract and initial native integration set.
8. Define external identity mapping, delegated authorization, action approvals, and write-back authority.
9. Define Context Operations, data retention/deletion, source disconnect, and trust/evaluation contracts.
10. Define the minimum P1 query set and success criteria.
11. Define platform success metrics without individual developer scoring.
12. Only then design enterprise information architecture and journeys in Console.

### Recommendation

Freeze the feature set after adding Context Operations as a P1 dependency. Next produce a consolidated enterprise platform map and prioritised capability matrix, then enter UX design from those stable contracts.

---

## CP-013 — Enterprise product and UX documentation workspace created

**Date:** 2026-07-19

### Workspace

Created `~/Desktop/brainstorm` as the standalone product-discovery workspace. The current ExecuteSpec repository was not modified or split.

### Folder structure

- `enterprise/` — complete enterprise product and UX definition.
- `other-solutions/professional-individual/` — placeholder for later discovery.
- `other-solutions/casual-vibe-coder/` — placeholder for later discovery.
- `shared/` — portfolio boundaries, object model, and repository topology/extraction plan.

### Enterprise artifacts

- Product document
- Detailed PRD
- Feature map and P0/P1/P2 sequencing
- User journeys
- UX strategy and information architecture
- Detailed low-fidelity wireframe document
- Interactive Console wireframe exploration
- Engineering Intelligence specification
- Software Work Graph specification
- Enterprise integrations specification
- Trust, governance, personalisation, and Context Operations specification
- Roadmap, metrics, experiments, and validation gates
- Decisions and open questions

### Repository direction recorded

The target codebase is documented as five repositories:

1. `executespec-core`
2. `executespec-services`
3. `executespec-extensions`
4. `executespec-ui`
5. `executespec-analytics`

The migration plan uses contract-first extraction and a modular Services repository rather than immediate microservice decomposition or blind copying.

### Next step

Review the product document and PRD for strategic alignment, then review the Console information architecture and wireframes screen by screen. Resolve the open product contracts before turning the wireframes into high-fidelity design or beginning repository extraction.

---

## CP-014 — Documentation and wireframe verification pass

**Date:** 2026-07-19

### Verification completed

- Confirmed all 22 workspace artifacts exist under `~/Desktop/brainstorm` and the primary PRD and interactive wireframe are non-empty.
- Validated all relative Markdown links; no broken internal links were found.
- Exercised all five interactive wireframe views—Home, Objective, Intelligence, Service, and Context Operations—in the in-app browser.
- Verified desktop and narrow/mobile rendering and checked browser logs; navigation works and no console errors or warnings were produced.
- Compared PRD requirements, user journeys, feature priorities, wireframe coverage, and the five-repository topology.

### Findings requiring correction

1. Slack action priority is inconsistent: the feature map places notifications in P0 and bounded actions in P1, while the PRD and integrations document describe bounded actions as P0.
2. Dedicated wireframes remain necessary for structured brainstorming, document updates, failure/recovery, incident learning, connector setup, source-conflict resolution, saved queries, and personal memory controls.
3. The interactive HTML is a five-view concept subset rather than parity with the full Markdown wireframe set.
4. The mobile header needs a dedicated compact pattern because scope, global ask, and objective creation compete for width.
5. Cross-language Core consumption remains unresolved; language-neutral schemas and generated Java/TypeScript contracts are required, or UI/Extensions should depend only on Services protocols.
6. The feature map does not yet classify capabilities as existing, reusable with change, partial, or net-new.
7. A standalone adversarial-assessment artifact has not yet been added to the workspace.

### Conclusion

The product architecture and enterprise scope are coherent enough for review, but the UX package should not be called final until priority conflicts and missing high-risk flows are resolved.

---

## CP-015 — Product workspace consolidated under Documents

**Date:** 2026-07-20

### Durable workspace

The complete product-discovery workspace was copied to `~/Documents/executespec/brainstorm` while preserving the enterprise, shared, and other-solutions folder structure.

The conversation audit is included at `~/Documents/executespec/brainstorm/audit/executespec-product-discovery-audit.md` so the working documents and decision history remain together.

The existing source workspace under `~/Desktop/brainstorm` was preserved; no ExecuteSpec code repository was modified.

---

## CP-016 — Enterprise product completeness assessment

**Date:** 2026-07-20

### Verdict

The current workspace is sufficient to freeze broad enterprise feature discovery and proceed into scope closure. It is not yet sufficient to call the product build-ready, design-complete, or enterprise-sale-ready.

The main risk is no longer missing feature categories. It is that the platform narrative, P0 product reality, authority model, operational experience, and measurable launch proof are not yet closed.

### Critical remaining gaps

1. **P0 category truth:** the current trusted objective-to-verified-change wedge may still be perceived as a governed coding agent. Either narrow the initial category claim or include a thin non-code/intelligence slice that proves the broader software-execution platform.
2. **ICP, buyer, and paid wedge:** choose the initial company/team profile, economic buyer, first two or three repeatable workflows, buying trigger, and measurable ROI.
3. **Current-state parity:** classify every P0/P1 capability as existing, reusable with change, partial, or net-new, including dependencies and confidence.
4. **Authority and data contract:** close delegated versus service identity, source-of-truth precedence, cross-team visibility, write-back authority, retention/deletion, and prompt-injection/tool boundaries.
5. **Operational service blueprint:** specify installation, connector grants, initial indexing, quality validation, normal use, degraded states, recovery, support, audit, and offboarding/purge.
6. **UX completion:** add missing high-risk journeys for brainstorming, document update, source conflict, connector setup, failure/recovery, incident learning, saved queries, and memory controls; fix the mobile shell.
7. **Evaluation and release gates:** define deterministic P0 acceptance journeys, permission/red-team tests, Intelligence golden queries, evidence correctness, latency/cost bounds, and design-partner exit criteria.
8. **Enterprise commercial envelope:** define packaging, procurement boundary, SSO/SAML/SCIM expectations, audit export, data residency/deployment options, support/SLA, and security review artifacts.
9. **Standalone adversarial assessment:** document product, security, adoption, data-quality, operational, economic, and category risks with kill criteria and mitigations.

### Scope-control warning

Do not add more connector names, graph layers, personas, or product surfaces now. Do not execute the five-repository extraction until canonical contracts and the P0 boundary are stable. The immediate work is closure and proof.

### Minimum closure artifact set

1. Enterprise launch boundary and category promise
2. Current-state capability/gap matrix
3. Authority, identity, and data contract
4. End-to-end service blueprint and failure modes
5. Missing UX flows and mobile pattern
6. Design-partner validation and release-gate plan
7. Enterprise packaging/readiness checklist
8. Standalone adversarial assessment

---

## CP-017 — Integration testing identified as a first-class verification capability

**Date:** 2026-07-20

### Assessment

Integration testing is present only implicitly through generic build/test, contract-check, preview, and production-verification language. It is not yet defined deeply enough for an enterprise software execution product.

### Required product boundary

P0 must include an **Integration and System Verification** capability covering:

- Test and dependency topology across repositories, services, APIs, events, schemas, databases, and external systems.
- Impact-based selection of existing integration tests plus proposal or generation of missing tests.
- Consumer/provider contract and backward-compatibility checks.
- Environment orchestration for bounded ephemeral or approved shared test environments.
- Governed test data, fixtures, secrets, identities, and cleanup.
- API, event, workflow, database, and critical UI-path verification where applicable.
- Baseline-versus-regression and flaky-test handling so unrelated existing failures are not presented as agent-caused proof.
- Deterministic evidence with command, environment, version/SHA, configuration, timing, results, omissions, and logs.
- Pre-merge and preview verification in P0; progressive and post-deployment system verification may deepen in P1.

Connector certification for Jira, Confluence, GitHub, Figma, Slack, and later systems is a separate platform-quality concern and also requires sandbox, permission, webhook, rate-limit, retry, reconciliation, and contract tests.

### Priority conclusion

Integration testing is P0 because the trusted objective-to-verified-change promise is not credible when verification stops at isolated unit tests or a successful build.

---

## CP-018 — Integration and System Verification deep dive

**Date:** 2026-07-20

### Capability definition

Defined Integration and System Verification as one P0 trust domain with four connected product capabilities:

1. Discover and execute change-relevant integration/system tests.
2. Generate tests only for explicit verified gaps.
3. Validate that existing and generated tests provide meaningful proof.
4. Trigger bounded verification automatically through CI policy or on demand from authorised product entry points.

### Key decisions

- Customer CI/CD remains the execution and branch-policy authority; ExecuteSpec adds planning, orchestration, correlation, explanation, and evidence rather than becoming a hidden second CI system.
- Test proof must distinguish unit, component, contract, integration, workflow, migration, security, performance, resilience, and post-deployment evidence.
- Generated regression tests should fail on the baseline for the expected reason and pass on the candidate; new-behaviour and refactor/migration tests use different explicit proof models.
- Test-suite health is evaluated independently from application success, including assertion quality, dependency realism, determinism, isolation, flake evidence, ownership, runtime, and sensitive-data hygiene.
- CI triggers are idempotent, SHA/environment-bound, supersede stale commits, cap retries/cost, reconcile callbacks, and cannot silently commit generated tests without organisation policy.
- On-demand natural-language requests resolve into typed action cards containing scope, commit, tests, environment, identity, data, cost, side effects, approval, and expected evidence.
- Verification remains inside Objective, Evidence, Service, Admin, CI/PR, CLI/IDE, and integration entry points; it does not introduce another top-level surface.

### P0 boundary

P0 remains deliberately narrow: test inventory/topology, impacted existing-test selection, one environment strategy, one CI provider plus provider-neutral API/CLI, regression-test generation with baseline/candidate proof, basic suite validation/failure classification, and durable evidence with explicit unmeasured boundaries.

### Artifact

Created `enterprise/INTEGRATION-TESTING-AND-VERIFICATION.md` in the consolidated product workspace.

---

## CP-019 — Enterprise brainstorm closure decision register

**Date:** 2026-07-20

### Canonical artifact location

All future product-discovery artifacts will be saved under `/Users/navneetprabhakar/Documents/executespec/brainstorm`.

### Consolidated owner decisions

Reduced the scattered open questions to fourteen owner decisions required to close enterprise brainstorming:

1. Honest launch promise/category
2. Initial customer unit, buyer and champion
3. Three-workflow paid P0 wedge and objective types
4. Primary measurable outcome and design-partner pass condition
5. Exact P0 native integration promise
6. Slack and Figma depth
7. P0/P1 Engineering Intelligence and graph boundary
8. P0 Integration and System Verification contract
9. Identity and action-authority defaults
10. Initial data, deployment and enterprise-security boundary
11. Thin non-code capability proving the broader category
12. Operations and incident boundary
13. Commercial packaging principle
14. Five-repository extraction timing and Core contract form

Questions that can be resolved through design partners, technical spikes, or implementation planning were explicitly separated so they do not keep feature brainstorming open.

### Closure rule

Once the fourteen owner decisions have explicit answers, freeze new P0/P1 feature categories, reconcile the canonical documents, and proceed to current-state parity, missing UX, adversarial assessment, architecture, and delivery planning.

### Artifact

Created `enterprise/ENTERPRISE-BRAINSTORM-CLOSURE-QUESTIONS.md`.

---

## CP-020 — Enterprise brainstorm closed on C01–C14

**Date:** 2026-07-20

### Owner decision

Navneet accepted every recommended default in C01–C14 with two amendments:

1. **C05:** include Bitbucket Cloud in the current P0 native SCM plan alongside GitHub.
2. **C07:** ensure a startup or greenfield project with zero existing information can start and accumulate useful context without fabricated history.

### Zero-information contract

- Start from declared intent, people/team, constraints, outcome, risk, objective, project, and decisions.
- Requirements, architecture, services, repositories, standards, tests, milestones, documents, and relationships begin as generated proposals.
- Generated proposals require approval before becoming declared project context or executable objectives.
- Facts and relationships distinguish authoritative, observed, declared, generated proposal, derived, inferred, and unknown states.
- Ownership, expertise, dependencies, runtime state, history, and customer requirements remain unknown until evidence exists.
- Later GitHub, Bitbucket, Jira, Confluence, Figma, Slack, and CI connections enrich rather than replace the approved greenfield history.

### Integration boundary amendment

Bitbucket Cloud P0 covers repository, branch, commit, pull request, review, webhook, identity mapping, and evidence write-back. GitHub Actions/Checks remains the first native CI/check presentation. Bitbucket Pipelines may use the provider-neutral CI API/CLI in P0 but is not described as a full native CI adapter until productized.

### Documentation reconciliation

Created `ENTERPRISE-BRAINSTORM-DECISIONS-CLOSED.md` and updated the closure register, PRD, product document, feature map, integrations, Software Work Graph, Engineering Intelligence, roadmap, user journeys, decision register, and enterprise README.

### Status

Broad enterprise feature brainstorming is frozen. Next work is current-state parity, missing UX/service-blueprint detail, adversarial assessment, canonical contracts, design-partner validation, and delivery planning.

---

## CP-021 — Agent-ready enterprise PRD and delivery guide

**Date:** 2026-07-20

### Artifacts created

1. `enterprise/ENTERPRISE-IMPLEMENTATION-PRD.md`
2. `enterprise/ENTERPRISE-CODING-AGENT-DELIVERY-GUIDE.md`

### Implementation PRD

The implementation PRD converts C01–C14 into stable requirement IDs and acceptance boundaries covering:

- Launch promise, customer, P0 wedge, goals, non-goals, principles, and invariants
- Canonical objects, epistemic states, and objective/action/verification lifecycles
- Greenfield and connected user journeys
- Onboarding, objectives, context, decisions/plans, actions, verification, evidence, Intelligence, graph, documentation, collaboration, flow, operations, integrations, memory, governance, and Context Operations
- GitHub and Bitbucket P0 SCM parity
- GitHub Actions/Checks plus provider-neutral CI API/CLI
- Integration testing, generated-test proof, suite validation, CI/on-demand triggers, and evidence
- Security, reliability, accessibility, performance, data lifecycle, analytics, release gates, P1/P2 boundaries, and P0 acceptance definition

### Coding-agent guide

The delivery guide defines:

- Source-of-truth order and stop conditions
- Mandatory current-state parity, contract, vertical-slice, and repository-extraction gates
- Core, Services, Extensions, UI, and Analytics ownership/dependency rules
- Canonical cross-cutting fields and contract sketches
- S0–S9 vertical-slice sequence with required tests
- API, webhook, external-write, connector, migration, UI, observability, and security invariants
- Coding-agent work-package template, definition of done, review checklist, prohibited shortcuts, and handoff evidence

### Critical sequencing rule

The first coding-agent assignment is the current-state enterprise parity audit. Major subsystem rewrites or physical five-repository extraction must not begin before existing capabilities, contracts, consumers, migrations, and gaps are evidenced.

---

## CP-022 — Enterprise discovery final; persona #3 agent-ready definition

**Date:** 2026-07-20

### Enterprise status

Enterprise product discovery is treated as final. Remaining work—current-state parity, missing high-risk UX/service blueprint, adversarial validation, contract freeze, design-partner validation, and repository extraction—is delivery work already governed by the enterprise PRD and coding-agent guide. It does not keep feature brainstorming open.

### Persona #3 product definition

Created the ExecuteSpec Studio casual vibe-coder package:

1. `other-solutions/casual-vibe-coder/CASUAL-VIBE-CODER-IMPLEMENTATION-PRD.md`
2. `other-solutions/casual-vibe-coder/CASUAL-VIBE-CODER-CODING-AGENT-DELIVERY-GUIDE.md`

### Product boundary

- One Studio surface will serve personas #2 and #3 through progressive disclosure.
- Persona #3 defaults to prompt-first, visual, managed, guided creation.
- Studio reuses shared execution, verification, evidence, preview/deployment, identity, billing, safety, versioning, connector, and analytics contracts.
- Studio does not expose Enterprise Console complexity or create a separate casual backend/runtime.
- Expected vibe-builder parity includes prompt-to-app, templates, visual editing, managed backend, API/secrets, preview, test generation/execution, versions, recovery, publishing, sharing/remix, GitHub/Bitbucket export, limits/cost, and error recovery.

### Differentiated persona #3 capabilities

- Verification of real behavior rather than visual/demo completion
- Automatic checkpoints, compare, restore, safe experiment, and rollback
- Managed data/auth/storage/server-function patterns with honest mock/real disclosure
- Plain-language build/browser/data/auth/responsive/accessibility/publish evidence
- Source ownership and graduation to advanced/code/CLI/IDE workflows
- Gamification through verified milestones, skill paths, optional quests, and accessible celebration
- No progress based on prompt count, tokens, generated lines, daily compulsion, or spend

### Implementation sequence

The first persona #3 coding assignment is a `STU-*` current-state parity audit. Implementation then proceeds through idea-to-preview, conversation/visual iteration, managed backend, verification/recovery, publish/rollback, sharing/export, verified gamification, and closed-beta hardening vertical slices.

### Portfolio sequencing

Persona #2 remains next and will be defined by referencing shared Enterprise contracts and the Studio/persona #3 project experience, adding professional ownership and client-delivery depth without creating another product surface.

---

## CP-023 — Persona #2 agent-ready definition; three-persona portfolio closed

**Date:** 2026-07-20

### Persona #2 product definition

Created the ExecuteSpec Studio professional-individual package:

1. `other-solutions/professional-individual/PROFESSIONAL-INDIVIDUAL-IMPLEMENTATION-PRD.md`
2. `other-solutions/professional-individual/PROFESSIONAL-INDIVIDUAL-CODING-AGENT-DELIVERY-GUIDE.md`

### Product boundary

- Persona #2 is Professional Mode in the same Studio product and URL/shell as persona #3; it is not a third product surface.
- It serves freelancers, consultants, independent developers, solo founders, and small agencies delivering production software for themselves or clients.
- It reuses persona #3 creation, canvas, preview, version, recovery, publish, ownership, and progressive-disclosure contracts.
- It reuses Enterprise authority, context, SCM/CI, integration testing, verification, evidence, documentation, and delivery contracts without exposing Enterprise Console complexity.
- It excludes full CRM, invoicing, accounting, legal-contract authoring, workforce marketplaces, and enterprise portfolio administration.

### P0 professional capabilities

- Existing GitHub, Bitbucket, and approved local repository onboarding
- Client workspaces with hard cross-client isolation and explicit context boundaries
- Repository understanding and source-cited professional Q&A
- Requirement clarification, scope baseline, estimates with assumptions, and change requests
- Cloud and local-hands execution with preview, diff, approval, rollback, and authority controls
- Generated and existing test discovery, integration-test execution, suite validation, CI/on-demand triggers, and evidence
- Branch, commit, pull request, review, check, deployment, and rollback workflows
- Client review links, acceptance capture, evidence-backed handoff bundles, documentation, secrets/ownership transfer, and offboarding
- Per-client/project cost attribution, BYOK/provider controls, spend limits, and plain-language budget visibility
- Graduation from casual Studio to Professional Mode and onward to team/Enterprise without rebuilding the project

### Implementation sequence

The first persona #2 coding assignment is a current-state parity and shared-object audit. Delivery then proceeds through Professional Mode/client workspaces, repository and local onboarding, connected context, requirements/estimation/scope control, controlled execution, verification/PR, client review, deploy/handoff, and memory/cost/offboarding vertical slices.

### Portfolio closure

- Persona #1 Enterprise: defined.
- Persona #3 Casual vibe coder: defined.
- Persona #2 Professional individual: defined.
- Product shells remain limited to Enterprise Console and Studio.
- Future implementation must reuse shared platform contracts and begin with evidenced parity; it must not introduce another product shell or duplicate execution, context, verification, evidence, deployment, or analytics engines.

---

## CP-024 — Three-persona current-state parity and implementation sequence

**Date:** 2026-07-20

### Repository baseline

- Audited repository: `/Users/navneetprabhakar/Documents/antigravity/executespec`
- Branch: `codex/brownfield-rca-remediation-20260717`
- Commit: `bf0ab60c188e8d328a6a93fe6538f5a9d4e9827e`
- Worktree was clean.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q` passed.

### Artifacts created

1. `shared/CURRENT-STATE-PARITY-AND-SHARED-OBJECT-AUDIT.md`
2. `shared/THREE-PERSONA-IMPLEMENTATION-SEQUENCE.md`

### Quantified parity

All 282 requirements across Enterprise, Studio casual, and Studio Professional were classified exactly once:

| Persona | Existing | Partial | Missing | Deferred | Total |
|---|---:|---:|---:|---:|---:|
| Enterprise | 18 | 57 | 15 | 19 | 109 |
| Studio casual | 13 | 34 | 26 | 8 | 81 |
| Studio Professional | 34 | 41 | 13 | 4 | 92 |
| Total | 65 | 132 | 54 | 31 | 282 |

### Critical assessment

- The current product has a strong execution, agent-session, SCM, verification, evidence, preview/deployment, tenancy, billing, context and memory foundation.
- The product remains run-centric; it has no canonical Objective joining intent, sources, decisions, plans, actions, runs, verification, releases and learning.
- Project is overloaded and cannot safely represent enterprise workspaces, personal Studio and professional client isolation.
- Integration presence is not connector certification. Jira, Confluence, Slack, Bitbucket, Figma, PagerDuty and ServiceNow require live authority, permission, health, reconciliation, data-lifecycle and UX proof.
- Verification is strong in fragments but not yet one governed topology, impact, plan, environment, execution, classification, evidence and CI lifecycle.
- Studio visual creation, managed backend, version/recovery and gamification are substantial net-new product work.
- Professional Mode is closest to current capability, but client boundary, scope/change control, review acceptance and handoff are missing.

### Shared object decision

The contract spine must be implemented in the current monorepo before physical repository extraction:

- Workspace and Project boundary
- Objective
- SourceObjectRef and ConnectorInstallation
- ContextBundle
- DecisionRecord and Plan binding
- ActionProposal and authority decision
- VerificationPlan and EvidenceLedger
- VersionCheckpoint and Release
- ScopeBaseline, ChangeRequest and HandoffBundle
- WorkGraphProjection, scoped MemoryItem and CostLedger causality

### Sequence decision

Implementation order is contract foundations → shared intent-to-verified-PR slice → Enterprise connected Objective and Professional client-delivery slices → governed integration testing/CI → Intelligence/Work Graph and operations read-first → Studio casual creation/canvas/backend/version/publish → evidence-backed gamification → connector certification and five-repository extraction gates.

### Immediate next assignment

Create the F0 contract harness and F1 Workspace design package without adding tables or moving files. It must include current ownership, Workspace schema/invariants, deterministic Project backfill, API compatibility, cross-client threat model, migration/rollback/flag plan, pre-implementation contract tests and the Tenant-versus-personal-Org decision.

---

## CP-025 — Studio single-user UX and four-lens wireframe

**Date:** 2026-07-20

### Artifact created

`other-solutions/STUDIO-SINGLE-USER-UX-WIREFRAMES.md`

### UX decision

Studio does not inherit the Enterprise Console IA. It is organised around one person, one active Project and one current Version using four lenses:

1. Build
2. Data & storage
3. Checks
4. Ship

Studio keeps only Home, Project workspace and Account as durable surfaces. Versions are a project overlay/timeline and settings are secondary. Runs, agents, models, tests, deployments, storage, auth, functions, secrets, integrations and evidence do not become separate default Studio applications.

### Resource decision

Data & storage presents user capabilities—App data, Sign-in, File storage, Server functions, Background jobs and Secrets—while provider/infrastructure detail remains progressively disclosed. Preview and Production data are separated by default.

### Deployment decision

Ship promotes an immutable Release package containing source/version, web/API/worker artifacts, data migrations, configuration/secret references, verification evidence, destination compatibility, health checks and rollback target. Users do not deploy these pieces independently.

P0 environments are Workspace, Preview and Production. Professional Mode may add Staging when external repository/CI/deployment workflows require it.

### Persona decision

Casual and Professional Mode use the same four lenses. Professional Mode adds repository/branch/diff, local/IDE, PR/CI, client/scope/change, advanced resource/deployment controls, evidence export, handoff and cost depth inside the existing lenses. It does not create a fifth lens or freelancer shell.

### Prototype tasks

The next Studio prototype should test idea to preview, add sign-in/storage, fix a failed Check, deploy one coherent Release package, roll back without losing workspace changes, and switch the same Project into Professional Mode for PR creation.

---

## CP-026 — Exhaustive Studio feature catalog before pricing

**Date:** 2026-07-20

### Artifact created

`other-solutions/STUDIO-EXHAUSTIVE-FEATURE-CATALOG.md`

### Catalog design

The feature inventory was normalised into atomic capabilities suitable for later packaging and pricing. Pricing tiers, quotas and gates are intentionally absent.

Each feature carries:

- a stable catalog ID;
- a feature statement;
- casual-builder depth;
- freelancer depth;
- origin classification.

Origin classifications are:

- `A` — already represented in an approved Studio/Professional PRD;
- `R` — implementation or UX refinement of an approved capability;
- `N` — genuinely new recommendation requiring product approval.

### Counts

| Scope | Count |
|---|---:|
| Shared Studio atomic capabilities | 187 |
| Casual-specific experience | 25 |
| Freelancer-specific experience | 78 |
| Total catalog entries | 290 |
| Casual applicable capabilities | 207 |
| Freelancer applicable capabilities | 264 |

Origin distribution: 248 approved, 37 refinements and 5 new recommendations.

### New recommendations requiring approval

1. Managed record browsing/editing.
2. Scheduled jobs/cron.
3. Background queue/worker.
4. AI/LLM feature template for generated applications.
5. Vector/semantic-search template.

The coherent Release summary is classified as a refinement of immutable-release/deploy-review/evidence requirements rather than a new feature.

### Pricing boundary

The next exercise may gate scale, convenience, collaboration, premium infrastructure and advanced control. It must not make source/data export, retained-Version restore, deletion/offboarding, secret/access safety, failure/limit/charge visibility, platform-caused rollback recovery, result evidence or graceful read-only limit behavior into paid-only safety traps.

---

## CP-027 — Apple-logic Studio lineup: Free, PRO and MAX

**Date:** 2026-07-20

### Artifact created

`other-solutions/STUDIO-APPLE-LOGIC-TIERED-UX.md`

### Product-line decision

Studio is one product with one Project model, route tree, navigation and interaction grammar. Free, PRO and MAX are capability views, not separate applications, URLs or migrations.

The tier promises are:

1. **Free — Make something real.** Complete, safe personal creation, verification, Studio-hosted publishing, Versions/recovery and ownership.
2. **PRO — Make it yours.** $20/month for production identity, custom domain, richer managed resources, deeper checks, longer history and higher usage.
3. **MAX — Deliver professionally.** $100/month for client boundaries, repository/local/IDE workflows, GitHub/Bitbucket, PR/CI/staging, BYOK, scope/change control, evidence, cost attribution and handoff.

### UX decision

All tiers retain Home, Project and Account plus the Project lenses Build, Data, Checks and Ship. The preview remains the dominant Build surface. PRO reveals production ownership controls; MAX adds a thin professional context strip rather than surrounding the application with dashboards.

Gated capabilities appear only after explicit user intent. Upgrade flows preserve the current Project and resume the originating action after purchase. Permanent locked-navigation graveyards and generic upsell banners are rejected.

### Trust boundary

Source/data export, deletion/offboarding, secret/access safety, failure/limit/charge visibility, retained-Version restore, platform-caused rollback recovery, claimed-result evidence and graceful limit behavior remain baseline trust obligations. Plans may vary capacity or retention, not the existence of these protections.

### Deliberately open

The requested prices are fixed for this UX exercise. Exact AI, project, deployment, storage, bandwidth, execution, retention, domain, client-workspace and top-up limits remain undecided; no quota numbers were invented.

---

## CP-028 — Agent-focused Studio UX variations

**Date:** 2026-07-20

### Artifact created

`other-solutions/STUDIO-AGENT-FOCUSED-UX-VARIATIONS.md`

### Variations explored

1. **Conversation first** — persistent agent conversation plus live preview; strongest for onboarding, ambiguous intent and small changes, but risks becoming a chat wrapper and consuming canvas space.
2. **Mission control** — objective, stable phases, meaningful activity and guidance plus preview; strongest for autonomous multi-step work, but risks reproducing an execution dashboard for trivial edits.
3. **Adaptive co-builder** — preview-first surface with compact agent presence and contextual decision sheets; strongest resting experience, but needs a discoverable expanded state for conversation and background work.

### Tentative synthesis

Do not lock Studio to one permanent layout. Treat the three concepts as states of one agent shell:

- Explore/clarify through Conversation first.
- Execute substantial bounded work through Mission control.
- Shape the product and resolve authority through Adaptive co-builder.

The state sequence is `Explore intent → Execute bounded mission → Review outcome/decision → Return to product`.

### Agent abstraction decision

Users interact with one product-level Studio agent. Planner, worker, reviewer, model, provider and tool orchestration remain implementation detail. User-visible agent state is limited to Ready, Understanding, Working, Waiting for you and Finished/needs attention.

### Trust and information boundary

The UI may expose interpreted objective, scope, meaningful progress, authority, outcome, verification, reversibility and recovery. It does not expose chain-of-thought, raw tool calls, provider retry chatter, internal agent personas, every generated task or unfiltered logs by default.

### Status

Variation C is the tentative resting-state recommendation. The adaptive three-state synthesis requires prototype testing before it is locked.

---

## CP-029 — Codex-style agent task shell for Studio

**Date:** 2026-07-20

### Artifact created

`other-solutions/STUDIO-CODEX-STYLE-AGENT-UX.md`

### Shell decision

The next Studio prototype adopts Codex desktop interaction logic without copying its visuals or coding-only language:

1. Persistent Project/Task history on the left.
2. One current agent Task/conversation in the center.
3. The Task’s inspectable Preview, Changes, Checks and contextual Data/Release evidence on the right.

The user-facing hierarchy is Project → Task → Output. The backend Run remains an execution/evidence object rather than primary Studio navigation.

### State decision

The prototype includes Working, Needs input and Complete states, with Needs attention and Paused defined for implementation. State is server-derived, durable and reloadable. A user can leave and return to a Task without relying on an active browser stream.

### Product-domain decision

Build, Data, Checks and Ship remain capability domains but do not require a permanent Studio rail in this shell:

- Build maps to Preview and Changes.
- Data appears when affected.
- Checks is a standard output/evidence pane.
- Ship appears as Release output and consequential decision cards.

### UX boundary

The shell is shared by Free, PRO and MAX. Plan entitlements change capabilities and capacity, not navigation or Task identity. Upgrade flows originate from the requested Task action and resume the same Task after purchase.

### Critical constraints

Three columns are allowed only on genuinely wide screens. The output split is collapsible/resizable, the sidebar collapses early and mobile renders one active Task/Output surface at a time. Progress uses durable semantic events, not fake percentages or internal task counts.

### Status

This is now the proposed next high-fidelity Studio prototype. The Working, Needs input and Complete journeys must be usability-tested before the shell is locked.

---

## CP-030 — Exhaustive Studio feature placement and Settings IA

**Date:** 2026-07-20

### Artifact created

`other-solutions/STUDIO-FEATURE-PLACEMENT-AND-SETTINGS-IA.md`

### Coverage decision

Every one of the 290 approved/catalogued Studio capabilities now has a discoverable home through one of three presentation types:

1. Task/Output work screen for frequent intent, active work, inspection, decisions and outcomes.
2. One Settings surface for stable defaults, connections, policies, lifecycle controls and plan/usage configuration.
3. Protected status for mandatory safety, evidence, recovery and isolation behavior that is visible but not disableable.

### Count reconciliation

The 20 settings domains sum exactly to 290 catalog entries:

`10 + 10 + 25 + 12 + 10 + 21 + 7 + 10 + 12 + 16 + 8 + 18 + 8 + 8 + 12 + 25 + 22 + 22 + 24 + 10 = 290`

The stable user-facing Output choices are Preview, Changes, Checks, Resources, Context, Release and Client delivery when applicable.

### Critical UX boundary

“All features on screen or under Settings” is interpreted as complete discoverability, not 290 toggles. Secret safety, deny-by-default access, verification integrity, non-destructive Version/Release lineage, exact production/destructive confirmation, cross-client isolation and graceful recovery remain mandatory platform behavior.

### Settings decision

Settings uses 20 canonical domains grouped for presentation under Account, Project, Resources, Source and quality, and Delivery and collaboration. Atomic catalog-ID mappings remain stable for entitlements, telemetry, documentation and automated coverage tests.

### Pending-product boundary

The five previously identified new recommendations have planned homes but are not approved product promises: managed record editing, scheduled jobs, background queue/worker, AI/LLM app template and vector/semantic-search template.

### Implementation requirement

Every catalog ID must carry primary domain, screen/Settings/protected-status home, presentation type, entitlement, permission, telemetry, documentation and test metadata. Coverage tests must fail when any ID is unplaced or any required protection is marked user-disableable.

---

## CP-031 — Group Studio Settings into six user jobs

**Date:** 2026-07-20

### Artifact created

`other-solutions/STUDIO-GROUPED-SETTINGS-IA.md`

### Correction

The 20-domain capability-coverage taxonomy created in CP-030 is too granular for user-facing Settings navigation. It remains valid for product coverage, entitlements, telemetry, documentation and tests, but is now explicitly internal.

### User-facing Settings decision

Settings is reduced to six job-based groups:

1. Account
2. Project
3. Resources
4. Source & quality
5. Publish & sharing
6. Client delivery

The six groups contain 23 recognizable subsections. Subsections render in the main panel rather than creating another persistent nested sidebar.

### Product relationship

The mapping is now:

`290 atomic capabilities → 20 internal domains → 23 subsections → 6 user-facing groups`

### Entitlement behavior

Client delivery is the only coherent plan-specific group. It is present by default for MAX. Free/PRO encounter it after explicit client-workspace intent or plan comparison rather than as a permanent locked navigation item. Existing client data remains manageable during downgrade/offboarding.

### Safety placement

There is no separate top-level Safety dumping ground. Protected guarantees appear inside the setting where their effect is understandable—secret safety under integrations, verification integrity under Checks, production confirmation under Release, cross-client isolation under Client workspace, and account export/delete under Privacy.

### Validation requirement

Discovery testing must measure whether users choose the correct group first for login configuration, domains, Version restore, budgets, Bitbucket/PR policy, preview visibility, export, webhooks, check gates and client offboarding.

---

## CP-032 — Objective-centred Enterprise Console and explicit Studio separation

**Date:** 2026-07-20

### Artifact created

`enterprise/ENTERPRISE-CODEX-STYLE-UX-REVISION.md`

### Product separation decision

Studio and Enterprise are separate product views, not tier states of one shell:

- Studio: `studio.executespec.ai`, with `Project → Task → Output`.
- Enterprise Console: `console.executespec.ai`, with `Organisation/Team → Objective → Inspectable enterprise objects`.

They share identity/SSO, design-system primitives and explicit authorized deep links. They do not share navigation, default panes, draft state or inferred scope.

### Previous Enterprise baseline

The previous shell used six global destinations—Home, Objectives, Intelligence, Services, Evidence and Admin—plus eight Objective tabs and an optional contextual rail. It was comprehensive and trust-aware but placed several navigation levels between the user and the next decision.

### Current Enterprise decision

The current shell uses:

1. Org/team scope, Needs you and active Objectives on the left.
2. One current Objective thread in the center.
3. Context, Plan, Changes, Evidence, Delivery, Systems and Activity as an Inspect selector on the right.
4. Intelligence, Services and grouped Settings as explicit enterprise tools rather than permanent Objective tabs.

### Capability preservation

No approved Enterprise capability is removed. Previous destinations map into Objective Inspect, Intelligence, Services or six grouped Enterprise Settings domains: Organisation & people; Knowledge & context; Integrations; Governance & AI; Environments & operations; Billing & audit.

### Non-conversation boundary

Not all enterprise work is forced into an Objective thread. Intelligence supports organization-wide query/browse without creating an Objective. Services remains the service/system portfolio. Either may create or scope an Objective when action is required.

### Existing-document status

`UX-STRATEGY-AND-IA.md` retains the approved experience thesis, object model, state/evidence language, progressive disclosure, collaboration, responsive and trust requirements. `WIREFRAMES.md` remains the previous feature/state baseline. Both now point to the current revision for shell/IA.

### Validation requirement

The previous and current versions must be compared using Jira-ready work, cross-source conflict resolution, governed plan approval, leave-and-return continuity, Intelligence without Objective creation, Service remediation, delivery/evidence, incidents, administration, cross-view notifications and mobile approval.

---

## CP-033 — UX wireframe closure audit

**Date:** 2026-07-20

### Artifact created

`shared/UX-WIREFRAME-CLOSURE-AUDIT.md`

### Verdict

The product-level UX direction is closed; the complete current-style wireframe pack is not yet closed.

Locked decisions:

- separate Studio and Enterprise views;
- Studio `Project → Task → Output` shell;
- Enterprise `Organisation/Team → Objective → Inspect` shell;
- shared agent state/decision grammar;
- Studio Free/PRO/MAX experience logic;
- complete 290-capability placement and six-group Studio Settings;
- Enterprise previous-to-current capability mapping;
- explicit URL/session/deep-link boundaries.

### Remaining bounded work

Studio still needs current-shell wireframes for zero-information start, needs-attention/limit states, Resources, Context, Release, MAX client delivery, representative Settings details, upgrade/downgrade and mobile.

Enterprise still needs current-shell wireframes for Home/Jira-ready work, plan approval, working/evidence/delivery, Intelligence, Services/Systems, incident remediation, grouped Settings/Context Operations, cross-channel continuity and mobile approval. The corresponding product behavior exists in prior wireframes or journeys but has not been redrawn in the current shell.

Cross-view switching, notifications/deep links and shared-object transitions also require explicit wireframes.

### Final pack decision

One consolidation pass will produce S01–S10, E01–E10 and X01–X03. No further shell variants or navigation models should be explored during that pass.

### Freeze gate

UX wireframing closes only when every approved journey maps to a current-shell screen sequence, relevant failure/partial/denied/stale states are represented, responsive behavior is unambiguous and each consequential action identifies scope, authority, evidence and recovery.

---

## CP-034 — Enterprise Figma visual direction V1

**Date:** 2026-07-20

### Artifacts created

- `enterprise/ENTERPRISE-FIGMA-VISUAL-DIRECTION-V1.md`
- `enterprise/figma/executespec-enterprise-objective-v1.png`
- `enterprise/figma/dsb-state-executespec-enterprise-v1.json`
- Figma file: `https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S`

### Scope decision

The first production-style visual is the current Objective-centred Enterprise screen, not the previous destination-heavy shell and not a Studio tier view. It tests organisation/work continuity, an agent-led decision flow and governed enterprise context in one shell.

### Palette decision

The design uses the active protected Console palette from `frontend/src/console/console.css`: black-green canvas, dark teal panels, bright green primary action, teal information states, muted pistachio text, yellow warnings and JetBrains Mono typography.

### Layout decision

The 1440 × 960 screen uses three explicit responsibilities:

1. left: workspace scope, Needs you, active Objectives and compact enterprise tools;
2. center: the current Objective, grounded response, one decision and continuation composer;
3. right: governed sources, conditional Slack, Work Graph, source conflict and universal context query.

The center deliberately carries more negative space than the previous wireframe. Context density is moved to the inspector instead of competing with the agent/user flow.

### Reusable design-system output

The Figma file contains Cover, Foundations, Components and Enterprise Screens pages; 71 local variables; Dark and Light semantic modes; seven text styles; two effects; and Button, Objective Row and Source Row component sets with variants and editable text properties.

### Validation result

The completed screen uses nine component instances and 86 variable-bound nodes. Structural QA found zero missing fonts, empty text layers, invalid dimensions or descendants outside the screen frame. Screenshot review found no clipping or overlap.

### Tool boundary

Figma was sufficient for editable application UI. Adobe Express and Canva were not used because V1 contains no marketing, campaign or presentation asset that would justify them.

### Remaining boundary

This checkpoint establishes visual direction only. It does not close the frozen S01–S10, E01–E10 and X01–X03 surface pack. Subsequent work must propagate this direction without reopening shell or navigation exploration.

---

## CP-035 — Collapsible Enterprise shell and complete-design UX safety gate

**Date:** 2026-07-20

### Artifact created

`enterprise/ENTERPRISE-COMPLETE-DESIGN-UX-SAFETY-GATE.md`

### Figma states added

- Expanded Objective screen updated with visible left/right collapse controls.
- Left collapsed: `14:23` — 56 / 888 / 496px.
- Right collapsed: `15:44` — 264 / 1120 / 56px.
- Both collapsed/focus: `15:176` — 56 / 1328 / 56px.

### Collapse decision

Left and right panes collapse independently. Collapse is presentation state only: it cannot change Objective scope, execution, current Inspect object, draft content, filters or unread state.

The left rail preserves Enterprise identity, Needs-you urgency, active-work status and access to Intelligence, Services and Settings. The right rail preserves all seven Inspect destinations and alert markers. In focus mode the center expands but readable content remains width-capped.

### Feature-preservation audit

The complete-design gate maps all 17 Enterprise requirement domains and all 109 approved requirement IDs into six homes: Objective thread, Inspect, Intelligence, Services, Enterprise Settings and external entry points. It also maps current execution, agent session, plan gate, SCM, local-hands, intelligence, memory, verification, evidence, deployment, governance, billing, sharing, connector and analytics primitives into the target experience.

### Truth decision

Design handoff must annotate Current, Current primitive, Planned P0, Planned P1 and Deferred. Partial or missing product behavior cannot be drawn as already production-ready simply because Figma can represent it.

### State decision

The E01–E10/X01–X03 pack must collectively draw zero-information, partial, denied, unavailable, no-result, stale, conflicting, decision, working, paused, blocked, failure, success, limit, supersession, approval invalidation, write-back/reconciliation and deletion/purge states where relevant.

### Verification protection

Integration and System Verification remains one lifecycle distributed through Plan, Working, Evidence/Delivery, Services, Settings and PR/CI. A generic green test badge cannot replace topology, impact, environment, generated-test validity, failure classification, omissions and provenance.

### Gate decision

The full Figma pack may proceed only through the safety manifest. It is rejected if a domain loses its home, a current capability becomes unreachable, a partial/missing feature is implied to exist, pane collapse hides urgent state, or a consequential action lacks scope, authority, evidence, effect and recovery.

---

## CP-036 — Semantic collapsed rails and explicit third-party/model identity

**Date:** 2026-07-20

### Figma correction

Collapsed Enterprise side panes no longer use bare counts, letters, initials or typographic glyphs as navigation. The left rail now uses semantic icons for Needs you, active Objectives, Intelligence, Services and Settings. The right Inspect rail uses semantic icons for Context, Plan, Changes, Evidence, Delivery, Systems and Activity. Warning and success color remain supplementary state signals; the icon and accessible label carry destination meaning.

The three verified pane states remain:

- left collapsed `14:23` — 56 / 888 / 496px;
- right collapsed `15:44` — 264 / 1120 / 56px;
- both collapsed/focus `15:176` — 56 / 1328 / 56px.

Structural QA across all four states found zero missing fonts, empty text layers or descendants outside their screen frames, and zero collapsed placeholder initials.

### Third-party identity rule

Every third-party integration must show both its official provider logo and canonical product name wherever it is selected, configured, inspected or referenced. Connection health, freshness, authorization and conditional-access state are separate signals and cannot substitute for brand identity. Generic plug, SCM, chat or AI icons cannot stand in for a named provider.

The current governed-source rows were corrected to show official Bitbucket, Jira and Slack marks beside their names in both expanded-right screen states.

### Model identity rule

Every model must be accompanied by its provider logo and full model label across pickers, policy settings, execution details, evidence, usage/cost records and audit history. Provider and model remain separate fields. A generic sparkle/AI icon, provider logo without the model label, or model label without provider identity is insufficient. Customer-hosted/unbranded endpoints require a governed neutral/customer mark plus explicit provider text rather than invented branding.

### Design-system implication

ExecuteSpec-owned navigation continues to use the semantic Lucide icon set. Third-party and model-provider identity uses maintained provider-approved brand assets with accessible names, preserved proportions/clear space and validated dark/light variants.

---

## CP-037 — Enterprise core-surface click-through prototype V1.3

**Date:** 2026-07-20

### Artifacts

- `enterprise/ENTERPRISE-FIGMA-CLICKTHROUGH-V1-3.md`
- updated `enterprise/ENTERPRISE-FIGMA-VISUAL-DIRECTION-V1.md`
- updated `enterprise/ENTERPRISE-COMPLETE-DESIGN-UX-SAFETY-GATE.md`
- updated `enterprise/figma/dsb-state-executespec-enterprise-v1.json`
- V1.3 screenshots under `enterprise/figma/`

### Surface expansion

The Enterprise Figma file now contains an interactive baseline for E01 Home/Needs you, E02 Objective Context, E03 Plan Approval, E04 Working Objective, E05 Evidence & Delivery, E06 Intelligence, E07 Services & Systems, E08 Incident, E09 Enterprise Settings and E09B AI Model Policy. The existing left-collapsed, right-collapsed and both-collapsed frames are classified as partial E10 desktop coverage.

### Click-through contract

The prototype start is E01 (`27:126`). The primary flow is E01 → E02 → E03 → E04 → E05 → E01. Expanded screens also link to Intelligence, Services and Settings; Services and Incident are bidirectionally linked; Settings links to Model Policy. Forty-nine direct-frame hotspots were created and validated.

### Spacing correction

Collapsed side-rail item spacing increased from 12px to 18px. The left flexible spacer decreased from 694px to 652px, preserving exact 960px containment and improving icon separation without widening either rail.

### Identity coverage

Integration rows retain official Bitbucket, Jira and Slack marks plus names. The model-policy state visibly enforces provider logo plus full model label for OpenAI/GPT-5.4, Anthropic/Claude Sonnet 4.6 and Google/Gemini 3.5 Flash. Provider/model identity and operational policy remain separate fields.

### QA

Across 13 frames, structural and rendered QA found zero missing fonts, empty text layers, rendered-text overflow or out-of-frame descendants. All 49 hotspots have valid reactions to top-level frames. Visual review covered the lifecycle, Intelligence, Services, Incident, Settings, Model Policy and collapse states; clipped primary labels found during review were shortened.

### Honest boundary

This does not close Enterprise UX. E10 tablet/mobile, X01–X03 cross-view/external continuity, the required denied/stale/unavailable/failure/partial/recovery state matrix, accessibility annotations and prototype usability testing remain open. Those are recorded as pending rather than implied by the broad happy-path screen set.

---

## CP-038 — Enterprise complete playable surface pack V1.4

**Date:** 2026-07-20

### Artifacts

- `enterprise/ENTERPRISE-FIGMA-COMPLETE-SURFACE-PACK-V1-4.md`
- updated `enterprise/README.md`
- updated `enterprise/figma/dsb-state-executespec-enterprise-v1.json`
- V1.4 screenshots under `enterprise/figma/`
- Figma file: `https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S`

### Bounded completeness decision

The frozen E01–E10 and X01–X03 manifest is the boundary for “all surfaces.” A separate frame is required when scope, authority, evidence, effect, recovery or responsive priority changes. Provider records, status strings and data permutations reuse those surfaces instead of generating a canvas of cosmetic clones.

### Playable directory

`P00 · Enterprise Prototype Directory` (`39:103`) is now the default Figma presentation start. It exposes 45 real destinations grouped into canonical lifecycle, responsive and pane states, exception and recovery states, and cross-view/external continuity. E01 remains a secondary presentation start for the core flow.

### Responsive coverage

E10 now includes compact desktop, tablet Inspect drawer, mobile Objective, mobile approval, mobile Evidence and a justified desktop-required comparison handoff. Mobile is a single-surface experience with a stable bottom action; approvals remain mobile-capable unless simultaneous evidence comparison is necessary for safety.

### Exception and recovery coverage

Twenty state-family screens cover zero information, source unavailable, partial/stale/denied/no-result context, rejected/expired/invalidated approval, approved bounded authority, paused/blocked work, partial failure/recovery/supersession, failed/inconclusive/unmeasured proof, partial write-back/rollback, denied or conflicting intelligence, unknown systems, active incident, blocked production action, verified remediation with reconciliation failure, first connection, connector drift, disconnect/export/purge and degraded model route/usage limit.

### Cross-view and external continuity

X01 covers explicit Studio/Enterprise entry and isolated view switching. X02 covers authorised Jira entry, wrong-view/expired/denied recovery and write-back reconciliation. X03 carries one explicit provider/repository/commit/evidence reference into Studio read-only without inferring tenant, project, team, chat, memory or neighbouring repository scope. Other external providers reuse the same exact-object contract rather than becoming new navigation surfaces.

### Shell correction

The expansion exposed a pre-existing containment defect: primary and Ask controls could extend underneath the adjacent pane. All expanded desktop composer and query rows now use contained widths and explicit gaps. Compact desktop also replaced inherited 136px wide-screen gutters with 6px gutters so its 616px content fits the 628px Objective column.

### QA

The Enterprise Screens page now has 46 top-level frames and 2,230 text layers. Final structural QA found zero missing fonts, empty text layers, genuine text overflow, genuine child containment failures, invalid prototype destinations or self-navigation. The prototype has 224 valid reactions; 45 originate from the playable directory. Collapsed rails intentionally retain clipped expanded descendants while rendering semantic icon layers and are excluded from visible-overflow counts.

### Remaining boundary

Screen architecture is closed against the frozen manifest. Remaining work is moderated usability testing, keyboard/focus/screen-reader/reduced-motion annotation, implementation-status metadata, provider-asset brand/licensing review, and validation with real data density/localisation/accessibility zoom. These do not reopen the shell or navigation model.

---

## CP-039 — Enterprise implementation-closure baseline and 109-ID traceability

**Date:** 2026-07-20

### Artifacts

- `enterprise/closure-v2/00-CLOSURE-REGISTER.md`
- `enterprise/closure-v2/01-PRD-9.1-NAVIGATION-AMENDMENT.md`
- `enterprise/closure-v2/02-PRD-TRACEABILITY-MATRIX.md`
- `enterprise/closure-v2/03-UX-FOUNDATION-CONTRACTS.md`
- `enterprise/closure-v2/04-PERMISSION-AND-REDACTION-MATRIX.md`
- `enterprise/closure-v2/05-RESPONSIVE-RULES.md`
- `enterprise/closure-v2/06-SCREEN-STATE-MANIFEST.md`
- `enterprise/closure-v2/07-COMPONENT-AND-VARIANT-INVENTORY.md`
- `enterprise/closure-v2/08-ACTION-AUTHORITY-EFFECT-RECOVERY-CONTRACT.md`
- `enterprise/closure-v2/09-PROTOTYPE-TRANSITION-MAP.md`
- `enterprise/closure-v2/10-ANALYTICS-AND-ACCEPTANCE-TESTS.md`

### Contract closure

All 109 stable PRD requirement IDs are now represented exactly once in the traceability matrix. Each row records priority and maturity, primary and alternate entry points, state responsibility, breakpoint responsibility, implementation evidence, analytics and acceptance proof. Backend-only and non-visual requirements are explicitly classified instead of being silently omitted from the UX pack.

The approved Objective-centred shell is retained. PRD section 9.1 is amended by mapping Home, Objectives, Intelligence, Services, Evidence and Admin responsibilities into the current shell without creating six permanent competing destinations. Production rollback and remediation remain P2/Conditional; P0/P1 operations remain read-first. Objective-scoped Intelligence is P0, while organisation-wide depth, Slack actions and PagerDuty/ServiceNow depth are P1 or Conditional.

### Shared implementation contracts

The closure pack now defines independent state, permission, freshness, evidence, epistemic and authority fields; inherited-shell redaction; action severity; Objective versus Context query semantics; readable typography and targets; responsive responsibility across desktop, compact desktop, tablet and mobile; complete screen-state and transition manifests; connector/model identity rules; analytics; and the 14 adversarial closure scenarios.

### Current-product evidence boundary

Reusable implementation evidence exists for auth/RBAC, projects, runs and plan approval, bounded workspaces, test generation and execution, validation evidence, RAG/documents/memory/contracts, GitHub/Bitbucket SCM, preview/deployment, audit/export/sharing and model policy. Jira, Confluence, Slack, PagerDuty and ServiceNow remain legacy/partial or unverified integration depth. A first-class Figma connector and the canonical Enterprise Objective/Context/Decision/Verification domain remain planned. The Figma prototype has not yet been mutated in this closure pass; component foundations and P0 state repairs are the next gate.

---

## CP-040 — Figma closure Phase 0 discovery and scope lock

**Date:** 2026-07-20

### Artifacts

- `enterprise/closure-v2/11-FIGMA-PHASE-0-GAP-ANALYSIS.md`
- `enterprise/closure-v2/dsb-state-enterprise-closure-v2-2026-07-20.json`

### Baseline

The current Figma file has four pages, 71 locally scoped/code-linked variables, seven text styles, two effect styles and only three component sets. Its visual palette maps correctly to the live protected Console CSS, so no colour-value decision fork exists. The deficiency is structural: current Button, Objective Row and Source Row sets cannot express authority, severity, permission, freshness, evidence, maturity, recovery or responsive behavior.

### Reuse decision

Material 3, Simple Design System and Apple libraries are subscribed. Targeted search found no compatible semantic action/navigation/state assets. The ExecuteSpec component library will therefore be extended locally while retaining valid official vector identities already used in the file. The current codebase's provider letter tiles are explicitly not accepted as official-logo parity.

### Locked build order

Foundation roles and readable typography precede components. Atomic action, navigation, maturity and compound-state components precede permission/failure/evidence/Objective/query/shell components; domain rows and responsive shell compositions follow. Existing component sets remain until migrated instances are verified, preventing silent prototype breakage.

### Gate

Phase 0 is complete and read-only. No Figma mutation occurred. Phase 1 awaits explicit approval, as required by the design-system workflow.

---

## CP-041 — Figma mutation preflight and complete screen-repair backlog

**Date:** 2026-07-20

### Artifacts

- `enterprise/closure-v2/12-FIGMA-PHASE-1-MUTATION-SPEC.md`
- `enterprise/closure-v2/13-FIGMA-SCREEN-REPAIR-BACKLOG.md`
- updated `enterprise/closure-v2/dsb-state-enterprise-closure-v2-2026-07-20.json`

### Foundation preflight

The exact Phase 1 delta is now specified: extend the existing three collections from 71 to 105 variables, add five readable text styles and three focus styles, preserve existing component/screen/reaction counts, and validate every alias/scope/code-syntax target. Contrast calculations exposed a real accessibility defect: the current light-theme red cannot carry AA body text. The planned destructive surface uses a deeper light red with a 6.47:1 white-text ratio.

### Screen repair evidence

The live Figma file contains 46 top-level frames. All 46 node IDs are represented in the repair backlog. Five existing pane/responsive frames have zero reactions; each receives explicit reversible transition requirements. The four mobile frames have only one hotspot each and are treated as inputs, not proof of complete mobile journeys.

The backlog orders state/redaction/action blockers first, then shell/navigation/query migration, purpose-built Intelligence/Services/Settings, lifecycle and missing PRD workflows, interaction graph, responsive tablet/mobile responsibility frames and per-frame accessibility/adversarial validation.

### Gate

No Figma mutation has occurred. Phase 1 remains approval-gated; the preflight and repair plan remove implementation ambiguity while waiting.

---

## CP-042 — Official integration/model identity review and legal release gate

**Date:** 2026-07-20

### Artifact

- `enterprise/closure-v2/14-OFFICIAL-IDENTITY-ASSET-MANIFEST.md`

### Evidence

The current prototype contains provisional vector identities for Bitbucket, Jira, Slack, OpenAI, Anthropic and Google/Gemini. GitHub, Confluence, Figma, GitHub Actions, DeepSeek, OpenRouter and Fireworks AI are missing from the reusable identity layer. The model registry currently exposes 38 display-label rows across six serving providers; the UI contract therefore binds provider identity and full API display label independently rather than hard-coding a short model catalogue.

Official sources and current usage guidance were reviewed for GitHub, Atlassian, Figma, Slack, OpenAI, Google, Anthropic, DeepSeek and Fireworks AI. Existing logo-shaped vectors remain provisional until source, retrieval date, terms, light/dark treatment and clear-space rules are recorded.

### Release conflict

DeepSeek's current terms restrict logo use without permission, while the approved product requirement requires an official provider logo. No verified permissive brand-kit terms were found for OpenRouter or Fireworks AI. Named-provider production exposure is therefore blocked until approved assets/permission exist; a neutral substitute is not silently accepted because it would violate the explicit product requirement. Customer-hosted/unbranded endpoints retain a neutral identity by design.

---

## CP-043 — Provider-logo exception and Figma Phase 1 foundation closure

**Date:** 2026-07-20

### Artifacts

- updated `enterprise/closure-v2/07-COMPONENT-AND-VARIANT-INVENTORY.md`
- updated `enterprise/closure-v2/12-FIGMA-PHASE-1-MUTATION-SPEC.md`
- updated `enterprise/closure-v2/14-OFFICIAL-IDENTITY-ASSET-MANIFEST.md`
- `enterprise/closure-v2/15-FIGMA-PHASE-1-FOUNDATION-RESULT.md`
- updated `enterprise/closure-v2/dsb-state-enterprise-closure-v2-2026-07-20.json`

### User-authorised identity exception

Navneet approved preserving DeepSeek, OpenRouter and Fireworks AI as named and selectable providers while omitting their logos until permission is secured. Full provider names and exact model display labels remain mandatory. No fabricated or neutral substitute may represent these named commercial providers; the neutral mark remains reserved for genuinely customer-hosted/unbranded endpoints. This resolves CP-042's release conflict without removing provider capability.

### Figma foundation mutation

Phase 1 extended the existing Figma collections in place: 71 to 105 local variables, seven to 12 text styles, and two to five effect styles. The new foundations cover action severity, denied/stale/scrim surfaces, responsive hit areas and pane widths, readable JetBrains Mono typography, and normal/high-impact/destructive keyboard focus.

### Validation

All 36 Dark/Light semantic alias assignments resolve. Exact scopes and WEB syntax passed. The file retains three component sets, 47 Enterprise Screens top-level nodes and 224 prototype reactions, proving that the additive foundation mutation did not alter existing screens or playability. Phase 1 is closed; semantic component construction is the next gate.

---

## CP-044 — Figma Foundations documentation closure

**Date:** 2026-07-20

### Artifact

- `enterprise/closure-v2/16-FIGMA-PHASE-2-FOUNDATIONS-DOCUMENTATION-RESULT.md`

### Mutation

The prior Foundations page was a 1,099px summary containing six colour swatches, four type specimens and four size examples. A new 1440×5,573 variable-bound reference now documents product UX contracts, all 42 primitive and 39 semantic colours, all 12 text styles, all 24 Size variables, both elevation styles, all three focus styles and the identity/maturity contract.

### Validation

All 81 colour swatches, 17 dimension bars, six radius examples and five effect cards are bound to the live variables/styles. Font and containment checks returned zero failures. Original-resolution screenshot review passed. After validation, the obsolete frame `5:10` was removed and the new canonical frame `75:31` took its canvas position; recovery remains available through Figma version history.

### Decision boundary

The file retains one Foundations surface. DeepSeek, OpenRouter and Fireworks AI are explicitly documented as named/selectable provider routes with full model labels and no logo pending permission. Component construction is the next gate.

---

## CP-045 — Figma semantic icons and Action/Button component checkpoint

**Date:** 2026-07-20

### Artifact

- `enterprise/closure-v2/17-FIGMA-PHASE-3-COMPONENT-PROGRESS.md`

### Icon family

Seventeen ExecuteSpec UI glyph components now cover primary navigation, collapsed panes, search, drawers and shell controls. All are 24×24, vector-stroke bound to `color/icon/muted`, keyed for idempotency and visually validated. They are product glyphs, not provider logos.

### Action/Button

Ninety-three variants were split into five sets of 20, 20, 20, 30 and 3 so no set exceeds the 30-combination cap. Normal, Approval, High-impact, Destructive, Secondary, Ghost, Loading and Disabled are represented across 32/40/44px targets. Label, icon visibility and icon swap properties are wired.

Visual QA caught and repaired three defects before screen adoption: gray paint seed fallback after semantic alias rebinding, opaque state overlays that erased amber/red severity, and component TEXT properties that replaced fixed loading/disabled labels. The final medium set screenshot preserves all four severities and loading behavior; an isolated focused specimen includes the full ring.

### Pending gate

After the successful final repair, the Figma MCP intermittently returned `Unknown tool` for consolidated read-only `use_figma` validation. Creation/property outputs, component metadata and screenshots exist, but one fresh structural read remains explicitly pending in the state ledger. Phase 3 is not marked complete and no screen may consume the button family until that read passes.

---

## CP-046 — Action/Button structural gate closed

**Date:** 2026-07-20

### Validation

A fresh read-only validation completed successfully across `Action/Button/Core Small`, `Core Medium`, `Core Large`, `Supporting` and `Disabled`. All 93 variants are present with exact expected set counts. Every set exposes exactly one TEXT, BOOLEAN and INSTANCE_SWAP property. Every variant has a bound primary fill; no obsolete state layer is visible; every Focused variant has an effect; every Loading variant has a visible spinner and fixed `Working…` label; every Disabled variant owns `Unavailable`; and no text node reports a missing font.

### Decision

The prior pending gate is closed. Action/Button may now be consumed by downstream semantic components and enterprise screens. Phase 3 remains in progress because the rest of the semantic component library is still pending.

---

## CP-047 — Status semantics component family closed

**Date:** 2026-07-20

### Artifacts

- Semantic status icon documentation: Figma `92:190`
- `Status/Maturity`: Figma component set `93:566`
- `Status/Compound`: Figma component set `94:674`

### Decisions

Maturity uses six fixed labels and fixed semantic icons, exposing only its VARIANT axis. This prevents accidental relabelling of implementation maturity. Compound status uses seven semantic tones and explicit Inline/Stacked layouts so the same truth reflows across wide and narrow surfaces. It exposes editable label/detail text plus detail visibility, but not an icon swap; icon meaning remains locked to tone.

### Validation

All seven status icons are 24×24 with bound vector paints. Status/Maturity passed with six variants, exact axis values, variable-bound surfaces, fixed labels and no editable properties. Status/Compound passed with 14 variants, exact Tone and Layout axes, two TEXT properties, one BOOLEAN property, no INSTANCE_SWAP property, fixed tone-to-icon mappings and zero missing fonts. Screenshot review passed for all three families.

### Next gate

Build and validate `Navigation/Row` and `Navigation/PaneToggle`, including expanded/collapsed icon alignment, 40px desktop rows, 44px touch targets, keyboard focus and reversible pane controls.

---

## CP-048 — Navigation component family closed

**Date:** 2026-07-20

### Artifacts

- `Navigation/Row`: Figma component set `97:342`
- `Navigation/PaneToggle`: Figma component set `98:808`

### Navigation row

The row family contains 28 variants across expanded/collapsed mode, desktop/touch target and seven interaction/state modes. Expanded rows retain a fixed 24px icon slot and label anchor. Collapsed rows render only the icon plus state dot while retaining Label as the implementation’s accessible name/tooltip. Structural validation confirmed exact axes/count, bound fills/strokes, correct 40/44px expanded targets, 44/48px collapsed targets, fixed dot visibility and focus effects.

### Pane toggle

The pane toggle family contains 20 44×44 variants. Side and current pane state fix both the visual icon and the hidden accessible action label; no editable property can make an expand control announce collapse or point in the wrong direction. Structural validation confirmed exact axes/count, icon-direction mapping, fixed labels, variable-bound surfaces and focus effects.

### UX closure impact

This closes the prior class of collapsed sidebars showing numbers instead of icons, misaligned icon-label anchors, undersized collapse controls and ambiguous collapse/expand direction. Screen repair may now consume both families.

### Next gate

Build `Permission/Notice`, then identity, composer/search/scope and evidence/action component families before shell and screen repair.

---

## CP-049 — Permission notice component closed

**Date:** 2026-07-20

### Artifact

- `Permission/Notice`: Figma component set `100:818`

### Contract

The component has five fixed permission states—Allowed, Limited, Denied, Revoked and Unknown—across Inline and Stacked layouts. Each state fixes its title and semantic icon. Only safe explanatory Detail copy is editable. Recovery controls are composed separately and must re-authorise at action time.

### Adversarial finding and repair

The first component-property default was too long for the 520px Inline layout and visibly clipped. It was replaced with the complete redaction-safe default `Protected content remains hidden.` The subsequent screenshot showed full text in every variant.

### Validation

Fresh validation confirmed 10 variants, exact Permission/Layout axes, one Detail TEXT property, no BOOLEAN or INSTANCE_SWAP escape hatch, correct fixed title/icon mappings, bound fills/strokes, exact Inline/Stacked dimensions, zero missing fonts and text width within content bounds.

### Next gate

Build Source/Identity and Model/Identity. DeepSeek, OpenRouter and Fireworks remain named/selectable with full model labels but no logo until permission; no substitute or invented logo may be shown.

---

## CP-050 — Source and model identity components closed

**Date:** 2026-07-20

### Decision recorded

DeepSeek, OpenRouter and Fireworks remain supported, selectable and named with full model labels. Their logos are withheld everywhere until permission is resolved. No neutral tile, fabricated letter mark or substitute provider logo is allowed. The neutral ExecuteSpec Services glyph is reserved for customer-hosted models only.

### Figma mutation

- Promoted six existing provisional 24px marks without recolouring: Bitbucket Cloud, Jira Cloud, Slack, OpenAI, Anthropic and Google.
- Created `Identity/Provider` (`105:470`) with ten fixed provider-name pairings.
- Created `Source/Identity` (`106:980`) with 18 Provider × Layout × State variants.
- Created `Model/Identity` (`109:1681`) with 42 Provider × Layout × Availability variants.

### Adversarial findings and repairs

1. Reusing the wide `Status/Compound` inside Source Identity caused card overflow and inter-variant overlap. It was replaced with compact semantic status chips using the fixed status-icon atoms.
2. The first structural check counted variants but did not test child containment. Fresh validation now checks composition-level child bounds.
3. The compact Jira sample wrapped its object label over the source link. The component now uses a provider-neutral editable sample label.
4. Compact Model Identity metadata needed more height and width for route plus maturity, especially customer-hosted. Compact variants are now 638×76 and pass bounds validation.

### Validation result

Provider Identity: 10/10 variants passed. Source Identity: 18/18 variants passed. Model Identity: 42/42 variants passed. DeepSeek, OpenRouter and Fireworks each have zero provider-mark instances. Screenshot review passed after repairs.

### Next gate

Build Objective/Row, Search/Context, Composer/Objective and Scope/Builder before composing new enterprise screens.

---

## CP-051 — Objective, search, composer and scope components closed

**Date:** 2026-07-20

### Figma mutation

- Created `Objective/Row` (`111:1363`): 24 State × Source variants.
- Created `Search/Context` (`112:1225`): six read-only search states.
- Created `Composer/Objective` (`113:1321`): six proposal states using typed Action/Button instances.
- Created `Scope/Builder` (`115:1324`): Compact, Expanded, Conflict and Denied.

### Authority and safety contracts

Search is visibly read-only and exposes scope plus retrieval modes in every state. The Objective composer says `PROPOSAL ONLY`; it can create a reviewable proposal but claims no direct mutation authority. Scope changes explicitly invalidate prior approval. Denied scope variants redact Service, Repo, Source and Time as `Protected` and remove the corresponding editable-property references.

### Adversarial findings and repairs

1. Objective rows had a 4px source-meta overflow and a misspelled radius token; both were repaired before acceptance.
2. Search initially exposed a Scope property that was not connected to visible text. The unused control was deleted and scope/modes were made fixed explicit metadata.
3. Scope Denied initially muted protected identifiers but still rendered them. Those four values are now structurally redacted.

### Validation result

Objective Row 24/24, Search Context 6/6, Objective Composer 6/6 and Scope Builder 4/4 passed fresh composition-level child-bounds checks and screenshot review. Source identity pairs remain fixed; attention and error states are not colour-only.

### Next gate

Build Citation/Row, Evidence/Card and Evidence/LedgerRow before action/decision/conflict/answer composition.

---

## CP-052 — Citation and evidence components closed

**Date:** 2026-07-20

### Figma mutation

- Created `Citation/Row` (`116:1633`): 24 Epistemic × Source variants.
- Created `Evidence/Card` (`117:1471`): six evidence-outcome variants.
- Created `Evidence/LedgerRow` (`118:1645`): 12 State × Compact/Detail variants.

### Evidence contracts

Every citation exposes source, object, version, observed time and epistemic classification. Denied citations preserve canonical source identity but replace object/version/time with `Protected` and remove the editable references. Evidence cards always show category, classification, exact subject version, environment, observed time and limitations. Unmeasured is distinct from Passed. Ledger rows preserve exact attempts and version, and Compact/Detail use identical column anchors.

### Validation result

Citation Row 24/24, Evidence Card 6/6 and Evidence Ledger Row 12/12 passed fresh composition-level containment checks and screenshot review. Denied citation values are structurally redacted. Ledger anchor order is identical across both layouts.

### Next gate

Build Action/Card, Decision/Card, Conflict/Card and Answer/Panel, then Verification rows and GeneratedTest proof.

---

## CP-053 — Action, decision, conflict and answer components closed

**Date:** 2026-07-20

### Figma mutation

- Created `Action/Card` (`119:3751`): 42 Lifecycle × Impact × Wide/Stacked variants.
- Created `Decision/Option` (`121:1866`): five whole-card selection states.
- Created `Conflict/Card` (`122:1953`): four resolution states.
- Created `Answer/Panel` (`123:2137`): eight answer states.

### Authority and truth contracts

Action cards show the exact actor, target, scope, effect, route, budget, preconditions, evidence, recovery, maturity and expiry being authorised. Conflicts cannot be resolved by similarity; the human-selected authority and rationale remain visible. Only Complete and Partial answers may claim grounding or expose an editable conclusion. Other answer states carry fixed state-specific conclusions.

### Adversarial findings and repairs

1. Stacked Action cards initially overflowed all three execution-contract cells. Cell/contract/card heights were increased and all 42 variants revalidated.
2. Answer states initially inherited Complete’s sample counts through shared text properties. Non-Complete states now use fixed state-safe counts.
3. Denied/conflicting/stale/unable/timeout/greenfield answers initially inherited Complete’s conclusion. Those conclusions are now fixed, state-specific and non-editable.
4. `grounded` language was removed from every answer state except Complete and Partial.

### Validation result

Action Card 42/42, Decision Option 5/5, Conflict Card 4/4 and Answer Panel 8/8 passed fresh composition-level containment checks. Wide and stacked exact-action specimens passed visual review; the remaining families passed full screenshot review.

### Next gate

Build Verification/PlanRow, Verification/RunRow and GeneratedTest/Proof, then shell/service/settings families.

---

## CP-054 — Verification and generated-test proof components closed

**Date:** 2026-07-20

- `Verification/PlanRow` (`126:2015`): five plan states; Required removal routes through Exception requested.
- `Verification/RunRow` (`127:2123`): eight run states with exact SHA, environment, attempts, duration, artifact and retry history; wrong SHA is Superseded and environment failure leaves outcome unmeasured.
- `GeneratedTest/Proof` (`129:2327`): 12 Context × Validity variants; passing on baseline and candidate is always Inconclusive.

All 25 variants passed semantic, containment and screenshot review. A 2px metadata overflow in GeneratedTest/Proof was repaired before acceptance.

### Next gate

Build shell, service, settings and remaining semantic families, then repair desktop screens and derive tablet/mobile.

---

## CP-055 — Responsive shell component families closed

**Date:** 2026-07-20

### Figma mutation

- Created `Shell/TopBar` (`132:2085`) with Desktop, Compact, Tablet and Mobile variants.
- Created `Shell/LeftPane` (`136:2403`) with Expanded, Collapsed and tablet Overlay variants.
- Created `Shell/Inspect` (`137:2448`) with Docked, Collapsed, Tablet drawer and Mobile route variants.

### UX and authority contracts

The top bar exposes context, current-job status, Ask, notifications and account without introducing a global create action that competes with the current Objective. The collapsed left rail contains semantic icons with names/status dots and no numeric placeholders. Inspect preserves scope/evidence across modes and keeps Search context read-only and visually separate from Objective composition.

### Adversarial findings and repairs

1. TopBar documentation clipping came from a full-pill component-set radius; it was replaced with the card radius.
2. Shared long TopBar defaults overflowed the 390px mobile variant; compact defaults and mobile density removed all off-canvas controls.
3. Inspect initially had seven containment failures in hugging header rows and tablet helper copy; fill sizing and wrapping fixed all seven.
4. Collapsed Inspect initially reused the Ask glyph for Search context and the Search glyph for Sources. Sources now uses the system/source grid and Search context uses the search glyph.

### Validation result

TopBar 4/4, LeftPane 3/3 and Inspect 4/4 passed fresh containment checks and screenshot review. JetBrains Mono and variable-bound shell paints were verified. Official/provisional source logos retain their unmodified brand paints by design.

### Next gate

Build Service/Row, Source/Row and Connector/Card, then settings/governance and shared feedback/data families.

---

## CP-056 — Service, source and connector component families closed

**Date:** 2026-07-20

### Figma mutation

- Created `Service/Row` (`140:2389`) with five operational states.
- Created `Source/Row` (`141:2518`) with seven source lifecycle states.
- Created `Connector/Card` (`143:3455`) with 24 provider × state variants.

### UX and trust contracts

Service state and LIVE/HISTORY observation are fixed independently from editable service metadata. Source rows separate source identity, lifecycle, capability, permission, freshness and owner. Connector cards keep provider identity distinct from connection health and expose the entire card as a named detail control.

### Adversarial findings and repairs

1. Service rows initially produced eight column-boundary failures; the exact 1320px grid was rebalanced and revalidated.
2. The Source deletion lifecycle label overflowed; the visible fixed label is now `PURGE QUEUED` while the variant remains `Deletion queued`.
3. A long nested Source Identity sample object overlapped the source link; it now uses the stable short identifier `PROD-1842`.
4. Connector visual QA explicitly covered Healthy, Validating and Purge so temporary work and terminal deletion cannot be mistaken for availability.

### Provider logo decision

DeepSeek, OpenRouter and Fireworks remain supported and named. Their provider/model identities intentionally render without logos or substitute placeholders until the separate asset/usage issue is resolved. Existing component structure already conforms.

### Validation result

Service Row 5/5, Source Row 7/7 and Connector Card 24/24 passed containment checks and screenshot review. Jira Healthy, Bitbucket Validating and Slack Purge were inspected as focused specimens.

### Next gate

Build settings/governance component families, then shared data/feedback/responsive primitives.

---

## CP-057 — Settings and governance component families closed

**Date:** 2026-07-20

### Figma mutation

- Created `Settings/GroupNav` (`147:2663`) and `Settings/ChangeReview` (`147:2836`).
- Created `Memory/Row` (`151:2741`) and `Audit/Row` (`151:2880`).
- Created `Handoff/Card` (`152:2791`) and `Continuity/Card` (`152:2909`).

### Adversarial findings and repairs

1. Compact settings rails could not contain full dirty/error pills; they now use named semantic status dots while expanded/mobile preserve explicit labels.
2. Badge fills bound directly to warning/danger variables erased text contrast; dedicated soft fills restored readable pills.
3. Clean, validation-failed and saved change-review states used the ambiguous disabled label `Unavailable`; each now has explicit non-action guidance.
4. Memory and Audit status columns inherited Figma's default white frame fill; the leak was removed from all ten variants.
5. Handoff masters clipped 24 requested-responsibility/recovery fields and Continuity clipped six final actions; masters and documentation frames were resized and reflowed, retaining every field.

### Validation result

All 32 variants across the six families pass final child-containment checks and focused screenshot review. Memory distinguishes explicit/inferred/policy precedence, audit retains viewer-safe correlated evidence, handoff is consent-based, and continuity is one-time, scoped and destination-reauthorized.

### Next gate

Build Objective/Stage, Timeline/Event, Empty/State, Failure/Panel, Drawer/Modal/Sheet, Toast/Banner and Loading/Skeleton.

---

## CP-058 — Live session handoff established; component reconciliation active

**Date:** 2026-07-20

### Handoff artifact

Created `18-SESSION-HANDOFF-AND-CHECKPOINT.md` as the operational continuation source. It records canonical/staging paths, Figma file/page IDs, mandatory skills, explicit product decisions, component IDs, completed audits, the active defect and the exact resume sequence. It must be updated at every later checkpoint.

### New validated families

Objective Stage wide/compact, Timeline Event, Empty State, Failure Panel, Drawer/Modal/Sheet, Toast/Banner and Loading/Skeleton were created, structurally validated and visually reviewed.

### Active reconciliation defect

Permission/Notice still lacks the required Expired and Wrong view variants. Four edited masters were cloned as `158:22`, `158:29`, `158:38`, `158:47`, but Figma placed them on Cover `0:1`; set `100:818` remains at 10 variants. Move them into the set, confirm the variant axes, clean Cover and revalidate before closing P3.n.

### Goal state

The enterprise closure goal remains active. Desktop repair, responsive screens, prototype coverage, adversarial/accessibility audit and coding-agent handoff are still pending.

---

## CP-059 — Phase 3 component library closed

**Date:** 2026-07-20

### Permission reconciliation

Moved Expired and Wrong view masters `158:22`, `158:29`, `158:38`, `158:47` from Cover into `Permission/Notice` (`100:818`). The set now exposes seven permission states × Inline/Stacked = 14 variants. Cover contains no strays. Expired was corrected from inherited revocation red to terminal-neutral.

### Consolidated audit

- 41/41 required component-family contracts covered.
- 50 component sets present including three retained legacy migration sets.
- Zero missing fonts.
- Zero direct-child overflows.
- DeepSeek, OpenRouter and Fireworks: canonical names present, zero provider logo instances, six model variants each, zero model-logo instances.
- Original minimal sets renamed `Legacy/Button`, `Legacy/Objective Row`, `Legacy/Source Row` and marked deprecated.

### Gate result

Phase 3 is complete. The next gate is desktop Enterprise Screens repair using canonical component instances, followed by tablet/mobile derivation, prototype and adversarial/accessibility validation. The live handoff was updated to CP-059.

---

## CP-060 — Desktop neutral/redaction repair batch 1 validated

**Date:** 2026-07-20

### Figma mutations

- Added `EN-D-00A · Neutral view choice · R1` (`166:345`) as the replacement for populated `50:291`.
- Added `EN-D-01Z · Zero information · R1` (`168:3261`) as the replacement for populated `40:103`.
- Originals remain retained until the full replacement batch and prototype transitions are verified.

### Adversarial findings and repairs

1. The first neutral-choice render inherited Figma's white frame fill and compressed 200px Decision/Option masters to 156px, causing status overlap. Structural fills were made transparent and both option instances restored to intrinsic height.
2. The zero-information inline notice initially clipped a long explanation. The message was reduced to the exact state assertion `No connected source or memory was queried.` and revalidated at zero overflow.
3. Both replacements were scanned for inherited Acme/Checkout/Bitbucket/Jira/Slack/objective/device identifiers. Zero hits remain.

### Validation result

Both screens use canonical linked components, local color variables, JetBrains Mono for new composition text, zero direct-child overflow and focused screenshot review. View choice exposes no organization/work context before selection. Zero information exposes no organization, repository, source, person, objective, graph, freshness or memory data and performs no retrieval.

### Next gate

Split permission denied from searched-no-result (`40:438`), then split wrong-view, expired and denied recovery from `50:626`.

---

## CP-061 — Desktop state split batch 2 validated

**Date:** 2026-07-20

### Figma mutations

- Added context permission denied `172:487` and searched-no-result `174:562` as separate replacements for overloaded `40:438`.
- Added wrong-view `175:611`, continuity-expired `176:3425` and authorization-denied `177:663` as separate replacements for overloaded `50:626`.
- Bound Expired/Wrong-view Permission/Notice detail layers `158:26`, `158:33`, `158:42`, `158:51` to shared property `Detail#100:0`.

### Adversarial findings and repairs

1. The canonical denied Answer/Panel contains a fixed sample Checkout scope. The linked instance on `172:487` overrides it with `Requested enterprise context`; no protected object/source identifier remains.
2. No-result can be misread as absence across protected sources. The search receipt explicitly records completion, `0 permitted matches`, and that denied/unavailable sources were not searched and are not counted as zero.
3. Expired/Wrong-view masters accepted `setProperties` calls but ignored the detail override because their cloned text layers were not bound. The four master references are now repaired and a throwaway instance test returned the requested value.
4. Link authorization denied initially overflowed the inline notice. Its state assertion was shortened to `Current account cannot authorize this link.` without weakening recovery or redaction information.

### Validation result

All five split screens contain only their own state semantics, zero inherited forbidden identifiers, zero overflow, canonical linked instances and focused screenshot review. The original overloaded frames remain retained pending full prototype transition validation.

### Next gate

Repair failed/inconclusive/unmeasured evidence, partial/stale/conflicting answers, and denied/unable answers.

---

## CP-062 — Evidence and answer truth batch 3 validated

**Date:** 2026-07-20

### Figma mutations

- Added verification failed/incomplete `179:674` for `42:175`.
- Added partial/stale/conflicting answer `181:806` for `42:399`.
- Added answer denied `183:973` and answer unavailable `184:1026` as cause-separated replacements for `42:510`.
- Bound Partial Answer/Panel layers `123:1895`, `123:1898`, `123:1901`, `123:1904` to the shared Citations, Conflicts, Omissions and Omissions-detail properties.

### Adversarial findings and repairs

1. Verification process completion previously risked reading as outcome/evidence completion. The replacement shows one Failed, one Inconclusive and one Unmeasured evidence card plus a Failure compound summary and preserved recovery proof.
2. Partial Answer accepted a `Conflicts=1` property call but displayed zero because the Partial master counts were unbound. All four shared count/omission references are repaired; the screen now displays `CONFLICTS 1` and the exact omission explanation.
3. Partial, stale and conflicting signals are visible together. Similarity is prohibited from choosing Jira/Slack authority; finalization requires explicit authority, refreshed evidence and accounted omissions.
4. Permission denial and environment/availability failure are now independent. Both show `NO GROUNDING CLAIM`; denied exposes no protected identifiers; unavailable explicitly says permission remains allowed.

### Validation result

All four screens use canonical linked components and pass focused visual review. Layout overflow is zero. The only nested bound exceptions are two 20px official Jira/Slack vector clones inside 16px identity wrappers; they are intentional brand artwork, visually contained relative to adjacent content and excluded from layout-overflow accounting.

### Next gate

Repair connector drift, disconnect/purge/export, rejected/expired plan, incident, route unavailable/limit and reconciliation.

---

## CP-063 — Operational consequence batch 4 validated; work paused

**Date:** 2026-07-20

### Figma mutations

- Added connector drift `187:1120` for `44:359`.
- Added disconnect/purge/export `189:1278` for `44:471`.
- Added rejected/expired/invalidated plan `192:1440` for `41:139`.
- Added P2 conditional incident remediation `194:1593` for `27:714`.

### Adversarial findings and controls

1. Connector drift no longer reports Healthy. Connection, permission, availability, freshness and capability are independently visible and consistent.
2. Purge no longer collapses disconnect, deletion and export into one button. The review exposes exact target, retained/deleted data, legal-hold result, export-first path, typed confirmation, reauthentication and irreversibility boundary.
3. Invalid plan approval cannot execute. Plan v3 rejection and Plan v4 invalidation remain in the decision timeline, and recovery requires rescope/re-estimate/fresh approval.
4. Incident rollback is not a generic green action. It is explicitly P2 + Conditional, recommendation-only until the named Incident Commander approves the exact production release, with one-attempt/time-window/evidence/recovery bounds. P0/P1 are explicitly non-executable.

### Validation result

All four screens use canonical linked components, have zero layout overflow and passed focused screenshot review. Originals remain retained until prototype replacement is verified.

### Pause and resume point

User requested a pause after CP-063. The goal remains active and is not complete. Resume with route unavailable/limit (`44:583`) and external-write reconciliation (`51:327`), record CP-064, then continue shared-shell migration, purpose-built global tools, responsive derivation, prototype, accessibility/adversarial validation and coding-agent handoff.

---

## CP-064 — Route governance and external-write reconciliation validated

**Date:** 2026-07-22

### Figma mutations

- Added route unavailable / managed usage limit replacement `200:1698` for `44:583`.
- Added uncertain external-write reconciliation replacement `202:1834` for `51:327`.
- Corrected Timeline/Event `Type=External` master `153:3114`: label `153:3119` is now `EXTERNAL WRITE EVENT`; the reconciliation instance renders `OUTCOME UNKNOWN`.

### Adversarial findings and controls

1. The legacy route screen offered a generic Apply route path while provider health, usage ceiling and policy availability were degraded. The replacement keeps current provider/model identity separate from health, exposes usage and policy constraints, blocks execution, requires candidate provider/model/data-region/cost checks and preserves paused-call reconciliation. No silent failover occurs.
2. The legacy reconciliation screen could imply evidence completion while Confluence confirmation remained pending. The replacement explicitly says Outcome unknown, preserves request/correlation/idempotency proof and prohibits blind or new-key retry.
3. The shared external timeline variant falsely labeled an external event as `JIRA RESULT CONFIRMED`. The master is now source-neutral and the screen-specific state is Outcome unknown.
4. A first route-screen render clipped the policy-facts container to 1px. The container was repaired to 180px and re-reviewed. Action-card actor/scope/route/budget/recovery strings were tightened only where necessary to avoid truncation without weakening the contract.

### Validation result

Both screens use canonical linked shell/status/identity/action/failure/timeline/button components. Metadata structure is valid; focused screenshots were reviewed at natural 1440×960 size. A scripted contract audit found zero required-copy omissions, zero forbidden Legacy/Apply route/Evidence complete/Jira-confirmed/blind-retry hits, zero missing fonts, non-overlapping roots and 44px actionable controls.

### Next gate

Proceed with shared-shell migration and purpose-built Intelligence, Services and Settings surfaces, followed by responsive derivation, prototype validation, accessibility/adversarial audits and coding-agent handoff. The goal remains active.

---

## CP-065 — Purpose-built Intelligence, Services and Settings validated

**Date:** 2026-07-22

### Figma mutations

- Added Intelligence scoped-query replacement `207:1960` for `27:518`.
- Added Services portfolio replacement `209:2309` for `27:616`.
- Added Enterprise Settings replacement `211:2595` for `27:812`.
- Added Search/Context property `Scope summary#208:0` to set `112:1225` and bound all six state layers.
- Replaced old Settings/GroupNav labels with the approved six-group IA across Expanded and Mobile variants; moved current-screen selection to Governance + AI.

### Adversarial findings and controls

1. Legacy Intelligence, Services and Settings were the same Objective/answer/decision/composer layout with different nouns. Replacements now use distinct primary objects: scoped query/session, service portfolio and administration workspace.
2. Search/Context exposed a hardcoded `payments-api` scope that contradicted the visible `checkout-api` query boundary. The shared scope property now makes every state instance-overridable; current Intelligence scope is exact.
3. Service/Row is a 1320px portfolio component. Rendering it beside an expanded rail clipped Active/Planned work. The Services default intentionally collapses both semantic rails to 56px and allocates 1328px to centre with an exact 1320px row width.
4. Settings GroupNav labels did not match the approved six groups. Shared labels are now canonical. Relabeling revealed an inherited active highlight on Knowledge + context; the current instance now selects Governance + AI. Reusable selected-group variants/API remain required before final component-contract closure.

### Validation result

All three screens passed focused screenshot review and a scripted cross-screen audit. Required copy is complete; forbidden Legacy/New-objective/generic production-action language is absent; no Objective composer appears; pane modes match each job; Intelligence has one read-only Search/Context with exact scope; three Service rows retain 1320×88; Settings Governance + AI is selected; fonts and 44px controls pass; roots do not overlap.

### Next gate

Replace model-policy matrix `31:94`, close reusable Settings selected-group state, and migrate remaining required desktop surfaces to the shared shell before responsive derivation. The goal remains active.

---

## CP-066 — Reusable Settings selection and AI model-policy matrix validated

**Date:** 2026-07-22

### Figma mutations

- Expanded Settings/GroupNav `147:2663` from three layout-only variants to 18 `Layout × Selected` variants.
- Bound `Subsection#147:3` across all 18 variants.
- Resized the component set to 822×3280 and its documentation frame to 3462px; shifted 12 following documentation frames by 2702px.
- Added AI model-policy matrix replacement `220:2861` for `31:94`.

### Adversarial findings and controls

1. A screen-only selected-row override was not an implementation-ready component API. The GroupNav now supports all six groups in Expanded, Compact and Mobile layouts, with exactly one matching active row per variant.
2. `clone()` initially produced 15 page-level components rather than component-set children. The exact returned node IDs were reparented into `147:2663`; no guessed cleanup occurred.
3. Figma did not preserve subsection property references on cloned variants. All 18 subsection layers are now explicitly bound and instance overrides render correctly.
4. Model labels initially duplicated provider names already shown with official marks, causing over-wrap. Labels now show agent tier + full registry model while the adjacent identity component carries provider name/logo.
5. The model-policy action is high-impact and review-first. Availability, budget, data policy and block-on-unavailable behavior are visible; no Apply route or silent failover exists.

### Validation result

The component audit found 18 variants, six Selected values, one matching active frame per variant and 18 live subsection bindings. The model screen contains exact OpenAI/Anthropic/Google identities, Allowed/Allowed/Limited state, budget/data/failure boundaries, no forbidden copy, no missing fonts and 44px actions. Focused screenshots passed visual review.

### Next gate

Migrate the remaining required desktop lifecycle/state screens in Batch R2 to the canonical shared shell, then begin responsive derivation. The goal remains active.

---

## CP-067 — Core Objective lifecycle and shared navigation selection validated

**Date:** 2026-07-22

### Figma mutations

- Added canonical Home replacement `227:3203` for `27:126`.
- Added Objective context replacement `227:3470` for `9:2`.
- Added Plan approval replacement `227:3737` for `27:224`.
- Added Working Objective replacement `227:4004` for `27:322`.
- Added Evidence and delivery replacement `227:4271` for `27:420`.
- Expanded Shell/LeftPane `136:2403` to 18 Mode × Selected variants.
- Raised Shell/TopBar Ask targets from 32px to 40px on Desktop/Compact and 44px on Tablet.

### Adversarial findings and controls

1. Shell/LeftPane hardcoded Home as active, so Objective, Evidence and global-tool screens inherited a false current destination. The reusable component now exposes Selected=Home/Objectives/Intelligence/Services/Evidence/Settings across Expanded, Collapsed and Overlay modes, with exactly one matching Active row.
2. The original Collapsed/Home master also left Intelligence persistently Focused. Focus is transient keyboard state, not selection; the stray state was removed.
3. The first Context composition used Scope/Builder Conflict for a source-authority disagreement even though service and repository scope matched. It now uses Compact exact scope; the disagreement lives only in Conflict/Card with NO AUTOMATIC MERGE and a human authority gate.
4. Several compound-status strings overflowed their fixed slots. Copy was shortened without dropping state, count, authority or deferred-signal meaning.
5. The TopBar Ask action was 32px despite the 40px desktop and 44px touch contract. The shared masters now meet those target sizes, and all screen instances inherit the fix.
6. Evidence keeps the production-only latency signal explicitly Unmeasured. Plan and delivery actions explicitly exclude production deployment and expose recovery before approval.

### Validation result

The LeftPane audit found all 18 Mode × Selected combinations, no missing combination, one matching Active destination per variant, no persistent Focused state, no set overflow and no downstream documentation overlap. The five lifecycle screens contain canonical components only, truthful selected destinations, no legacy instances, no centre overflow, no missing fonts and no action under 40px. Required source-authority, cost, expiry, recovery, checkpoint, unmeasured-evidence and reconciliation copy is present. Focused 1440×960 screenshots passed visual review.

### Next gate

Migrate degraded/recovery lifecycle states `40:215`, `40:327`, `41:251`, `41:363`, `41:475`, `42:287`, then complete service/incident/connector and continuity variants before responsive derivation. The goal remains active.

---

## CP-068 — Degraded and recovery lifecycle validated

**Date:** 2026-07-22

### Figma mutations

- Added source-unavailable Home replacement `235:5159` for `40:215`.
- Added partial/stale Context replacement `235:5449` for `40:327`.
- Added approved/bounded-authority Plan replacement `235:5745` for `41:251`.
- Added blocked Working replacement `235:6027` for `41:363`.
- Added recovered/superseded Working replacement `235:6335` for `41:475`.
- Added partial external-write Delivery replacement `235:6639` for `42:287`.

### Adversarial findings and controls

1. Source unavailability is not permission denial or loss of all context. Jira is explicitly Unavailable while cached evidence, last freshness, correlation and reconnect paths remain visible.
2. Partial context is not a complete answer. Current repository/Confluence evidence, stale Jira comments and denied Slack scope are classified independently; omitted evidence cannot silently influence planning.
3. Approval does not imply broad authority. Plan v4 authorises only an isolated preview, with exact target, `$8.40` ceiling, expiry, proof and recovery. Production deployment and merge remain excluded.
4. A failed CI environment cannot produce a failed or passed test claim. The test outcome is Unmeasured, the checkpoint is durable, and resume requirements are explicit.
5. Recovery does not erase history. The replacement SHA is current while evidence from the original attempt is retained and labelled Superseded.
6. Delivery is not atomic when external systems diverge. GitHub and Jira writes are recorded as passed; the Confluence write is failed with request evidence, a same-target retry and an explicit rollback path.

### Validation result

All six screens passed focused 1440×960 screenshot review and a scripted cross-screen audit. Selected destinations are Home/Objectives/Evidence as appropriate; required unavailable/partial/bounded/blocked/recovered/superseded/write-failure language is present; no legacy components, missing fonts or centre overflow remain; minimum action height is 40px.

### Next gate

Complete service/incident/connector and continuity desktop variants, then derive tablet and mobile layouts. The goal remains active.

---

## CP-069 — Service, incident and first-connector surfaces validated

**Date:** 2026-07-22

### Figma mutations

- Added live-source-unavailable service replacement `239:1356` for `43:211`.
- Added active-incident investigation replacement `239:1843` for `43:323`.
- Added blocked incident-action replacement `239:2257` for `43:435`.
- Added verified-remediation/write-back-failed replacement `239:2671` for `43:548`.
- Added first Jira connector permission-review replacement `239:3226` for `44:247`.

### Adversarial findings and controls

1. Historical service evidence is not current health. The unavailable screen says `HISTORY ONLY`, timestamps the last valid observation and makes present health unknown.
2. P1 incident support is not a production control plane. Investigation and remediation planning are available; the proposal explicitly does not mutate production.
3. Showing a blocked rollback must not leak execution authority. No token, credential or command is exposed; future P2 allow-list, identity, approval and verification requirements remain inspectable.
4. Successful remediation is not equivalent to complete incident delivery. Production proof passed, but ServiceNow returned HTTP 403 and no record changed; retry is blocked until permission is restored.
5. Connector approval must precede OAuth and ingestion. Jira remains Not connected; exact selected-project scope, read/write effect, least privilege, region check and deletion recovery appear before the high-impact approval.
6. Screenshot review found defects the metadata audit missed: stale Governance + AI subsection copy on the Integrations screen, clipped denied-authority copy and truncated outcome statements. All were repaired and re-rendered.

### Validation result

All five screens passed final visual and scripted QA. Services/Settings selection is truthful, collapsed operational rails retain the 1320px Service/Row contract, no workspace content overflows, no legacy component or missing font remains and all actionable targets are at least 40px.

### Next gate

Close secure continuity created/source-changed/completed desktop states, then begin tablet and mobile derivation. The goal remains active.

---

## CP-070 — Secure continuity desktop states validated

**Date:** 2026-07-22

### Figma mutations

- Added secure handoff token-created desktop state `245:1722`.
- Added continuity source-changed desktop state `245:2113`.
- Added continuity completed desktop state `245:2417`.

### Adversarial findings and controls

1. A handoff link is not a serialized session. It contains no protected content; it carries only one-time, user-bound scope and the destination evaluates current permissions before restore.
2. Token validity does not override source drift. If the approved SHA changes, restoration is blocked and the user must inspect the exact diff and reauthorize the current source.
3. Completion is not a reusable login. The token is consumed and invalidated after one use; the receipt records same-user verification, current-permission evaluation and the exact restored snapshot.
4. Device labels are generic (`Mobile device`, `Desktop browser`) rather than personal hardware identifiers.
5. Fixed-height permission notices initially wrapped outside their visual containers. The summaries were tightened and re-rendered without removing authorization or redaction requirements.

### Validation result

All three screens use Objectives-selected canonical shell instances and validated Continuity/Card variants. No raw/secret-like token is visible; no protected content is embedded in the link; source-change blocking and one-time completion terms are present; no legacy component, missing font, centre overflow or action under 40px remains.

### Next gate

The agreed Enterprise desktop repair inventory is closed. Begin responsive derivation from `05-RESPONSIVE-RULES.md`, starting with core Objective lifecycle and secure handoff. The goal remains active.

---

## CP-071 — Core Enterprise tablet routes validated

**Date:** 2026-07-22

### Figma mutations

- Added tablet Home `251:9440`, Context `251:9706`, Plan `251:10003`, Working `251:10185`, Evidence/Delivery `251:10379` and modal Inspect `251:10683`.
- Repaired Shell/Inspect tablet master `137:2318` from 360px to 560px and expanded its documentation/set containment.
- Repaired Drawer/Modal/Sheet tablet surface `155:2810` from 360px to 560px.
- Replaced the embedded 40px conflict action with a read-only authority comparison plus a separate 44px source-choice action.
- Corrected fixed-strip status copy by preserving distinct `label` and `detail` component slots.

### Adversarial findings and controls

1. Tablet is not a scaled desktop. A semantic 56px rail replaces the expanded navigation and the primary workspace owns the remaining 778px.
2. Inspect is modal at this breakpoint. The 560×896 sheet uses a scrim, makes background content inert, traps focus, closes by Escape/back and restores focus to the invoker.
3. A source conflict cannot inherit a sub-touch-target action. The comparison is read-only and the governing-source choice is an explicit 44px control outside the evidence card.
4. Compact status does not justify clipped trust copy. Context, Plan, Working and Evidence retain complete authority, scope and proof semantics within the 40px strip.
5. Evidence search remains read-only and permission-scoped; opening Inspect does not widen access or authorize a stale plan.

### Validation result

All six screens are 834×960. The final scripted audit reported zero failures for frame sizing, status-copy overflow, text outside the frame, legacy components and missing fonts. All audited controls are at least 44px; T11's drawer is exactly `x=274`, `y=64`, `560×896`. Focused screenshots passed visual review.

### Next gate

Derive the 390×844 mobile core routes and secure handoff states using a top bar plus bottom/overflow navigation, with no permanent panes. The goal remains active.

---

## CP-072 — Core Enterprise mobile routes and prototype validated

**Date:** 2026-07-22

### Figma mutations

- Added 13 mobile routes covering M00 navigation, M01 Home, M02 Objective/Context, pushed Inspect, M03 Plan, M04 Working and blocked recovery, M05 Evidence and partial write-back, and four M10 continuity states.
- Added reusable `Shell/MobileBottomNav` set `261:6859` with five truthful selected-destination variants and documentation `261:6681`.
- Reused Shell/Inspect `Mode=Mobile route` for pushed evidence screen `266:15874`.
- Applied state-correct TopBar status icon overrides instead of the inherited Attention icon.
- Added 76 valid prototype reaction sources and named mobile core/continuity starting points.

### Adversarial findings and controls

1. Mobile cannot be a compressed three-pane desktop. Primary routes have no permanent LeftPane or Inspect; navigation is bottom/overflow and evidence is pushed full-screen.
2. Plan approval retains Inspect, Change scope, Reject and Approve. No breakpoint widens authority or reuses approval after source, scope, route or cost drift.
3. Working and blocked recovery are separate. Environment failure leaves verification Unmeasured and preserves the checkpoint; it does not fabricate pass/fail evidence.
4. Deterministic proof and production-only unmeasured signals remain separate. Partial write-back names the successful GitHub/Jira writes and failed Confluence target independently.
5. Continuity created, changed, expired and completed cannot share one generic success screen. Protected content is never embedded in the link; source drift blocks restore; expiry restores nothing; completion consumes the token.
6. A warning glyph beside Running, Expired or Done is semantically false. Mobile TopBars now use Active, Terminal, Success, Blocked or Failure icons as appropriate.
7. Prototype links may not use self-navigation or misleading state transitions. The audited graph has valid top-level destinations, explicit back/close behavior and no self-navigation.

### Validation result

All 13 frames are 390×844. There are no text-bound, missing-font, legacy, touch-target, state-tone, permanent-pane, destination or self-navigation failures. Action and prototype targets are at least 44px; bottom navigation items are 72×56. The two named mobile flows and 76 reaction sources are present.

### Next gate

Derive mobile/tablet Intelligence, Incident and critical Settings, then close M09 deep-link states and remaining explicit continuity states. The goal remains active.

---

## CP-073 — Responsive Intelligence, Services, Incident and critical Settings validated

**Date:** 2026-07-22

### Figma mutations

- Added six tablet routes: Intelligence partial `270:12803`, Services portfolio `270:13019`, conditional incident `270:13183`, critical connector review `270:13339`, Intelligence denied `273:13993` and authorization-failed recovery `273:14146`.
- Added five mobile routes: Intelligence partial `271:13510`, Intelligence denied `271:13640`, conditional incident `271:13749`, critical connector review `271:13878` and authorization failed `271:13990`.
- Added named prototype entry points `Enterprise Tablet · Operations and Governance` and `Enterprise Mobile · Intelligence, Incident and Settings`.
- Wired 42 interaction sources/destinations across global navigation, Services→Incident, permission recovery, connector review/failure recovery and bounded Objective proposal paths.
- Repaired clipped mobile failed-authorization banner copy and latent tablet T09/T10 field-value bounds, then re-rendered the affected screens.

### Adversarial findings and controls

1. Partial Intelligence is not complete grounding. Citations, omissions and conflicts remain visible, and denied Slack evidence does not influence the answer.
2. Permission denial is not a zero-result search. Denied routes execute no retrieval/query, expose no protected identifiers and produce no grounded answer.
3. Historical service evidence is not current health. The Services route declares current health unknown and does not expose production action authority.
4. Incident assistance is not a production control plane. The remediation path is `P2 · CONDITIONAL`, creates a reviewable Objective proposal and contains no credential or direct mutation.
5. Connector review is not connection. Before OAuth, no tenant data has been read; requested scope cannot expand silently.
6. OAuth failure is not partial success. Jira remains disconnected, zero objects are read or indexed, and retry begins a fresh authorization/policy evaluation.
7. Component-instance internals can report false text-overflow positives. The final audit excludes immutable instance internals but still checks every local text override against its local container; this caught the real Settings value-width defects.

### Validation result

Final visual review passed for all 11 screens, including repaired tablet Settings renders `/private/tmp/executespec-en-t-09-settings-r1-final.png` and `/private/tmp/executespec-en-t-10-recovery-r1-final.png` plus mobile failed authorization `/private/tmp/executespec-en-m-08f-settings-failed-r1-final.png`.

The final scripted Figma gate covered 11 roots, 350 text nodes, 42 reaction sources and 42 destinations. It reported zero failures for viewport geometry, required adversarial copy, local text bounds, missing fonts, 44px touch targets, tablet rail/mobile bottom-navigation contracts, permanent-pane leakage, invalid destinations, self-navigation and named flows.

### Next gate

Close M09 authorised/wrong-view/expired/denied deep-link routes and explicit continuity Opened/Revoked states, then run the full prototype, accessibility and PRD-traceability closure audits. The goal remains active.

---

## CP-074 — Responsive external links and secure continuity matrix validated

**Date:** 2026-07-22

### Figma mutations

- Added four tablet EN-60 states: Authorized `282:14299`, Wrong view `282:15102`, Expired `282:15899`, Denied `282:16704`.
- Added four mobile EN-60 states: Authorized `282:17515`, Wrong view `282:18354`, Expired `282:19185`, Denied `282:20025`.
- Added the complete tablet EN-61 origin matrix: Created `283:14489`, Opened `283:15533`, Expired `283:16587`, Revoked `283:17647`, Source changed `283:18717`, Completed `283:19794`.
- Added missing mobile Opened `283:20866` and Revoked `283:21844` states.
- Added/retained six named entry flows for tablet/mobile external-link authorization/recovery and tablet/mobile secure continuity.
- Rewrote 35 screen action reactions after audit found cloned Action/Button instances had inherited unrelated source-screen destinations.
- Repaired mobile Expired/Denied two-line heading and description collisions.

### Adversarial findings and controls

1. An external-link shell must not resolve target content before authorization. Wrong-view, expired and denied states use a neutral ExecuteSpec header and contain no tenant, object, repository, ticket, source, member, graph, version or device identifier.
2. Authorized links do not inherit the creator's permission. Current viewer scope and source version are checked before Jira Cloud and the exact target/action appear.
3. Wrong view, expired and denied are separate security states. Switching view triggers a fresh check; expiry creates no destination session; denial preserves event evidence while redacting target identity.
4. Opening a continuation token proves receipt only. Protected context remains unrestored while identity, permission, source version and approval expiry checks are pending.
5. Revocation is terminal for that token. It blocks the destination, preserves the audit event and requires a fresh token for recovery.
6. Source change cannot consume stale approval. Exact diff review and current-source reauthorization are required.
7. Completion is not reusable access. Same-user/current-permission checks precede restore and the one-time token is consumed and invalidated.
8. Cloning a component instance can clone prototype reactions. Every derived Action/Button must have its destination explicitly cleared or replaced; source-instance reactions cannot be trusted as defaults.

### Validation result

Focused screenshots were reviewed for all 16 screens. Final repaired mobile renders are `/private/tmp/executespec-en-m-09e-link-expired-r1-final.png` and `/private/tmp/executespec-en-m-09d-link-denied-r1-final.png`; continuity renders use `/private/tmp/executespec-en-{t,m}-10*.png`.

The final scripted Figma gate covered 16 roots, 333 text nodes, 43 reaction sources and 43 destinations. It reported zero failures for viewport size, required state copy, neutral-shell disclosure, official Jira mark/name pairing, local text bounds, heading/description flow, missing fonts, 44px targets, tablet rail/mobile bottom navigation, personal device identifiers, invalid destinations, self-navigation and all six expected flow starts.

### Next gate

Responsive derivation is closed. Run the complete cross-surface prototype, accessibility, adversarial/redaction and PRD-traceability closure audits, then produce the implementation handoff. The goal remains active.

---

## CP-075 — Prototype graph and responsive PRD gaps closed

**Date:** 2026-07-22

The accepted Enterprise Screens page now contains 110 `EN-D/T/M` roots and 324 visible Action/Button instances. The final graph audit reports 313 wired actions, 11 explicitly inert controls and zero accidental unwired, invalid, self-linked or malformed external actions.

Figma additions:

- desktop continuity Opened `296:15631` and Revoked `297:15875`;
- tablet/mobile zero-information setup `301:16123`, `301:19043`;
- tablet/mobile context-authority resolved `301:19230`, `302:16424`;
- mobile model-route review/block `302:19297`, `302:19483`;
- tablet/mobile neutral Studio/Enterprise choice `302:19673`, `302:19907`.

The pass fixed status-banner truncation, tablet action-label overflow and four mobile TopBar pressure/containment defects. Focused accessibility assertions pass for 44px mobile actions, 9px minimum focused text, top-bar containment, unique screen names and zero pending destinations.

The product decision remains explicit: Studio opens a separate URL and receives no Enterprise context automatically. Provider routes remain supported even where logos are temporarily omitted.

This checkpoint does not close the goal. Whole-file redaction, keyboard/focus, contrast, reflow/localisation, named-flow replay and coding-agent handoff remain open. See `19-CROSS-SURFACE-CLOSURE-AUDIT.md` and `20-FIGMA-PROTOTYPE-ACCESSIBILITY-AUDIT.md`.

---

## CP-076 — Enterprise product/UX closure and coding-agent handoff

**Date:** 2026-07-22

The final live Figma audit passes across 110 accepted roots: 48 desktop, 30 tablet and 32 mobile. It measured 4,035 visible text nodes, 327 canonical Action/Button instances, 127 icon-only targets, 20 named flow starts and 13 redaction-critical roots.

Final result: zero text below 9px, visible clipping, viewport overflow, AA contrast failure, accidental unwired action, missing destination, self-link, malformed URL, generic/undersized icon target, protected-context disclosure, accepted-screen dead end, duplicate screen name or invalid flow start. The action graph contains 316 wired and 11 explicitly inert canonical controls.

Material repairs:

- replaced a 1320px Source Row compressed into a 664px source-unavailable workspace with a compact canonical Source Identity composition;
- raised inherited 8px metadata to 10px across seven dense-data component families without dimension changes;
- repaired 31 clipped instance strings and restored the external Timeline Event composition to its canonical 1320px width;
- rebound low-contrast terminal/disconnected labels to semantic accessible variables;
- added canonical recovery controls to desktop Continuity Expired and Link Authorization Denied, removing the last accepted-screen dead ends;
- retargeted legacy starts, removed generic flow names and added explicit zero-information/model-route starts;
- attached focus-order, modal trap/dismissal, focus-return, reduced-motion and live-region contracts to the accepted screens.

PRD set equality is exact: 109 unique IDs in the Enterprise PRD and 109 rows in the traceability matrix, with no missing or extra ID.

New implementation records:

- `21-CODING-AGENT-IMPLEMENTATION-HANDOFF.md`
- `22-FIGMA-PROTOTYPE-GRAPH.json`

Enterprise product definition and UX are closed. Browser keyboard/screen-reader execution, 200% zoom/reflow, 30% localisation expansion, production RBAC/source-ACL redaction, connector certification and deterministic `AT-<PRD-ID>` results remain implementation gates. Static Figma evidence must not be represented as production readiness.

---

## STUDIO-CP-001 — Individual-user Studio closure started

**Date:** 2026-07-23

Enterprise and Studio are now paired with separate canonical design files. The Enterprise implementation PRD links directly to `https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S` and states that a surface is implementation-ready only when PRD, traceability row and Figma state agree.

A separate Studio design file was created at `https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L`. It contains the initial page structure: Cover `0:1`, Foundations `1:2`, Components `1:3` and Studio Screens `1:4`. No visual screen is accepted yet.

The Studio source audit found substantial existing definition rather than a blank-slate product:

- 81 stable `STU-*` casual-builder requirements;
- 92 stable `PRO-*` professional-individual requirements;
- approved persona PRDs and coding-agent delivery guides;
- Free/Pro/Max product-line logic;
- Codex-inspired Task/Output shell direction;
- Build/Data/Checks/Ship Project lenses;
- exhaustive capability catalog and six-group Settings IA;
- verified, non-spend gamification rules;
- explicit mobile/tablet and professional progressive-disclosure intent.

The adversarial weakness is fragmentation. There was no master Studio authority, live tier semantics do not yet map cleanly to Free/Pro/Max, no 173-row code-parity/UX traceability audit exists, and no Studio Figma component/screen/prototype system exists.

Phase 0 closure artifacts were created under `other-solutions/studio/closure-v1/`:

- closure register;
- master Studio PRD baseline;
- grouped 173-requirement traceability matrix;
- UX foundation contracts;
- 153-state logical screen/state inventory;
- Figma Phase 0 gap analysis;
- resumable checkpoint and machine-readable state.

Closed working interpretation: Studio is one product at `studio.executespec.ai`; casual and professional users share one Project/Task/Output grammar; Home, Project and Account remain the only durable surfaces; Free/Pro/Max share the same shell; Professional Mode adds client/repository/CI/evidence depth without a separate freelancer application; tablet and mobile are closure surfaces, not later cleanup.

Next gate: reconcile Max with live Pro+/BYOK entitlements, complete existing/partial/planned/missing code parity for all 173 requirements, expand exact row-level traceability and consolidate 153 logical states into a comprehensible accepted composition set before building polished Figma screens.

Operational note: the installed Figma MCP cannot retrieve comment threads. Comments must be inspected through the signed-in Figma UI or through separately authenticated Figma REST comment access, then recorded in a durable review log.

---

## STUDIO-CP-005 — Free/Pro desktop composition gate closed

**Date:** 2026-07-23

Studio Phase 3 is closed in the canonical Figma file `LfEwxST9vagBMNJdVWn23L`. The Studio Screens page contains 40 accepted Free/Pro desktop compositions in five sections: P3.A `13:2`, P3.B `15:206`, P3.C `17:522`, P3.D `20:711`, and P3.E `23:962`.

The set covers start, import, building, visual iteration, data, checks, test repair, versions, compare, restore, publish, deployment, degraded health, rollback, sharing, export/remix, revocation, verified progress, optional quests, advanced view, professional graduation, plan limits, upgrade/resume, billing/downgrade, privacy/ownership, destructive deletion, offline/stale recovery, and independent pane collapse.

The complete metadata gate reports exactly D01–D40 with no missing or duplicate identifiers, no wrong viewport sizes, no JetBrains Mono drift, no wrong column structures, no placeholder copy, and no forbidden internal `PRO_PLUS`/`Pro+` or provider-logo terms. D40 deliberately uses 48 / 1344 / 48 official-icon rails so the center remains dominant when both panes are collapsed.

Fresh screenshots were inspected for every section. D18 failure recovery, D27 share preview, and D40 collapsed-pane safety were inspected independently; inherited CTA copy and composer drift found during review were corrected before acceptance.

Product decisions preserved by the compositions:

- Free, Pro, and Max share one Project/Task/Output interaction grammar.
- Professional depth is progressive disclosure inside Studio, not a second freelancer product.
- Upgrade and recovery preserve the exact Task context, draft, evidence, and intended action.
- Verification, recovery, ownership, billing consequences, and destructive effects remain visible before authority is exercised.
- Gamification is optional and tied to verified outcomes, never spend or meaningless activity.
- Third-party provider/integration names remain supported; their logos remain deliberately deferred.

Next gate: build and validate the D41–D93 Max/professional desktop composition set with explicit client, source, scope, authority, cost, delivery, and recovery boundaries. The goal remains active.

---

## STUDIO-CP-006 — Max/professional desktop gate closed

**Date:** 2026-07-23

Studio Phase 4 is closed. The canonical Figma file now contains 53 accepted Max/professional desktop compositions, D41–D93, in seven sections: `32:1238`, `34:1518`, `35:1798`, `36:2078`, `37:2358`, `38:2638`, and `39:2918`.

The composition set covers Professional Mode entry and continuity, zero-information client creation, client isolation/switching/offboarding, GitHub, Bitbucket Cloud, local hands, dirty/multi-repository source safety, repository map and cited context, requirement intake, estimates and assumptions, ScopeBaseline/ChangeRequest, plan/authority/provider/model/BYOK/budget/time/local-cloud routing, implementation/diff/review/autofix, test topology/generation/suite validation/integration CI/on-demand failure evidence, Preview/environments/deploy/post-deploy/rollback, client review/feedback/acceptance/handoff, project documents/templates/memory sanitisation, estimate-versus-actual attribution, cap/BYOK failure, scoped collaborators, privacy/retention, secret/local-data denial, deletion, offboarding mismatch recovery, and Team/Enterprise graduation.

The professional UX keeps client identity, source identity, active scope, authority, and cost boundary visible wherever an action can affect the wrong client or environment. Max and BYOK are not conflated: Max is the Studio tier, while BYOK is a separately reviewed key-source route. Provider/model and integration names remain visible without their deferred logos.

The scripted gate reports exactly 53 frames and an exact D41–D93 set, with zero numbering gaps/duplicates, viewport or pane drift, font drift, Max-tier errors, placeholder/internal-tier copy, off-canvas text, or missing DecisionCard/OutputPanel contracts. Seven fresh high-resolution section screenshots were downloaded and visually inspected; no clipping, overlap, center-dominance regression, or boundary ambiguity was accepted.

Next gate: explicit tablet and mobile designs. These will use rail/sheet and single-surface interaction models rather than scaled desktop frames. The goal remains active.

---

## STUDIO-CP-007 — Responsive composition gate closed

**Date:** 2026-07-23

Studio Phase 5 is closed with 20 accepted tablet compositions and 30 accepted mobile compositions. The canonical responsive sections are tablet `44:3093`, `47:3427`, `48:3763` and mobile `49:3931`, `52:4306`, `57:4661`.

Tablet uses a 64 px official navigation rail and a dominant single Task with contextual Output. Mobile uses an explicit 390×844 single-surface model with a 64 px top bar, 603 px Task, 109 px composer, and 68 px official bottom navigation. It supports review, decisions, evidence, small conversational changes, publishing, rollback, and recovery without claiming full desktop source editing.

Fresh screenshot inspection found and repaired three real clipping defects in Professional entry, scope approval, and scoped collaborator invitation. It also restored safe bottom spacing in two dense decision screens and removed six-group Account summary collisions.

The final structural gate reports exactly T01–T20 and M01–M30, explicit Dark mode, JetBrains Mono, official navigation variants, 44 px external actions, no mobile content overflow, no placeholder/internal-tier/provider-logo copy, and exactly six non-colliding job-based Account groups.

Studio now has 143 accepted compositions: 93 desktop, 20 tablet, and 30 mobile. Phase 6 remains active for prototype wiring, whole-file adversarial audit and fixes, 173-row audited traceability, and the coding-agent handoff.

---

## STUDIO-CP-009 — Studio product and UX closure complete

**Date:** 2026-07-23

Studio Phase 6 and the complete product/UX closure are finished in the canonical Figma file `LfEwxST9vagBMNJdVWn23L`.

The playable prototype contains 17 named flow starts, 72 interactive reaction nodes, 72 navigation edges and 71 distinct screen transitions. All 19 required core and recovery route assertions pass, with zero broken destinations. It covers idea-to-rollback, visual editing/version recovery, data/API/secret/checks, limit-upgrade-same-Task resume, the complete professional client-delivery path, and offline, stale, unavailable-source, failed-check, invalid-suite, failed-CI, degraded-deploy, denied, expired, destructive-cancel and client-mismatch recovery.

A fresh audit captured and inspected all 18 accepted screen sections. The final structural pass confirms exactly 143 roots — 93 desktop, 20 tablet and 30 mobile — with zero missing/duplicate/extra IDs, wrong dimensions, font drift, forbidden internal-tier/logo/placeholder copy, persistent shimmers, visible viewport overflow, broken destinations, missing required edges or undersized mobile action targets. The first bounds scan's 49 mobile reports were verified as clipped canonical-component internals; the second pass reports zero actionable visible bounds failures. D40 is confirmed as 48 px official left rail / 1344 px centre / 48 px official right rail. Unresolved P0/P1 findings are zero.

PRD set equality is exact: 81 unique casual `STU-*` IDs plus 92 unique professional `PRO-*` IDs equal 173 unique traceability rows, with no missing or extra ID. Every row is now Audited, contains exact Figma node evidence, and carries an explicit non-visual implementation contract. Automated validation found zero status, node-evidence or contract errors.

Final implementation records:

- `19-FIGMA-PHASE-6-PROTOTYPE-GRAPH.md`
- `20-STUDIO-ADVERSARIAL-UX-AUDIT.md`
- `21-STUDIO-CODING-AGENT-IMPLEMENTATION-HANDOFF.md`
- `22-STUDIO-FINAL-COMPLETION-PROOF.md`

The implementation handoff defines the planned Core, Service, Extension, UI and Analytics repository boundaries; canonical routes, domain/state/API contracts; integration-testing and generated-suite validation; automatic and on-demand CI; safety/redaction/client isolation; analytics; phases I0–I8; and deterministic `AT-<requirement-id>` release evidence.

Provider/integration logos remain deliberately deferred. Browser keyboard/screen-reader behavior, 200% zoom/localisation, production authorization/redaction, responsive state-machine parity and deterministic runtime effects remain implementation release gates. Static Figma evidence is not represented as production readiness.

The final package was synced to `/Users/navneetprabhakar/Documents/executespec/brainstorm/other-solutions/studio/closure-v1/` and verified byte-identical across 67 files: 23 numbered documents, the machine ledger, and 43 durable visual-evidence PNGs. The canonical audit was also mirrored to the requested Downloads and brainstorm audit locations and verified identical.

---

## STUDIO-CP-010 — Visual sharpness accepted; implementation I0 started

**Date:** 2026-07-23

The prior Studio closure was complete but visually weaker than the accepted Enterprise file. A fresh paired review found insufficient three-zone contrast, presentation-scale headings, floating 440 px desktop decisions, a centre composer stranded above unused canvas, sparse Output panes, split blue/green primary emphasis, and low-contrast rail icons.

The Figma file was mutated in place without changing route, feature, tier, object, or prototype identities. Desktop now uses stronger `#030F0F` / `#042222` / `#052827` zoning, 22 px product headings, 680 px decision zones, a bottom-anchored composer, and an 840 px contextual Output inspector. Tablet and mobile retain compact responsive compositions at 440 px and 350 px decision widths.

The first propagation exposed two real responsive defects: a 680 px tablet decision pushed the Output sheet off-canvas, and fixed desktop inspector internals overflowed the mobile 318 px content column. Both were repaired with breakpoint-specific component overrides before acceptance.

The final whole-file audit reports 143 screens, zero duplicate roots, zero missing fonts, zero broken instances across 1,575 instances, zero visible clipped descendants, 72 prototype reaction nodes, 72 reactions, and zero broken destinations. Six fresh desktop/tablet/mobile renders are stored with the closure package. The detailed evidence is in `23-STUDIO-VISUAL-SHARPNESS-PASS.md`.

Implementation Phase I0 is now in progress. The kickoff inventory confirms the current single Maven backend plus `frontend/` layout, identifies raw `PRO_PLUS` presentation leakage risks, freezes the Studio tier/key-source mapping, durable Task vocabulary, redacted event envelope, five dependency boundaries, and `AT-STU-*` / `AT-PRO-*` convention. Physical repository extraction, generated clients, compatibility code, and dependency-test enforcement remain pending I0 work. See `24-IMPLEMENTATION-PHASE-I0-KICKOFF.md`.

---

## STUDIO-CP-011 — Versioned Core contract and tier compatibility slice verified

**Date:** 2026-07-23

The first implementation slice of I0 is complete, but I0 itself remains open. A canonical Studio v1
JSON Schema now defines the customer-safe tier, billing-route, key-source, persona, durable task,
account-profile, and redacted event-envelope vocabulary. A deterministic generator produces the
TypeScript mirror and fails on drift; dependency-free Java Core enums are pinned to the same schema
by compiled parity tests.

The persistence boundary is explicit and deliberately fail-closed. Studio Max maps to persisted
`PRO_PLUS`. Persisted legacy `MAX` is rejected because it historically means Team, and `TEAM`,
`ENTERPRISE`, unknown, and null identities are also rejected as non-Studio inputs. BYOK projects as
Studio Pro with a separate BYOK billing route and own-key source; it is not a fourth presentation
tier. ArchUnit prevents Core and compatibility packages from acquiring service, UI, extension, or
analytics dependencies.

V340 seeds `studio.surface.enabled=false`. No route consumes it yet, so this slice does not expose a
customer surface, alter billing identity, or change Enterprise Console behavior.

Verification passed for backend compile, focused compatibility/schema/ArchUnit tests, Flyway
inventory and compatibility, Java Spotless, TypeScript generation drift, frontend formatting,
production build, and lint. Detailed evidence is in
`25-IMPLEMENTATION-PHASE-I0-CONTRACT-SLICE.md`.

The next slice is the versioned Studio service OpenAPI plus generated UI and split-extension
consumers, followed by the remaining five-boundary rules, `AT-*` CI enforcement, and a
Studio-scoped customer-visible tier-leak gate. The visible I1 route shell remains blocked until all
I0 exits pass.

---

## STUDIO-CP-012 — implementation attempt rolled back; UX-only scope restored

**Date:** 2026-07-23

The CP-011 implementation claim is superseded. The user clarified that the current session is only
for UX discussion and that coding must begin only after explicit UX acceptance. The phrase “go
ahead” did not provide sufficiently explicit authority to mutate the ExecuteSpec repository; the
assistant's interpretation was incorrect.

All repository mutations from the temporary contract slice were removed: Studio schema, Java Core
and compatibility code, generated frontend types, generator and package scripts, tests, migration,
and codebase documentation. No commit was created. Linked codemap and reason-log entries describing
that temporary implementation were also removed.

The UX, PRD, Figma, traceability, product-discovery, and handoff artefacts remain intact. Document 25
is retained only as a rollback audit and is not implementation authority.

Future boundary: continue UX/product review only. Do not modify application code, migrations,
configuration, CI, generated clients, package manifests, or repository structure until the user
explicitly states that the UX is accepted and authorises coding in a named repository/scope.

---

## STUDIO-CP-013 — PRD-adversarial review reopens Studio UX closure

**Date:** 2026-07-23

A fresh evidence-first audit captured and inspected all 18 current Figma sections spanning 93
desktop, 20 tablet, and 30 mobile compositions. The frames were reviewed against the 173 stable
Studio requirements, 20 journeys, Home/Project/Account surface model, Build/Data/Checks/Ship
lenses, inspectable Output, authority/effect/recovery, responsive, and accessibility contracts.

The previous zero-P0/P1 closure is rejected. It established screen inventory, dimensions, naming,
component consistency, and prototype structure, but treated 173 traceability rows mapped into 28
screen groups—with one generic authority/effect/recovery phrase—as evidence of interaction
completeness. It was not sufficient.

The new assessment records 3 P0, 8 P1, and 6 P2 findings. The three blockers are: desktop does not
visibly expose the persistent Project lenses or coherent Account hub; the right Output pane mostly
summarises context instead of exposing the real preview/diff/test/data/release artifact; and the
traceability/handoff package lacks requirement-specific interaction and decision evidence.

Strengths to retain are the single Studio shell, broad recovery model, upgrade continuity, client
isolation, Max/BYOK separation, explicit failure states, and the stronger mobile hierarchy with
honest desktop handoff. Full evidence and corrections are in
`26-STUDIO-PRD-ADVERSARIAL-DESIGN-REVIEW.md`.

Product/UX closure gates S4, S9, S10, and S11 are reopened. No application code was changed, and
the UX-only boundary remains in force.

---

## STUDIO-CP-014 — strict second pass identifies the missing product interaction

**Date:** 2026-07-23

All 18 current Studio Figma sections were captured again and the saved evidence was inspected under
a stricter standard: a named capability or count is not coverage unless the intended user can
complete the job, inspect the relevant object, understand the consequence, and reach a credible
success or recovery state.

The resulting assessment is 6 P0, 10 P1, and 7 P2 findings, superseding CP-013's provisional 3/8/6
count. The fundamental issue is that Studio currently behaves like an approval dossier around an
agent. It clearly describes what the agent plans, is doing, or claims to have produced, but it does
not yet design the working application Preview/canvas, direct manipulation, or inspectable Preview,
Changes, Data, Checks, Release, and Evidence objects required by the PRD.

The remaining P0 blockers are: incomplete Projects/Tasks/lenses/Account navigation; inconsistent
high-consequence authority fields; tablet/mobile designs that do not demonstrate the required
drawer, sheet, overlay, and Task/Output switching states; and 173-row traceability that still maps
through grouped screens and generic non-visual contracts rather than requirement-specific
interaction evidence.

The strongest material remains the safety and recovery model: pause/checkpoint/resume,
source/offline/dirty-state handling, limit continuity, client isolation, cost boundaries,
retention/deletion, and sanitized memory. Mobile action hierarchy is also substantially stronger
than desktop.

The authoritative second-pass report is `27-STUDIO-STRICT-PRD-AUDIT-SECOND-PASS.md`. No Figma or
application-code mutation occurred. Product/UX closure remains open and coding remains unauthorised.

---

## STUDIO-CP-015 — strict audit evidence plotted in Figma

**Date:** 2026-07-24

The 18 fresh strict-pass captures and their concise health notes are now plotted on a separate
Figma page so the full evidence set can be reviewed spatially without modifying the accepted
Studio product screens. Page `89:2` contains section `89:3`, with cards 01–15 in the first row at
200 px spacing and cards 16–18 in a second row 600 px below.

Programmatic validation confirmed 18/18 screenshot rectangles have unique image fills using `FIT`.
Whole-board and row-level renders were then visually inspected. The board preserves the current
6 P0, 10 P1, and 7 P2 verdict; it improves reviewability but closes none of the product gaps.

Detailed evidence: `28-FIGMA-STRICT-AUDIT-BOARD.md`. The UX-only boundary remains in force and no
ExecuteSpec application code was changed.
