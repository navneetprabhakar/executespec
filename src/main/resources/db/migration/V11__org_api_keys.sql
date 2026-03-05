-- V11: Add AI provider API key columns to the org table.
-- These are org-wide keys used by all members who haven't set a personal key.
-- Resolution order: user key → org key → system key (application.yml).
ALTER TABLE org
    ADD COLUMN anthropic_api_key TEXT DEFAULT NULL,
    ADD COLUMN openai_api_key    TEXT DEFAULT NULL,
    ADD COLUMN gemini_api_key    TEXT DEFAULT NULL;
