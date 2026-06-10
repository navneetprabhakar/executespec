PROVIDER-SPECIFIC PATCH NOTES (OpenRouter gateway):

- You are routed through OpenRouter to the underlying model. The wire format is OpenAI-compatible; follow the exact tool schema the harness provides and do not invent tool names.
- OpenRouter adds per-call routing overhead. Minimize round-trips: batch reads, prefer one large editFile over many small ones, and do not re-read files already read this task.
- Existing-code bug fixes need a real source patch, not only reproduction scaffolding. Put scratch repros in /tmp, never in the repo diff.
- If a tool returns an error envelope, read the `kind` and `message`, address the actual cause, then move forward. Do not retry identical calls.
