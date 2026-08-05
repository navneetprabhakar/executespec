import type {PrSummary} from '@/types/project';

/** Server reason code for an UNKNOWN CI status caused by a missing repository webhook. */
export const WEBHOOK_NOT_INSTALLED = 'WEBHOOK_NOT_INSTALLED';

export type PrCiBadgeView = {label: string; kind: 'ok' | 'warn' | 'bad' | 'info'};

/**
 * CI rollup badge for a PR row. Returns null when the server sent no ciStatus at all (older
 * servers / pre-poll rows), so absent evidence renders nothing rather than a fake "unknown".
 */
export function prCiBadge(
  pr: Pick<PrSummary, 'ciStatus' | 'ciFailingCount'>,
): PrCiBadgeView | null {
  const status = (pr.ciStatus || '').toUpperCase();
  if (!status) return null;
  switch (status) {
    case 'SUCCESS':
      return {label: 'CI · passing', kind: 'ok'};
    case 'FAILURE':
      return {
        label: pr.ciFailingCount ? `CI · ${pr.ciFailingCount} failing` : 'CI · failing',
        kind: 'bad',
      };
    case 'PENDING':
      return {label: 'CI · running', kind: 'warn'};
    case 'NEUTRAL':
      return {label: 'CI · neutral', kind: 'info'};
    default:
      return {label: 'CI · unknown', kind: 'info'};
  }
}

export type WebhookCiNotice = {
  message: string;
  /** Repo to install the webhook on; null when the server did not identify the repository. */
  repoId: string | null;
};

/**
 * Explains an UNKNOWN CI status caused by a missing repository webhook and, when the server
 * identified the repository, enables the one-click install action. Null-safe: absent
 * `ciStatusReason` (older servers) or a real CI verdict returns null and the row renders
 * unchanged.
 */
export function webhookCiNotice(
  pr: Pick<PrSummary, 'ciStatus' | 'ciStatusReason' | 'repoId'>,
): WebhookCiNotice | null {
  if ((pr.ciStatusReason || '').toUpperCase() !== WEBHOOK_NOT_INSTALLED) return null;
  const status = (pr.ciStatus || 'UNKNOWN').toUpperCase();
  if (status !== 'UNKNOWN') return null;
  return {
    message: 'CI status unavailable — repository webhook not installed.',
    repoId: pr.repoId || null,
  };
}
