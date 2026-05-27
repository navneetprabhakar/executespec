import {useEffect, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {relTime} from '@/signal/lib/runUtils';

/**
 * Global plan-gate indicator (RL-2026-05-052, control-plane reframe — the
 * highest-leverage piece): a persistent "N awaiting your approval" chip so a run
 * paused at AWAITING_APPROVAL is never missed (it directly gates the
 * SPEC_SUBMITTED → PLAN_APPROVED funnel step). Reuses GET /runs?status= (zero
 * backend change); polls every 15s, skipped while the tab is hidden. Renders
 * nothing when nothing is waiting (no nag).
 */
export function PlanGateIndicator({dir = 'up'}: {dir?: 'up' | 'down'}) {
  const navigate = useNavigate();
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = () => {
      if (document.hidden) return;
      api
        .getRuns({status: 'AWAITING_APPROVAL', size: 50})
        .then((r) => {
          if (!cancelled) setRuns(r.content ?? []);
        })
        .catch(() => {});
    };
    load();
    const id = window.setInterval(load, 15000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, []);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  const count = runs.length;
  if (count === 0) return null;

  const go = (id: string) => {
    setOpen(false);
    navigate(`/plan/${id}`);
  };

  return (
    <div ref={wrapRef} className='ide-gate-wrap'>
      <button
        type='button'
        className='ide-gate-badge'
        onClick={() => (count === 1 ? go(runs[0].runId) : setOpen((o) => !o))}
        title={`${count} run${count > 1 ? 's' : ''} awaiting your approval`}
        aria-label={`${count} run${count > 1 ? 's' : ''} awaiting approval`}
      >
        <span className='ide-gate-dot' aria-hidden='true' />
        {count} awaiting approval
      </button>
      {open && (
        <div className={`ide-gate-pop ${dir === 'down' ? 'down' : 'up'}`} role='menu'>
          <div className='ide-gate-pop-head'>Awaiting your approval</div>
          {runs.map((r) => (
            <button
              key={r.runId}
              type='button'
              className='ide-gate-pop-item'
              role='menuitem'
              onClick={() => go(r.runId)}
              title={r.runTitle || r.userRequest || r.runId}
            >
              <span className='ide-gate-pop-title'>
                {(r.runTitle || r.userRequest || r.runId).slice(0, 52)}
              </span>
              <span className='ide-gate-pop-meta'>
                {(r.projectName || 'no project') + ' · ' + relTime(r.startedAt)}
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
