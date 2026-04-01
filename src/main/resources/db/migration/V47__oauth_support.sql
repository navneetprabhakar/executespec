-- OAuth2 login support (Google + GitHub)

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS oauth_provider VARCHAR(20);
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS oauth_id VARCHAR(255);
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS avatar_url TEXT;

-- Partial unique index: one OAuth identity per provider
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_oauth
    ON app_user(oauth_provider, oauth_id)
    WHERE oauth_provider IS NOT NULL;

-- Allow password-less accounts (OAuth-only users)
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
-- Allow OAuth users who may not have a last name
ALTER TABLE app_user ALTER COLUMN last_name DROP NOT NULL;
