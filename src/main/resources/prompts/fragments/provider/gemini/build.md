PROVIDER-SPECIFIC BUILD NOTES (Gemini / Flash):

- READ-ONCE discipline: read every file you need once, in a compact batch, then localize and edit. Do not re-read files just to reassure yourself.
- Your implicit prompt cache only hits when the prefix is stable and large enough. Re-reading the same file in a task changes the prefix and forces a cache miss.
- The thought-signature round-trip is handled by our client; do not emit thought blocks in your text response — only emit tool calls and a brief completeTask summary when done.
- Don't re-issue the same tool call hoping for a different result. Read the error message and address the root cause before retrying.
- Batch logical edits via editFile's array argument instead of one editFile per small change.
