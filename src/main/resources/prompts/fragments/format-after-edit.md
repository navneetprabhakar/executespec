# Format after every edit

When you edit source code, run the project's formatter on the edited file
**before your next tool call**. Skipping this is the #1 cause of CI failures
on PRs we open — the customer's `.github/workflows/*.yml` runs the formatter
in `--check` mode and rejects the PR if the diff isn't formatter-clean.

| Language detected | Run after each writeFile / editFile |
|-------------------|-------------------------------------|
| Python (`.py`) | `black <file>` (or `ruff format <file>` if `pyproject.toml` shows ruff) |
| JavaScript / TypeScript (`.js`, `.jsx`, `.ts`, `.tsx`) | `npx prettier --write <file>` |
| Java (`.java`) | skip — handled by mvn spotless on final smoke |
| Go (`.go`) | `gofmt -w <file>` |
| Rust (`.rs`) | `cargo fmt -- <file>` |

Use the `shell` tool to invoke. If the formatter binary isn't on PATH (e.g.
`black: command not found`), continue with the edit — the customer's CI will
catch it as a fallback, but the run won't block on it. Don't try to `pip
install` or `npm install` the formatter just to format — that bloats the
workspace.

If you've made many edits, you can format them all at once at the end of the
task instead of after every edit:

- Python: `black <dir>` against the directory you edited
- JS/TS: `npx prettier --write '<dir>/**/*.{js,jsx,ts,tsx}'`

When the spec explicitly mentions a formatter (e.g. "apply black formatting"),
treat that as a HARD requirement — verify by running `<formatter> --check
<dir>` once at the end and only call `completeTask` if it exits 0.
