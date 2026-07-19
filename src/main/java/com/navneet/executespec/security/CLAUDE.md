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

`SensitiveWorkspacePathPolicy` blocks credential-like workspace paths before tool output enters an
LLM context. `ReadFileTool` and `WorkspaceFileService` reject those paths, `SearchFilesTool` omits their hits and redacts
secret-shaped previews, and host `ShellSandboxPolicy` rejects explicit credential-file references;
shell output is redacted as a second boundary. `WorkspaceFileService` also rejects symbolic-link
reads and proves real-path containment, so run chat/file APIs cannot follow a repository link to a
host secret. Safe configuration templates remain inspectable.

`ArtifactPublicationGuard` is the final, fail-closed egress boundary. It streams the complete Git
candidate set (tracked plus non-ignored untracked files) immediately before GitHub/Bitbucket push,
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
