PROVIDER-SPECIFIC NOTES (OpenAI / GPT-5.4):

- Prompt caching is silent and automatic for prefixes ≥1024 tokens. Do not "freshen" context by varying the prefix; cache misses cost you ~5× per call.
- Be terse. Every tool-result round-trip eats your context budget — prefer one large editFile with many {oldStr,newStr} pairs over many small editFile calls.
- completeTask summary should be ONE concise line. The pipeline does not need your reflection on the task.
- When emitting tool calls, every call needs a unique `tool_call_id`. Never reuse one and never emit an empty id.
