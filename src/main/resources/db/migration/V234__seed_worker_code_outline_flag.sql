-- =====================================================================
-- V234: Seed worker.code-outline.enabled = false (DEFAULT OFF).
--
-- W3.1 (vix-leverage, external-audit P2) — the `readCodeOutline` worker tool
-- (tool/ReadCodeOutlineTool) returns a read-only structural outline of a Java
-- source file: package, imports, and each type's methods/constructors/fields
-- with signatures + line ranges (bodies omitted), so the worker can LOCALIZE the
-- symbol it needs in a large file and then readFile only the relevant line range
-- instead of the whole file. JavaParser-backed; Java (.java) only first cut
-- (TS/JS/Py/Go need tree-sitter, not yet a dependency).
--
-- Schema-safety (RL-2026-05-061): ReadCodeOutlineTool is a FlagGatedTool — when
-- OFF (this seed row), ToolRegistry.bind*/isToolBound does NOT bind it; it is
-- ABSENT from the model's tool schema (not bound-but-inert), so a new tool can't
-- cause reasoning-only stalls on schema-sensitive providers (DeepSeek). Worker
-- tool set is byte-identical until this flag is flipped.
-- =====================================================================
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.code-outline.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'readCodeOutline worker tool (W3.1, vix-leverage P2) — read-only JavaParser structural outline of a .java file (types/methods/fields + line ranges) so the worker localizes without reading whole files. FlagGatedTool: when OFF (default) it is NOT bound — absent from the model schema (RL-2026-05-061 schema-safety).'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;
