/**
 * R-105 — single source of truth for v3 vocabulary.
 *
 * Every user-visible string in the v3 surface MUST come from here.
 * The only acceptable bypass is fully dynamic content (a user-typed
 * project name, a server-supplied error message, etc.).
 *
 * The strict UX review caught five different words for "run"
 * (Run / Mission / Spec / Launch / Execution). v3 uses ONE word
 * for each concept, forever. The greenfield doc §2.6 is the
 * canonical decision table; this file is the runtime mirror.
 */

// ── The primitive: a single submit-to-result cycle ────────────────────────────
export const RUN_SINGULAR = 'Run';
export const RUN_PLURAL = 'Runs';
export const RUN_NEW = 'New run';
export const RUN_VERB = 'submit'; // "submit a run", never "launch a mission"

// ── Status labels (replaces 5 different chip implementations) ─────────────────
// Each maps to the backend pipeline_run.status enum but presents one
// canonical user-facing string.
export const STATUS_LABELS: Record<string, string> = {
  PENDING: 'Pending',
  PLANNING: 'Planning',
  AWAITING_APPROVAL: 'Plan ready',
  WORKING: 'Building',
  REVIEWING: 'Reviewing',
  COMPLETE: 'Ready',
  PARTIAL: 'Needs review',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled', // never "Blocked" — strict review R-006
  ABORTED: 'Cancelled',
};

// ── The headline verdict (replaces "Verified N/100" + "Shippable") ────────────
// Three discrete outcomes; nothing else. The 4 evidence chips
// (Build / Tests / Reviewer / Cost) live separately as <Pill>s.
export type V3Verdict = 'READY' | 'NEEDS_REVIEW' | 'FAILED';
export const VERDICT_LABELS: Record<V3Verdict, string> = {
  READY: 'Ready to merge',
  NEEDS_REVIEW: 'Needs review',
  FAILED: 'Build failed',
};

// ── The container ─────────────────────────────────────────────────────────────
export const PROJECT_SINGULAR = 'Project';
export const PROJECT_PLURAL = 'Projects';
export const PROJECT_NEW = 'New project';

// ── Provider display names — one casing forever ──────────────────────────────
// Strict review R-012: API enum stays ANTHROPIC/GEMINI/OPENAI; UI always
// shows these strings.
export const PROVIDER_DISPLAY: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  GEMINI: 'Gemini',
  OPENAI: 'OpenAI',
};

// ── Tier display names — match the new 3-rung ladder ─────────────────────────
// Free / Pro / Team visible by default. Enterprise is footer-only.
// Max is on the V141 deletion path; if a user is grandfathered the v3
// surface still labels them "Team" (tier_config row name change is
// pending; v3 never shows "Max" to a user).
export const TIER_DISPLAY: Record<string, string> = {
  FREE: 'Free',
  PRO: 'Pro',
  TEAM: 'Team',
  MAX: 'Team', // grandfathered MAX users see Team (per V141 path + greenfield §7.1)
  ENTERPRISE: 'Enterprise',
};

// ── Plan-gate verbs (replaces "AWAITING_APPROVAL" / "Production" / "Prototype") ─
export const PLAN_GATE_HEADER = 'Plan ready — Approve & ship';
export const PLAN_GATE_APPROVE_CTA = 'Approve & ship';
export const PLAN_GATE_ADJUST_CTA = 'Adjust plan';
export const PLAN_GATE_CANCEL_CTA = 'Cancel';

// ── Quick-run vs Plan-and-review (replaces PROTOTYPE/PRODUCTION enum) ─────────
// User-facing words; backend mapping in code where used.
export const RUN_MODE_PLAN_REVIEW = 'Plan & review';
export const RUN_MODE_QUICK = 'Quick run';

// ── Cost copy (plain English; no "soft budget" / "hard cap" jargon) ───────────
export const COST_PREFIX = 'About';     // "About $0.40"
export const TIME_PREFIX = '~';         // "~3 min"

// ── Misc ──────────────────────────────────────────────────────────────────────
export const SAMPLE_PROMPT_LABEL = 'Try a prompt';
export const TEMPLATE_LABEL = 'Saved spec';
export const ARCHITECTURE_LABEL = 'Code map';
