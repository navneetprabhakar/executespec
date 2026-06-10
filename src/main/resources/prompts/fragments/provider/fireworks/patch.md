PROVIDER-SPECIFIC PATCH NOTES (Fireworks gateway):

- You are routed through Fireworks to an OpenAI-compatible model endpoint. Follow the exact tool schema the harness provides and do not invent tool names.
- Keep patch loops compact: localize, edit, verify. Batch reads and edits because aggregator round-trips dominate latency.
- Existing-code bug fixes need a real source patch, not only reproduction scaffolding. Put scratch repros in /tmp, never in the repo diff.
- If you add or change a helper, wire it into the real failing call path in the same edit.
- If a tool returns an error envelope, read the `kind` and `message`, address the actual cause, then move forward. Do not retry identical calls.
