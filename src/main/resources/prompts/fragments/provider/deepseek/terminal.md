PROVIDER-SPECIFIC TERMINAL NOTES (DeepSeek):
- EMIT A TOOL CALL ON EVERY TURN. Do not answer with reasoning-only prose; a no-tool turn makes no terminal progress.
- Use only tool names present in the current schema. Prefer `shell`/`bash` for terminal work; do not invent short names like `read`.
- When a command returns an error envelope, inspect `kind`, `stdout`, and `stderr`, then change the command or file. Do not retry the same command unchanged.
- Before completing, run a verifier-shaped command or inspect the exact requested /app deliverable.
