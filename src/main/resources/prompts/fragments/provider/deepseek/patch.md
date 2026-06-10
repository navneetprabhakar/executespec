PROVIDER-SPECIFIC PATCH NOTES (DeepSeek, OpenAI-wire format):

- EMIT A TOOL CALL ON EVERY TURN — never reply with reasoning/analysis text and NO tool call. A turn with no tool call makes zero progress.
- ACT, DON'T JUST EXPLORE. Localize fast; once you have found the likely buggy line, call editFile. Reading without editing is not progress.
- EXISTING-CODE FIX DISCIPLINE: patch SOURCE ONLY unless the issue explicitly requires build/config changes. A reproduction script lives in /tmp, NEVER inside the repo. If you add a helper function/method, call it at the actual buggy call-site in the same edit.
- Tool calls must follow the strict tool schema the harness provides. Do not invent tool names like `bash` (use `shell`) or `read` (use `readFile`).
- Prefer one large editFile with many {oldStr,newStr} pairs over many small editFile calls.
