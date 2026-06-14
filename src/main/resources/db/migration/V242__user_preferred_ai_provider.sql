-- =====================================================================
-- V242: per-user preferred AI provider.
--
-- Common chat now spans web, CLI, and VS Code. Local-only provider defaults
-- made those surfaces drift: selecting DeepSeek in the CLI did not affect
-- web compose or VS Code, and vice versa. This column is the authenticated
-- user's cross-surface provider preference. Project aiProvider remains the
-- project default; this is the user's current default when a surface does
-- not send an explicit provider.
-- =====================================================================

ALTER TABLE app_user
  ADD COLUMN IF NOT EXISTS preferred_ai_provider VARCHAR(32);

COMMENT ON COLUMN app_user.preferred_ai_provider IS
  'User-level default LLM provider shared by web, CLI, and VS Code. Null falls back to project/system default.';
