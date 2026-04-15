-- V97 — fix V96. The Scope enum is PLATFORM | ORG, not GLOBAL.
-- V96 inserted scope='GLOBAL' which breaks the FeatureFlagService loader at boot.
-- Per project policy we never modify an applied migration, so we patch here.

UPDATE feature_flag
   SET scope = 'PLATFORM'
 WHERE flag_key = 'project.runs.auto-push'
   AND scope = 'GLOBAL';
