-- Credential-envelope follow-up (2026-07-17).
--
-- pipeline_run snapshots SCM credentials for accepted asynchronous work. github_pat is already TEXT
-- (V208), but bitbucket_app_password remained VARCHAR(512). AES-GCM plus the versioned envelope can
-- exceed the raw secret's declared width, so make both account and run copies length-safe before the
-- startup KeyMigrationRunner rewrites legacy/plaintext rows.
ALTER TABLE app_user
    ALTER COLUMN bitbucket_app_password TYPE TEXT;

ALTER TABLE pipeline_run
    ALTER COLUMN bitbucket_app_password TYPE TEXT;
