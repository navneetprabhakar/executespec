PROVIDER-SPECIFIC PATCH NOTES (Gemini / Flash):

- READ-ONCE discipline: read every file you need once, in a compact batch, then localize and edit. Do not re-read files just to reassure yourself.
- EXISTING-CODE FIX DISCIPLINE: patch SOURCE ONLY unless the issue explicitly requires build/config changes. Any repro goes in /tmp, never the repo. Integrate helpers at the real call-site. When you change a shared structure, update every consumer in the same edit.
- The thought-signature round-trip is handled by our client; do not emit thought blocks in your text response — only emit tool calls and a brief completeTask summary when done.
- Don't re-issue the same tool call hoping for a different result. Read the error message and address the root cause before retrying.
- Batch logical edits via editFile's array argument instead of one editFile per small change.
