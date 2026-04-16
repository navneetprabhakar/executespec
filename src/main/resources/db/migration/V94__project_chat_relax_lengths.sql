-- V94 — some users (notably SUPER_ADMIN seeds) have id/org_id values longer
-- than 36 chars, which caused INSERTs into project_chat_message to fail with
-- "value too long for type character varying(36)". Relax the identifier
-- columns to match app_user/project's effective lengths.
ALTER TABLE project_chat_message
    ALTER COLUMN id          TYPE VARCHAR(64),
    ALTER COLUMN project_id  TYPE VARCHAR(64),
    ALTER COLUMN org_id      TYPE VARCHAR(64),
    ALTER COLUMN user_id     TYPE VARCHAR(64);
