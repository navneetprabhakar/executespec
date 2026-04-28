-- V154 — R-322 (D-125 #2): mono-repo subdirectory marker.
--
-- Stores the project root inside a repo when the repo is a monorepo and
-- the customer signals which subdirectory the project lives in via a
-- '.executespec/root.yml' file ({@code root: <subdir>}). NULL = repo root,
-- which preserves today's behaviour for every existing row.
ALTER TABLE project_repo
    ADD COLUMN IF NOT EXISTS subdir VARCHAR(512);

COMMENT ON COLUMN project_repo.subdir IS
    'R-322 — when set, customer-declared subdirectory inside the repo
     that is the project root. Detected on attach + sync from the
     .executespec/root.yml marker file on the default branch.';
