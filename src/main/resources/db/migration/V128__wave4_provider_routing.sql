-- Wave 4.3 — smart provider routing observability.
--
-- Records every auto-routing decision (which provider was picked, why) so
-- we can measure hit rate vs. manual overrides + inform the heuristic.
-- Stays empty when provider.smart-routing.enabled=false (default).

CREATE TABLE provider_recommendation_log (
    id              VARCHAR(64)  PRIMARY KEY,
    run_id          VARCHAR(64),
    user_id         VARCHAR(64)  NOT NULL,
    project_type    VARCHAR(64),
    runtime_surface VARCHAR(32),
    recommended     VARCHAR(16)  NOT NULL,  -- ANTHROPIC | OPENAI | GEMINI
    reason_code     VARCHAR(32),            -- PROJECT_TYPE_HEURISTIC | USER_HISTORY | BYOK_FORCED | CIRCUIT_FALLBACK
    user_accepted   BOOLEAN NOT NULL DEFAULT TRUE,  -- false = user overrode
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_prl_user ON provider_recommendation_log(user_id, created_at DESC);
CREATE INDEX idx_prl_recommended ON provider_recommendation_log(recommended, user_accepted);

-- Wave 4 feature flag seeds
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('pricing.persona-first', 'false', 'BOOLEAN', 'PLATFORM',
     'When true, serves the persona-first PlansPage variant (A/B treatment arm). Measure paid-conversion at 30d post-signup.'),
    ('provider.smart-routing.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'When true, provider=auto is accepted on run submission + ProviderRecommendationService picks a provider from project type + user history. Default OFF during Wave 4 opt-in.')
ON CONFLICT (flag_key) DO NOTHING;
