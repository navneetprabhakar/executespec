-- Release containment gates remain deliberately OFF.
--
-- A strict container is not release-ready merely because lifecycle and dispatch are enabled:
-- the configured image must be immutable and must contain the toolchains exercised by generated
-- projects. Separately, the legacy generated-test runner uses Docker --network=host to reach a
-- live preview. It stays disabled until that path has controlled outbound routing.
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('sandbox.runtime.toolchain-readiness.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'Operator acknowledgement for a verified digest-pinned sandbox toolchain image. Keep OFF until the image and representative build/test matrix pass admission.'),
    ('testgen.live-preview-network.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'Temporary gate for live-preview generated-test execution. Keep OFF while the legacy Docker runner requires --network=host; enable only after controlled sandbox egress replaces host networking.')
ON CONFLICT (flag_key) DO NOTHING;
