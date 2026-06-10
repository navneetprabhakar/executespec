PROVIDER-SPECIFIC BUILD NOTES (OpenRouter gateway):

- You are routed through OpenRouter to the underlying model. The wire format is OpenAI-compatible; follow the exact tool schema the harness provides and do not invent tool names.
- OpenRouter adds per-call routing overhead. Minimize round-trips: batch reads, prefer one large editFile over many small ones, and do not re-read files already read this task.
- If a tool returns an error envelope, read the `kind` and `message`, address the actual cause, then move forward. Do not retry identical calls.
