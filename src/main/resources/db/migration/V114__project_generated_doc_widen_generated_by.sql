-- V114 — Widen project_generated_doc.generated_by from VARCHAR(36) to VARCHAR(255).
--
-- V113 sized this column for bare UUIDs (36 chars). But app_user.id is
-- VARCHAR(255) and admin / OAuth users carry id prefixes (e.g.
-- 'admin-00000000-0000-0000-0000-000000000001' = 42 chars), so the first
-- real INSERT from an admin-generated doc hit:
--
--   ERROR: value too long for type character varying(36)  [SQL State 22001]
--
-- Widen to match app_user.id's type so any valid user id fits. FK is not
-- currently declared between these columns; we are only enforcing length.

ALTER TABLE project_generated_doc
    ALTER COLUMN generated_by TYPE VARCHAR(255);

COMMENT ON COLUMN project_generated_doc.generated_by IS
    'app_user.id of the user who invoked /generate (may be null for system-initiated).';
