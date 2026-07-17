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
