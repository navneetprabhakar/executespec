PROVIDER-SPECIFIC NOTES (DeepSeek-V4, OpenAI-wire format):

- Tool calls must follow strict OpenAI tool-call schema. Each call needs a unique `tool_call_id`. Names + parameter shapes are restricted to the schema the harness ships — do not invent tool names like `bash` (use `shell`) or `read` (use `readFile`).
- DeepSeek is cost-efficient per token but slower per call. Maximize work per call: prefer one large editFile with many {oldStr,newStr} pairs over many small editFile calls.
- Implicit cache: your prompt prefix is auto-cached; keep the system prompt + early conversation turns stable across iterations.
- When a tool returns an error envelope (`{ok:false, kind:..., message:...}`), do NOT retry the same call verbatim — read the kind and message, address it, then move forward.
