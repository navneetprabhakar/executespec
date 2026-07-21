-- V308 — RR-1629 / RR-1653: contract detection storage corrections.
--
-- RR-1629: contract_version.inline_content was declared JSONB (V135:49) while every detector
-- writes the RAW file body into it. Proto / GraphQL SDL / OpenAPI-YAML / AsyncAPI-YAML are not
-- valid JSON, so the insert threw, was swallowed into null by ContractDetectionService, and
-- contract_version got ZERO rows for those formats — version history, the breaking-change
-- baseline and full-body contract injection were all silently dead. Contract bodies are opaque
-- source text and are never queried as JSON, so TEXT is the correct type.
--
-- `#>> '{}'` (rather than `::text`) is used for the cast so any JSON *string* scalar that did
-- persist is unwrapped to its raw value instead of keeping its JSON quoting; for the JSON objects
-- that actually persisted (openapi.json / *.schema.json) it is equivalent to `::text`.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'contract_version'
      AND column_name = 'inline_content'
      AND data_type = 'jsonb'
  ) THEN
    ALTER TABLE contract_version
      ALTER COLUMN inline_content TYPE TEXT USING inline_content #>> '{}';
  END IF;
END $$;

-- RR-1653: DetectorUtils.basenameWithoutExtension cut the contract name at the FIRST dot, so
-- `api.v1.proto` / `api.v2.proto` (and `payments.openapi.yaml` / `payments.internal.yaml`) both
-- collapsed onto one UNIQUE (repo_id, name) registry row, producing perpetual version flip-flop
-- and an oscillating breaking-change baseline. The Java helper now strips only the known
-- extension; this backfill re-derives existing names the same way so the corrected detector
-- updates the existing row instead of inserting a duplicate alongside a stale one.
--
-- Guarded: only rows whose derived name actually differs AND whose derived name is not already
-- taken by another contract in the same repo are renamed, so the UNIQUE (repo_id, name)
-- constraint cannot be violated. Rows left behind (a genuine post-collision pair) keep their
-- current names and are re-detected normally.
WITH derived AS (
  SELECT
    cr.id,
    cr.repo_id,
    regexp_replace(
      regexp_replace(
        regexp_replace(cr.detected_path, '^.*/', ''),
        '\.schema\.json$', ''),
      '\.(graphqls|graphql|proto|yaml|yml|json|gql)$', '') AS new_name
  FROM contract_registry cr
  WHERE cr.detected_path IS NOT NULL
    AND cr.detected_path <> ''
)
UPDATE contract_registry cr
SET name = d.new_name
FROM derived d
WHERE cr.id = d.id
  AND d.new_name <> ''
  AND d.new_name <> cr.name
  AND NOT EXISTS (
    SELECT 1 FROM contract_registry other
    WHERE other.repo_id = d.repo_id
      AND other.name = d.new_name
      AND other.id <> d.id
  );
