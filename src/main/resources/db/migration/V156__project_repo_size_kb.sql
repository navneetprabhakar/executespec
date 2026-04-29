-- V156 — R-325 (D-125 #3 Q3 d): big-repo opt-in tracking.
--
-- Persists the GitHub-reported repo size (in KB) at attach time so a
-- downstream credit-surcharge calculation can bill runs that touch
-- oversized repos appropriately. NULL means "unknown" — either the
-- attach predates V156 or GitHub didn't return a size value (legacy
-- attach path, or a 5xx during attach).
--
-- The size is captured on EVERY attach (not only opt-in attaches) so
-- the surcharge calc can reason about the full population. The opt-in
-- branch is signalled separately via {@code attached_anyway} below.
ALTER TABLE project_repo
    ADD COLUMN IF NOT EXISTS size_kb BIGINT;

-- {@code attached_anyway = TRUE} means the attach went through despite
-- exceeding the {@code executespec.repo.max-size-kb} threshold (R-323
-- size gate). Triggers the per-run credit surcharge in EstimationService
-- once that wiring lands.
ALTER TABLE project_repo
    ADD COLUMN IF NOT EXISTS attached_anyway BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN project_repo.size_kb IS
    'R-325 — GitHub-reported repo size (KB) at attach time. NULL when
     the attach predates V156 or GitHub did not return a size.';

COMMENT ON COLUMN project_repo.attached_anyway IS
    'R-325 — TRUE when the attach bypassed the executespec.repo.max-size-kb
     gate via the opt-in flow. Drives a per-run credit surcharge.';
