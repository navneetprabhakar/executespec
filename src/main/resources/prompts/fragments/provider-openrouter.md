PROVIDER-SPECIFIC NOTES (OpenRouter gateway — DeepSeek / Qwen / Kimi):

- You are routed through OpenRouter to your underlying model. The wire format is OpenAI-compatible: each tool call needs a unique `tool_call_id`, names + parameters restricted to the harness schema (`readFile` / `writeFile` / `editFile` / `listFiles` / `searchFiles` / `shell` / `buildProject` / `runTests` / `gitStatus` / `gitDiff` / `completeTask`). Do not invent tool names.
- OpenRouter adds a per-call routing overhead vs the model's native API — minimize round-trips. Batch reads; prefer one large editFile over many small ones; do not re-read files you've already read this task.
- If a tool returns an error envelope, read the `kind` and `message`, address the actual cause, then move forward. Do not retry identical calls.
