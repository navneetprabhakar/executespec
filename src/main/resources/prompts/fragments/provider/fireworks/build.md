PROVIDER-SPECIFIC BUILD NOTES (Fireworks gateway):

- You are routed through Fireworks to an OpenAI-compatible model endpoint. Follow the exact tool schema the harness provides and do not invent tool names.
- Fireworks is usually lower-latency than a generic aggregator from US regions, but every round-trip still matters. Batch independent reads and prefer one large editFile over many small edits.
- Do not re-read files already read in this task. Use the current evidence, make the smallest correct edit, then verify.
- If a tool returns an error envelope, read the `kind` and `message`, address the actual cause, then move forward. Do not retry identical calls.
