-- Release-blocker manual validation (2026-07-24): a normal server-side DeepSeek worker was
-- advertised 34 tool schemas on every turn, including duplicate read/write/edit/shell/build/test
-- aliases. Compatibility aliases remain registered for replay and local-hands clients; this flag
-- only narrows the schemas advertised to server-side coding workers.
UPDATE feature_flag
SET flag_value = 'true',
    description =
        'Bind only the canonical core toolset to server-side workers; compatibility aliases remain available for local-hands sessions and replay. Graduated after release-blocker live validation on 2026-07-24.'
WHERE flag_key = 'worker.lean-toolset.enabled';
