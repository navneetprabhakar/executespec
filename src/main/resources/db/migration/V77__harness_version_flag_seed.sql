-- V77: Seed V2 rollout feature flags for installs where V76 ran before the flag seed
-- was added to it. Idempotent — does nothing if the rows already exist.
--
-- Anyone fresh-installing after V76 got the seeds from V76 itself; this migration
-- just covers the window between V76 landing and V77 landing.

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('harness.version',            'v1', 'STRING',  'PLATFORM',
     'Which harness produces new runs: v1 | v2 | v2-canary. Default v1 — V2 runs only when flipped.'),
    ('harness.v2.canary_percent',  '0',  'INTEGER', 'PLATFORM',
     'When harness.version=v2-canary, this percent (0..100) of runs route to V2; the rest stay on V1.')
ON CONFLICT (flag_key) DO NOTHING;
