-- =====================================================================
-- V229: Seed spec.chat.enabled = false (DEFAULT OFF).
--
-- RL-2026-05-052 — Phase 1 of the unified `spec` client (CLI + VS Code).
-- Gates the real POST /api/spec/chat agentic loop (SpecChatService): when the
-- flag is OFF the controller returns an explicit "not enabled" SSE `done`
-- event (the Phase-1-skeleton placeholder is removed); when ON it runs the
-- LlmRouter (LIGHTWEIGHT tier) manual tool-loop binding ProjectChatActionTools
-- (submit / approve / reject / cancel / list / report), stateless — only the
-- spec_chat_audit row is persisted, never chat content.
--
-- Default OFF so production is byte-identical until an operator flips it in the
-- SUPER_ADMIN feature-flag console (an unseeded flag already reads false in
-- FeatureFlagService.isEnabled; this row just makes it visible + toggleable).
-- =====================================================================
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('spec.chat.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'RL-2026-05-052 Phase 1 — enable the real POST /api/spec/chat agentic loop (SpecChatService: LlmRouter LIGHTWEIGHT manual tool-loop over ProjectChatActionTools, stateless/audit-only). Default OFF; when OFF the endpoint returns a not-enabled SSE done event.')
ON CONFLICT (flag_key) DO NOTHING;
