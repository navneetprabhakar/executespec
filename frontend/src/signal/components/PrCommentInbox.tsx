import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Button} from '@/signal/components/Primitives';
import * as api from '@/lib/api';

/**
 * Inbound PR review-comment inbox (`pr_comment_inbox`) for a run's PR, with
 * dismiss + apply-as-fix. Surfaces the dark `getPrComments` /
 * `dismissPrComment` / `applyPrComment` endpoints that no screen reached.
 *
 * NB: the dismiss/apply path param is the inbox ROW id (`dto.id`), not the
 * GitHub `commentId` — the backend resolves it via `inboxRepo.findById(id)`.
 *
 * Shared by the Signal Result page + the IDE run detail; `variant` switches
 * the styling between the two chromes so each stays native.
 */
export function PrCommentInbox({runId, variant}: {runId: string; variant: 'signal' | 'ide'}) {
  const navigate = useNavigate();
  const [comments, setComments] = useState<api.PrCommentInboxDto[] | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let off = false;
    api
      .getPrComments(runId)
      .then((c) => {
        if (!off) setComments(c);
      })
      .catch(() => {
        if (!off) setComments([]);
      });
    return () => {
      off = true;
    };
  }, [runId]);

  async function dismiss(rowId: number) {
    setBusy(true);
    try {
      await api.dismissPrComment(runId, rowId);
      setComments(await api.getPrComments(runId));
    } catch {
      /* keep last state on failure */
    } finally {
      setBusy(false);
    }
  }
  async function fix(rowId: number) {
    setBusy(true);
    try {
      const r = await api.applyPrComment(runId, rowId);
      navigate(`/live/${r.applyRunId}`);
    } catch {
      setBusy(false);
    }
  }

  const pending = (comments ?? []).filter((c) => c.status === 'PENDING');
  if (comments === null || pending.length === 0) return null;

  const meta = (c: api.PrCommentInboxDto) =>
    `${c.author}${c.filePath ? ` · ${c.filePath}${c.lineNumber ? ':' + c.lineNumber : ''}` : ''}`;

  if (variant === 'ide') {
    return (
      <div className='ide-card' style={{marginTop: 12, padding: 0}}>
        <div className='ide-h2' style={{margin: '12px 16px 4px'}}>
          Review comments · {pending.length} pending
        </div>
        {pending.map((c) => (
          <div key={c.id} style={{borderTop: '1px solid var(--ide-border)', padding: '10px 16px'}}>
            <div style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>{meta(c)}</div>
            <div style={{fontSize: 13, margin: '4px 0', whiteSpace: 'pre-wrap'}}>
              {c.body.slice(0, 400)}
            </div>
            <div style={{display: 'flex', gap: 6}}>
              <button
                className='ide-btn'
                style={{padding: '4px 10px'}}
                disabled={busy}
                onClick={() => fix(c.id)}
              >
                Fix
              </button>
              <button
                className='ide-btn ghost'
                style={{padding: '4px 10px'}}
                disabled={busy}
                onClick={() => dismiss(c.id)}
              >
                Dismiss
              </button>
              <a
                className='ide-btn ghost'
                style={{padding: '4px 10px'}}
                href={c.githubUrl}
                target='_blank'
                rel='noreferrer'
              >
                View ↗
              </a>
            </div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div
      style={{
        marginTop: 12,
        border: '1px solid var(--signal-hairline)',
        borderRadius: 12,
        padding: 14,
        background: 'var(--signal-card-bg)',
      }}
    >
      <div
        style={{
          fontSize: 12,
          fontWeight: 700,
          textTransform: 'uppercase',
          letterSpacing: '0.08em',
          color: 'var(--signal-text-mute)',
          marginBottom: 6,
        }}
      >
        Review comments · {pending.length} pending
      </div>
      {pending.map((c) => (
        <div key={c.id} style={{borderTop: '1px solid var(--signal-hairline)', padding: '10px 0'}}>
          <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>{meta(c)}</div>
          <div
            style={{
              fontSize: 13.5,
              margin: '4px 0',
              whiteSpace: 'pre-wrap',
              color: 'var(--signal-ink)',
            }}
          >
            {c.body.slice(0, 400)}
          </div>
          <div style={{display: 'flex', gap: 6, alignItems: 'center'}}>
            <Button kind='secondary' sm onClick={() => fix(c.id)} disabled={busy}>
              Fix
            </Button>
            <Button kind='ghost' sm onClick={() => dismiss(c.id)} disabled={busy}>
              Dismiss
            </Button>
            <a
              href={c.githubUrl}
              target='_blank'
              rel='noreferrer'
              style={{fontSize: 12, color: 'var(--signal-coral)'}}
            >
              View ↗
            </a>
          </div>
        </div>
      ))}
    </div>
  );
}
