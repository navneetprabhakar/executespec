PROVIDER-SPECIFIC PATCH NOTES (OpenAI / GPT):

- Terseness is for simple bugs only. When the bug spans multiple interdependent components, invest the extra tool calls and write the complete multi-part fix.
- EXISTING-CODE FIX DISCIPLINE: patch SOURCE ONLY unless the issue explicitly requires build/config changes. Any repro goes in /tmp, never the repo. Integrate helpers at the real call-site. When you change a shared structure, update every consumer in the same edit.
- Do not edit visible tests to make your output pass. Hidden tests score behavior; visible tests are useful as repro/verification signals.
- Avoid needless imports. Do not add a module-level import for a name that is only resolved indirectly by framework or dispatcher naming.
- Prefer one large editFile with many {oldStr,newStr} pairs over many small editFile calls.
