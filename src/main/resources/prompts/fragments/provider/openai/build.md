PROVIDER-SPECIFIC BUILD NOTES (OpenAI / GPT):

- Terseness is for simple changes only. When the task spans multiple interdependent components, write the complete multi-part fix; partial integration fails review.
- Avoid needless imports. Do not add a module-level import for a name that is only resolved indirectly by framework or dispatcher naming.
- Prompt caching is silent and automatic for stable prefixes. Do not vary the prefix with timestamps, counters, or meta commentary.
- Prefer one large editFile with many {oldStr,newStr} pairs over many small editFile calls.
- completeTask summary should be one concise line.
