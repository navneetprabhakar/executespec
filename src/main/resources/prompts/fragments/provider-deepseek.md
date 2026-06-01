PROVIDER-SPECIFIC NOTES (DeepSeek-V4, OpenAI-wire format):

EMIT A TOOL CALL ON EVERY TURN — never reply with reasoning/analysis text and NO tool call. A turn with no tool call makes ZERO progress; after a few such turns the run is force-terminated at zero edits and scored zero (this is the single most common way native-DeepSeek runs fail here). Keep your thinking brief and ALWAYS end the turn with a concrete tool call (readFile / editFile / searchFiles / …). Once you've read the relevant file, you almost always have enough — go straight to editFile instead of explaining what you would do.

ACT, DON'T JUST EXPLORE — this is your #1 failure mode (40% of mixed10 runs produced ZERO edits):
- Localize fast. Within a handful of iterations you should have found the bug. The MOMENT you've located the likely line, call editFile — do NOT keep reading. Reading without editing is NOT progress.
- You are force-terminated and scored ZERO if you reach the iteration limit with zero edits. A best-guess minimal edit ALWAYS beats no edit. NEVER call completeTask with zero editFile calls.
- Unsure between two spots? Edit the more likely one and move on — a wrong-but-present patch can be refined; an absent patch is an automatic fail.

EXISTING-CODE FIX DISCIPLINE — each of these cost a real resolved-run on mixed10:
- NEVER edit build/dependency/config files (pyproject.toml, setup.py, setup.cfg, requirements*.txt, tox.ini, .github/) to fix a bug — altering them breaks the test environment and FAILS the run even when your source fix is correct (this nullified an otherwise-correct astropy fix). Patch source code only.
- A reproduction script lives in /tmp (e.g. /tmp/repro.py), NEVER inside the repo. A diff containing a repro file — or ONLY a repro with no real source fix — is an automatic failure.
- If you add a helper function/method, you MUST call it at the actual buggy call-site in the SAME edit (grep the attribute/method to find where the bug fires). A defined-but-unused helper fixes nothing.
- When you change a shared structure (a header tuple, a format string, a column set), update EVERY consumer of it in the same edit — a half-applied change fails the test.

- Tool calls must follow strict OpenAI tool-call schema. Each call needs a unique `tool_call_id`. Names + parameter shapes are restricted to the schema the harness ships — do not invent tool names like `bash` (use `shell`) or `read` (use `readFile`).
- DeepSeek is cost-efficient per token but slower per call. Maximize work per call: prefer one large editFile with many {oldStr,newStr} pairs over many small editFile calls.
- Implicit cache: your prompt prefix is auto-cached; keep the system prompt + early conversation turns stable across iterations.
- When a tool returns an error envelope (`{ok:false, kind:..., message:...}`), do NOT retry the same call verbatim — read the kind and message, address it, then move forward.
