import {useCallback, useEffect, useState} from 'react';

import * as api from '@/lib/api';
import type {PrSummary} from '@/types/project';
import {IdeBadge} from '@/ide/IdeKit';
import {prCiBadge, webhookCiNotice} from '@/ide/prCiUtils';

/**
 * Webhook-onboarding surfacing for a PR whose CI status is UNKNOWN because the repository
 * webhook is not installed. Renders the explanation plus a one-click install action with
 * pending/success/error feedback; refreshes the caller's PR data on success. Renders nothing
 * when the server did not report the `WEBHOOK_NOT_INSTALLED` reason (null-safe for older
 * servers).
 */
export function PrCiWebhookNotice({
  pr,
  onInstalled,
}: {
  pr: Pick<PrSummary, 'ciStatus' | 'ciStatusReason' | 'repoId'>;
  onInstalled?: () => void;
}) {
  const [phase, setPhase] = useState<'idle' | 'pending' | 'done' | 'error'>('idle');
  const [feedback, setFeedback] = useState('');
  const notice = webhookCiNotice(pr);
  if (!notice) return null;

  async function install(repoId: string) {
    if (phase === 'pending') return;
    setPhase('pending');
    setFeedback('');
    try {
      const res = await api.installRepoWebhook(repoId);
      if (res.installed || res.eligibilityState === 'ALREADY_INSTALLED') {
        setPhase('done');
        setFeedback('Webhook installed. CI status will update on the next poll.');
        onInstalled?.();
      } else {
        setPhase('error');
        setFeedback(res.message || res.remediation || res.reason || 'Webhook install failed.');
      }
    } catch (e: unknown) {
      setPhase('error');
      setFeedback(e instanceof Error ? e.message : 'Webhook install failed.');
    }
  }

  return (
    <div style={{marginTop: 8, fontSize: 12}}>
      <div style={{color: 'var(--ide-fg-muted)'}}>{notice.message}</div>
      {notice.repoId && phase !== 'done' && (
        <button
          className='ide-btn ghost'
          style={{marginTop: 6, padding: '4px 10px'}}
          disabled={phase === 'pending'}
          title='Installs a pull_request webhook so PR status + CI results update automatically'
          onClick={() => void install(notice.repoId as string)}
        >
          {phase === 'pending' ? 'Installing…' : 'Install webhook'}
        </button>
      )}
      {feedback && (
        <div
          role='status'
          style={{
            marginTop: 6,
            color: phase === 'error' ? 'var(--ide-red)' : 'var(--ide-green)',
          }}
        >
          {feedback}
        </div>
      )}
    </div>
  );
}

/**
 * The run's PR rows with CI evidence (from `GET /api/runs/{id}/prs`), including the webhook
 * onboarding notice for UNKNOWN-because-no-webhook rows. Renders nothing when the run has no
 * PR rows or the read fails — the surrounding PR tab already owns the primary PR affordances.
 */
export function RunPrCiPanel({runId}: {runId: string}) {
  const [prs, setPrs] = useState<PrSummary[]>([]);

  const load = useCallback(() => {
    api
      .getRunPrs(runId)
      .then((rows) => setPrs(rows || []))
      .catch(() => {});
  }, [runId]);

  useEffect(() => {
    load();
  }, [load]);

  if (prs.length === 0) return null;

  return (
    <div className='ide-card' style={{marginTop: 12}}>
      <div className='ide-h2' style={{marginTop: 0}}>
        Pull request status
      </div>
      {prs.map((pr, i) => {
        const ci = prCiBadge(pr);
        return (
          <div
            key={`${pr.repoFullName}#${pr.prNumber}`}
            style={{
              padding: '8px 0',
              borderTop: i > 0 ? '1px solid var(--ide-border)' : 'none',
            }}
          >
            <div style={{display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap'}}>
              <span className='ide-mono' style={{fontSize: 12}}>
                {pr.repoFullName}#{pr.prNumber}
              </span>
              <IdeBadge
                kind={pr.status === 'MERGED' ? 'ok' : pr.status === 'CLOSED' ? 'bad' : 'ok'}
              >
                {pr.status}
              </IdeBadge>
              {ci && <IdeBadge kind={ci.kind}>{ci.label}</IdeBadge>}
            </div>
            <PrCiWebhookNotice pr={pr} onInstalled={load} />
          </div>
        );
      })}
    </div>
  );
}
