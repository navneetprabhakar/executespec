-- V84 — Pilot R3. Persist per-repo codemaps and a project-wide architecture
-- doc so the planner (R4 assessment) and the dashboard can reason about the
-- codebase without re-scanning on every run.
--
-- Rules:
--   - project_code_map:  one row per (project, repo). Re-generation bumps version.
--   - project_architecture_doc: one row per project per generation. Keeps history.

CREATE TABLE IF NOT EXISTS project_code_map (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id VARCHAR(255) NOT NULL,
    repo_id UUID NOT NULL REFERENCES project_repo(id) ON DELETE CASCADE,
    version INT NOT NULL DEFAULT 1,
    content TEXT NOT NULL,
    generated_from_sha VARCHAR(40),
    generated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_code_map_project_id ON project_code_map(project_id);
CREATE INDEX IF NOT EXISTS idx_code_map_repo_id ON project_code_map(repo_id);

CREATE TABLE IF NOT EXISTS project_architecture_doc (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id VARCHAR(255) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    content TEXT NOT NULL,
    user_annotations TEXT,
    generated_from_shas JSONB,
    generated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_arch_doc_project_id ON project_architecture_doc(project_id);
CREATE INDEX IF NOT EXISTS idx_arch_doc_project_version ON project_architecture_doc(project_id, version DESC);
