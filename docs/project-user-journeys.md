# Project User Journeys

> Project-scoped journeys. A project owns linked repositories, generated intelligence, uploaded docs, memory, activity, runs, reviews, and sharing settings.

## Journey 1 - Create Project And Link Repos

1. User creates a project with name, description, provider default, and optional GitHub owner.
2. Backend validates `CreateProjectRequest` and creates the project.
3. User links one or more repositories.
4. Project workspace services clone/index as needed.
5. User generates architecture.

Current accepted project providers:

- `ANTHROPIC`
- `OPENAI`
- `GEMINI` / legacy `GOOGLE`
- `DEEPSEEK`
- `OPENROUTER`
- `FIREWORKS`

## Journey 2 - Generate Project Intelligence

1. Generate codemap per repo.
2. Generate per-repo architecture.
3. Synthesize cross-repo architecture.
4. Persist versions.
5. Use staleness/diff endpoints to understand drift.

The project dashboard should reflect architecture status, repo health, recent runs, and PRs.

## Journey 3 - Upload Docs And Use RAG

1. Upload project documents.
2. Backend extracts text through Tika.
3. Chunks are embedded and stored in pgvector.
4. Planner and project chat can retrieve relevant chunks.

Generated EXPLANATION and SETUP docs are separate from uploaded docs.

## Journey 4 - Project Chat

1. User asks a project-scoped question.
2. Chat context includes project architecture and memory.
3. Agent can call tools to list/read generated docs, search uploaded docs, inspect repos/runs/PRs, and search code.
4. Suggestions remain advisory unless dispatched through guarded action endpoints.

## Journey 5 - Submit Project Run

1. User opens Compose from a project.
2. User selects linked repos or leaves default.
3. Run request includes `projectId` and optional `selectedRepoIds`.
4. Planner receives project memory/code intelligence/reference docs.
5. Execution runs in per-run workspace with selected repos.
6. Result and PRs are linked back to the project.

## Journey 6 - PR Review

1. User submits repo/PR number.
2. PR review agent reads diff.
3. Backend persists verdict and comments.
4. User can dispatch autofix for comments.
5. Autofix uses PATCH mode against the PR head branch.

## Journey 7 - Code Review

1. User submits whole-repo scan.
2. Static analysis and LLM triage produce issues.
3. Report is available in UI/API.
4. User can dispatch one PR per severity bucket.

## Journey 8 - Tech Debt

1. Project aggregates validation-report issues.
2. UI displays category/severity/file hotspots.
3. User dispatches category fix.
4. Backend submits PATCH run through the common finding submitter.

## Journey 9 - Sharing

Project sharing has two paths:

- Cross-org invite through `ProjectMember`.
- Public read-only share link with scope validation.

The org external-sharing flag gates the cross-org invite path, not public share links.

Once an external invitee accepts, the host project's surfaces work for them, not just the project shell: the Work tab lists the project's runs, the Code tab browses linked repository source (read through the host/linking user's repository credentials, so private repos resolve), and project Chat runs at the host project's entitlement. The invitee's personal billing tier is not changed; access falls away if the membership is removed. `PROJECT_VIEWER` stays read-only — write actions require write-level access.
