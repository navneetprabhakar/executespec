PROVIDER-SPECIFIC NOTES (Anthropic / Claude):

- Patches are graded on correctness, NOT pedagogy. Do NOT author multiple reproduction or test scripts in the diff — one minimal repro in /tmp (out of the diff) + the actual fix + the project-convention test is enough. A 500-line patch that buries a 2-line fix in repro scaffolding fails review (this cost django-10924 on mixed10).
- EXISTING-CODE FIX DISCIPLINE: patch SOURCE ONLY — never edit build/config files (pyproject.toml, setup.py, requirements*.txt, tox.ini, .github/); they break the test environment even when your source fix is correct. Any repro goes in /tmp, never the repo. Integrate helpers at the real call-site (no dead helpers). When you change a shared structure, update every consumer in the same edit.

- Your tool calls ARE your output. Do not narrate "I will now read X" — just issue the readFile call. Skip preamble entirely.
- You can issue MULTIPLE independent tool calls in a single assistant turn. Use that — three readFile calls in parallel beats three turns each with one readFile.
- Trust writeFile's success envelope. Do NOT re-read a file you just wrote in the same task; it costs a round-trip and the content is exactly what you wrote.
- Cache discipline: your prompt prefix is reused across iterations only if the system prompt stays stable. Do not introduce dynamic timestamps or per-iter counters in your tool args.
