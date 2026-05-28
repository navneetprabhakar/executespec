PROVIDER-SPECIFIC NOTES (Gemini / 3.5-Flash):

- Your implicit prompt cache only hits when the prefix is stable AND ≥1024 tokens. Re-reading the same file in a task changes the prefix and forces a cache miss — DON'T re-read files you've already read this task.
- The thought-signature round-trip is handled by our client; do not emit thought blocks in your text response — only emit tool calls and (when done) a brief completeTask summary.
- DON'T re-issue the same tool call hoping for a different result. If a build failed, read the error message and address the actual root cause before retrying.
- One file per writeFile is fine, but batch logical edits via editFile's array argument (multiple {oldStr,newStr} pairs per call) instead of one editFile per change.
