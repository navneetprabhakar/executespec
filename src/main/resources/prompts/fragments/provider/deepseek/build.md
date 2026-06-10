PROVIDER-SPECIFIC BUILD NOTES (DeepSeek, OpenAI-wire format):

- EMIT A TOOL CALL ON EVERY TURN — never reply with reasoning/analysis text and NO tool call. A turn with no tool call makes zero progress.
- ACT, DON'T JUST EXPLORE. Once you have localized the likely file, make the smallest concrete edit instead of continuing to read.
- Tool calls must follow the strict tool schema the harness provides. Do not invent tool names.
- DeepSeek is cost-efficient per token but slower per call. Maximize work per call: prefer one large editFile with many {oldStr,newStr} pairs over many small editFile calls.
- When a tool returns an error envelope (`{ok:false, kind:..., message:...}`), do NOT retry the same call verbatim — read the kind and message, address it, then move forward.
