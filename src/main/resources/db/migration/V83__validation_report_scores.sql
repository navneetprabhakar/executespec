-- V83 — Pilot R6. Break the existing single-dimension accuracy_score (V74) into
-- six explicit dimensions so trend charts + per-category thresholds work. The
-- existing accuracy_score column stays as the "overall" (weighted) number.

ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS code_quality_score      INT,
    ADD COLUMN IF NOT EXISTS test_coverage_score     INT,
    ADD COLUMN IF NOT EXISTS architecture_score      INT,
    ADD COLUMN IF NOT EXISTS security_score          INT,
    ADD COLUMN IF NOT EXISTS performance_score       INT,
    ADD COLUMN IF NOT EXISTS design_compliance_score INT;
