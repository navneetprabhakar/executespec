MODULE SYSTEM (ESM vs CJS) — MANDATORY:
Check package.json for "type": "module".
- If present: use import/export syntax. Never use require() for local files.
- If missing: use CommonJS (require() / module.exports).
Mixing them causes runtime errors.
