-- V142 — drop dead feature flags
--
-- These flag rows were seeded by earlier migrations but are never read by
-- FeatureFlagService.isEnabled() anywhere in the codebase:
--
--   codegen.react_native.enabled   (V118, 2026-04-19)
--     The React Native + Expo codegen shipped on dev/main and is now always-on:
--     PlanV2 / PlannerOutputUtils / SequentialExecutorV2 / ScaffoldController
--     dispatch on `projectType == "react-native-expo"` directly. The flag was
--     intended as a kill-switch but the gate was never wired.
--
--   pricing.persona-first          (V128, v2.6.1 Wave 4)
--     A/B treatment arm for the persona-first PlansPage variant. The variant
--     never shipped; the flag is unread.
--
-- Idempotent.

DELETE FROM feature_flag
 WHERE flag_key IN (
   'codegen.react_native.enabled',
   'pricing.persona-first'
 );
