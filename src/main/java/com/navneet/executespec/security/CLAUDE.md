# `security/` — chat-facing sanitization contracts

`InputSanitizer` owns prompt-injection/abuse scanning and exposes `normalize` so chat ingress stores
the exact NFKC, zero-width-free text that was scanned. A missing resolver or null level fails safe
to HIGH. `GuardrailLevelResolver` also fails safe to HIGH for missing, invalid, or unreadable flag
state and emits both a WARN and `executespec.security.guardrail.resolution_failures`.

`ContextSanitizer` treats repository/document/web/local context as untrusted data, neutralizes
instruction-like content, and redacts provider keys, ExecuteSpec/GitHub PATs, AWS key ids, bearer
tokens, generic secrets/passwords, and private-key blocks from outputs. `sanitizeException`
returns a bounded, secret-free description; chat/tool/SSE paths must never expose a raw exception
message.

`SafeWorkspacePath` is the shared path-containment boundary for framework-owned filesystem access
into customer-controlled trees (cloned repositories, run workspaces, generated test scaffolds). It
enforces relative-only paths, rejects dot/empty segments, proves canonical-root containment, and
rejects a symbolic link in any existing component; a not-yet-existing leaf is permitted only under an
already-validated existing parent. `requireSafeRelativePosixPath` is the SCM-side variant for
repository-relative paths written through a provider API where there is no local root to
canonicalize against. Every failure is a refusal — the write, read or delete does not happen and is
not redirected. Callers: `integration/RunContextComposer`, `context/ContextStore` (including the
destructive run-end cleanup), `context/ContextAgent`, `context/ContextAgentScanner`,
`context/ContextEvidenceBundleWriter`, `context/RunJournalStore`, `codereview/CodeReviewService`,
`testgen/TestWorkerAgent`, and `folder/ExecutespecFolderWriter`. It duplicates the rules already in
`tool/PathSafety` and `deployment/WorkspaceFileService`; folding those two onto this helper is a
tracked follow-up owned by their respective lanes.

`SensitiveWorkspacePathPolicy` blocks credential-like workspace paths before tool output enters an
LLM context. `ReadFileTool` and `WorkspaceFileService` reject those paths, `SearchFilesTool` omits their hits and redacts
secret-shaped previews, and host `ShellSandboxPolicy` rejects explicit credential-file references;
shell output is redacted as a second boundary. `WorkspaceFileService` also rejects symbolic-link
reads and proves real-path containment, so run chat/file APIs cannot follow a repository link to a
host secret. Safe configuration templates remain inspectable. `WorkspaceFileService.readFileDiff`
applies the same policy plus `SafeWorkspacePath.requireSafeRelativePosixPath` before any head,
GitHub-fallback or `git show <baseSha>:<path>` read, so a credential file deleted from the head can
no longer be served back out of history as `FileDiff.base` (RR-859).

`ArtifactPublicationGuard` is the final, fail-closed egress boundary. It streams the complete Git
candidate set (tracked plus non-ignored untracked files) immediately before GitHub/Bitbucket push.
**RR-080:** a tracked entry is scanned from its INDEXED blob by object id through one long-lived
`git cat-file --batch` child, not from the worktree file, so a secret that was staged and then
overwritten or deleted on disk is still blocked; the worktree copy is scanned as well, because a
caller that stages after this gate publishes those bytes instead. **RR-081:** `ls-files -z` records
are parsed as raw bytes — the object id decides what is scanned and the path is only a bounded label,
so a non-UTF-8 tracked name cannot skip inspection, and an untracked entry whose name is undecodable
is refused (`UNDECODABLE_GIT_PATH`) rather than treated as absent. It also
scans every local or restored-GCS workspace ZIP member, and validates both newly generated and
historical project-export tarballs before download/publication. ZIP/TAR readers enforce entry-count,
per-entry and total-inflated limits, reject traversal and unsupported TAR entry types, and scan
member names plus streamed bytes. Git candidate enumeration is time- and output-bounded; gitlinks
scan their published path/metadata without recursively reading a submodule checkout. This closes
shell/local-hands and pre-existing-file bypasses around `WriteFileTool`/`EditFileTool`. Findings
expose only category and relative path; there is no request-controlled bypass.

Structured public/report responses scan their serialized JSON, while PDF/Markdown/archive downloads
scan the actual outbound bytes. Current Git publication candidates are covered; deleted credentials
reachable only through older local-only Git history are not recursively object-scanned.

## 2026-07-18 verification boundary

Publication/security focused tests pass 39/39 and the full backend regression passes 3,243/3,243
with zero failures/errors/skips. The local gate covers GitHub/Bitbucket current publication bytes,
legacy deployment pre-create, local/restored workspace ZIP, new/historical project-export TAR.GZ,
governed public/report DTOs, validation-report PDF/share compatibility, and authenticated Code Review
and generated Project Doc downloads.

Do not overstate this as deployed leak-proofing. A live seeded canary still must exercise each route.
Preview/container-image publication, process argv/logs, deleted historical Git objects, screenshots and
recordings (OCR/DLP), and outbound email bodies/attachments remain separate release controls. Those
residuals are explicit acceptance gates, not implicit coverage from `ArtifactPublicationGuard`.
