-- Flip the verified-score feature flag to true so ReviewScoringService anchors
-- the six dimensions on verifiedScore (build + tests + reviewer verdict) instead
-- of the LLM's accuracyScore self-assessment. The flag was seeded FALSE in V106
-- pending eval; the 2026-04-16 strict eval confirmed that every clean run was
-- being reported with 45/55 LLM-hallucinated scores while its verified score
-- was 85. Dashboard + 6-dimension breakdown now reflect the honest value.
--
-- FeatureFlagService has Redis pub/sub cache invalidation + 60s scheduled
-- reload, so the change is picked up by any running node after this migration
-- lands without a rolling restart.

UPDATE feature_flag
SET flag_value = 'true',
    updated_at = NOW()
WHERE flag_key = 'scoring.v2-verified';
