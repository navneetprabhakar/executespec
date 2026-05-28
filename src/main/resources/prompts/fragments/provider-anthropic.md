PROVIDER-SPECIFIC NOTES (Anthropic / Claude):

- Your tool calls ARE your output. Do not narrate "I will now read X" — just issue the readFile call. Skip preamble entirely.
- You can issue MULTIPLE independent tool calls in a single assistant turn. Use that — three readFile calls in parallel beats three turns each with one readFile.
- Trust writeFile's success envelope. Do NOT re-read a file you just wrote in the same task; it costs a round-trip and the content is exactly what you wrote.
- Cache discipline: your prompt prefix is reused across iterations only if the system prompt stays stable. Do not introduce dynamic timestamps or per-iter counters in your tool args.
