-- Credential-security gate (2026-07-17): the versioned encryption envelope
-- (enc:v1:<keyVersion>:<Base64(IV||ciphertext+tag)>) is materially longer than the raw credential.
-- A ~164-char OpenAI key becomes ~265 chars enveloped, overflowing VARCHAR(255) — both the startup
-- KeyMigrationRunner backfill AND every EncryptedValueConverter write of a long key would fail.
-- Widen every credential column still sized for raw values to TEXT. Columns already TEXT
-- (github_pat, deepseek/fireworks/openrouter keys) and the 512-wide bitbucket app password are
-- already envelope-safe and left unchanged.
ALTER TABLE app_user
    ALTER COLUMN openai_api_key TYPE TEXT,
    ALTER COLUMN anthropic_api_key TYPE TEXT,
    ALTER COLUMN gemini_api_key TYPE TEXT,
    ALTER COLUMN jira_api_token TYPE TEXT,
    ALTER COLUMN confluence_api_token TYPE TEXT;

ALTER TABLE org
    ALTER COLUMN openai_api_key TYPE TEXT,
    ALTER COLUMN anthropic_api_key TYPE TEXT,
    ALTER COLUMN gemini_api_key TYPE TEXT,
    ALTER COLUMN jira_api_token TYPE TEXT,
    ALTER COLUMN confluence_api_token TYPE TEXT;
