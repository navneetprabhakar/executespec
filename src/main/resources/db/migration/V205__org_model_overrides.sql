-- F3 (2026-05-22) — per-org model-matrix overrides (ENTERPRISE).
-- Sparse JSON map of "<provider>:<tier>" -> model id, e.g.
--   {"anthropic:planning":"claude-opus-4-6","openrouter:workhorse":"gpt-5.4-mini"}
-- Unset cells fall back to the application.yml model-matrix defaults.
-- Model ids are not secret, so this is plain TEXT (unlike the encrypted *_api_key columns).
ALTER TABLE org ADD COLUMN model_overrides_json TEXT DEFAULT NULL;
