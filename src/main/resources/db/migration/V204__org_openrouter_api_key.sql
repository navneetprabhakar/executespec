-- F1/F2: per-org OpenRouter API key (Enterprise gateway).
-- Mirrors the V11 org-key columns (anthropic/openai/gemini); encrypted at rest via
-- EncryptedValueConverter (same as the other provider keys). Org-level only — there is
-- no user-level OpenRouter key. Only ENTERPRISE org admins can set it.
ALTER TABLE org
    ADD COLUMN open_router_api_key TEXT DEFAULT NULL;
