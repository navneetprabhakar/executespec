PROVIDER-SPECIFIC NOTES (Gemini / 3.5-Flash):

- READ-ONCE discipline: read every file you need ONCE, up front, in a single parallel batch, then localize and EDIT. Never re-read a file to "double-check" — your first read is authoritative, and a re-read invalidates the whole implicit-cache prefix (a measurable latency + cost hit that correlates with no-patch stalls). Don't loop back to re-read; commit the edit.
- EXISTING-CODE FIX DISCIPLINE: patch SOURCE ONLY — never edit build/config files (pyproject.toml, setup.py, requirements*.txt, tox.ini, .github/); they break the test environment even when your source fix is correct. Any repro goes in /tmp, never the repo. Integrate helpers at the real call-site (no dead helpers). When you change a shared structure, update every consumer in the same edit.

- Your implicit prompt cache only hits when the prefix is stable AND ≥1024 tokens. Re-reading the same file in a task changes the prefix and forces a cache miss — DON'T re-read files you've already read this task.
- The thought-signature round-trip is handled by our client; do not emit thought blocks in your text response — only emit tool calls and (when done) a brief completeTask summary.
- DON'T re-issue the same tool call hoping for a different result. If a build failed, read the error message and address the actual root cause before retrying.
- One file per writeFile is fine, but batch logical edits via editFile's array argument (multiple {oldStr,newStr} pairs per call) instead of one editFile per change.
