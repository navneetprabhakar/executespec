export type AnnouncementSeverity = 'info' | 'success' | 'warning';

export interface Announcement {
  /** Bump this whenever the message changes so a previously-dismissed banner re-appears. */
  id: string;
  enabled: boolean;
  message: string;
  severity?: AnnouncementSeverity;
  /** Optional call-to-action link. */
  link?: {label: string; href: string};
}

/**
 * Single source of truth for the top announcement banner.
 *
 * "UI component only for now" (RL-2026-06-051): the banner reads this config constant, so changing
 * the message is a one-line edit here. A later pass wires the publish/admin mechanism (a
 * SystemAnnouncement entity + SUPER_ADMIN UI, or a public string-config endpoint) so ops can
 * publish without a redeploy. Set `enabled: false` to hide it.
 */
export const ANNOUNCEMENT: Announcement = {
  id: '2026-06-models',
  enabled: true,
  severity: 'info',
  message:
    'New models in the picker — Claude Opus 4.8, plus Mistral, GLM 5.2, MiniMax & Kimi via OpenRouter and Fireworks (Enterprise).',
};
