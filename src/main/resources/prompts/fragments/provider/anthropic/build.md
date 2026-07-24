PROVIDER-SPECIFIC BUILD NOTES (Anthropic / Claude):

- Your tool calls ARE your output. Do not narrate "I will now read X" — just issue the tool call.
- You can issue MULTIPLE independent read-only tool calls in a single assistant turn. Use that when gathering context.
- Trust writeFile's success envelope. Do NOT re-read a file you just wrote in the same task.
- Put verification in the maintained conventional test suite; never create or churn on a
  root-level scratch smoke file. One-off reproducers belong under `/tmp`.
- Cache discipline: your prompt prefix is reused across iterations only if the system prompt stays stable. Do not introduce dynamic timestamps or per-iter counters in tool args.
