-- V237: Add explicit BYOK key lanes for DeepSeek-native and Fireworks.
--
-- The router already has DeepSeek and Fireworks dispatch paths. Without persisted key lanes,
-- OWN_KEYS/HYBRID resolution silently cannot supply customer keys for those transports and falls
-- back to platform keys. Keep the fields encrypted via the existing JPA converter.

ALTER TABLE org
    ADD COLUMN IF NOT EXISTS deepseek_api_key TEXT,
    ADD COLUMN IF NOT EXISTS fireworks_api_key TEXT;

ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS deepseek_api_key TEXT,
    ADD COLUMN IF NOT EXISTS fireworks_api_key TEXT;
