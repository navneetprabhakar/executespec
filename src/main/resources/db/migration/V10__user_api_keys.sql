-- V10: Per-user API keys for AI providers (OpenAI, Anthropic, Gemini)
--       and per-user JIRA / Confluence integration credentials.
--
-- All key columns store values as plaintext (same approach as github_pat).
-- Raw values are never returned in API responses — only boolean "has*" flags.

ALTER TABLE app_user
    -- AI provider keys
    ADD COLUMN openai_api_key        TEXT          DEFAULT NULL,
    ADD COLUMN anthropic_api_key     TEXT          DEFAULT NULL,
    ADD COLUMN gemini_api_key        TEXT          DEFAULT NULL,

    -- JIRA integration
    ADD COLUMN jira_base_url         TEXT          DEFAULT NULL,
    ADD COLUMN jira_email            VARCHAR(255)  DEFAULT NULL,
    ADD COLUMN jira_api_token        TEXT          DEFAULT NULL,

    -- Confluence integration
    ADD COLUMN confluence_base_url   TEXT          DEFAULT NULL,
    ADD COLUMN confluence_email      VARCHAR(255)  DEFAULT NULL,
    ADD COLUMN confluence_api_token  TEXT          DEFAULT NULL;
