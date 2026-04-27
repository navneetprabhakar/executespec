/**
 * R-160 — v3 frontend event names + capture helpers.
 *
 * Mirrors the backend EventName enum entries prefixed with V3_*. Each
 * fires a single PostHog event tagged with `surface: 'v3'` so the
 * conversion lift can be A/B'd cleanly against v2.
 *
 * These are CLIENT-SIDE captures — the backend EventTrackingService
 * still owns the authoritative funnel ledger (user_activation_event)
 * and dedupes first-time firings. The frontend captures are useful
 * for properties the backend can't observe (which composer chip was
 * clicked, mobile vs desktop, time-on-page before submit, etc.).
 */

import { capture } from '@/lib/analytics';

/** Lower-case identifiers — match PostHog convention. The backend
 *  enum values stay SCREAMING_CASE (Java) but on the wire/PostHog
 *  side we settle on snake_case for readability. */
export const V3_EVENTS = {
  LANDING_TEXTAREA_SUBMIT: 'v3_landing_textarea_submit',
  FIRST_RUN_STARTED: 'v3_first_run_started',
  FIRST_RUN_READY: 'v3_first_run_ready',
  REPEAT_RUN_SUBMITTED: 'v3_repeat_run_submitted',
  TIER_UPGRADED: 'v3_tier_upgraded',
  TEAM_SEAT_ADDED: 'v3_team_seat_added',
  ENTERPRISE_INQUIRY_SUBMITTED: 'v3_enterprise_inquiry_submitted',
  TRUST_PAGE_PDF_DOWNLOADED: 'v3_trust_page_pdf_downloaded',

  // UI-only signals (no backend mirror) — useful for funnel-stage
  // diagnosis without polluting the EventName enum.
  COMPOSER_SAMPLE_CHIP_CLICKED: 'v3_composer_sample_chip_clicked',
  COMPOSER_QUICK_RUN_TOGGLED: 'v3_composer_quick_run_toggled',
  COMPOSER_ADVANCED_OPENED: 'v3_composer_advanced_opened',
  PROJECT_SWITCHER_OPENED: 'v3_project_switcher_opened',
  CMDK_OPENED: 'v3_cmdk_opened',
  CMDK_ACTION_RUN: 'v3_cmdk_action_run',
} as const;

export type V3EventName = (typeof V3_EVENTS)[keyof typeof V3_EVENTS];

/** Convenience wrapper that always tags `surface: 'v3'`. */
export function trackV3(
  event: V3EventName,
  props?: Record<string, string | number | boolean | null | undefined>
): void {
  capture(event, { surface: 'v3', ...(props ?? {}) });
}
