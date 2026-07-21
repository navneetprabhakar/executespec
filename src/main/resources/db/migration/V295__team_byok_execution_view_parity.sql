-- V295 — F6 (founder decision 2026-07-18): TEAM and BYOK reach ENTERPRISE parity for the
-- Execution-View entitlement, which gates Project Chat, the IDE Execution View, and Project Shell
-- (all three consult tier_config.execution_view_enabled).
--
-- V144/V253 seeded execution_view_enabled = TRUE for ENTERPRISE only, so TEAM and BYOK customers
-- were denied Project Chat + Execution View with a "requires Team or Enterprise tier" message that
-- named their own tier — contradicting the collaboration/solo-Team-controls positioning of those
-- plans. Grant the entitlement to TEAM and BYOK; PRO / PRO_PLUS / FREE stay gated.
UPDATE tier_config
   SET execution_view_enabled = TRUE
 WHERE tier IN ('TEAM', 'BYOK');
