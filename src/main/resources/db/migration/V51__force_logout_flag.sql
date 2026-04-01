-- Force logout feature flag: when enabled, stamps force_logout_epoch to invalidate all JWTs
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('force_logout', 'false', 'BOOLEAN', 'PLATFORM', 'When enabled, invalidates all existing JWTs (forces re-login)');
