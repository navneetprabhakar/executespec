import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {relTime, runTitleFor} from '@/signal/lib/runUtils';
import {StatusDot} from '@/ide/IdeIcons';

export default function ConsoleDecisions() {
  const navigate = useNavigate();
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);

  const load = useCallback(() => {
    api
      .getRuns({status: 'AWAITING_APPROVAL', size: 50})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => setRuns([]));
  }, []);

  useEffect(() => {
    load();
    const id = window.setInterval(() => {
      if (!document.hidden) load();
    }, 12000);
    return () => window.clearInterval(id);
  }, [load]);

  return (
    <div className='con-home'>
      <section className='con-cockpit'>
        <div className='con-cockpit-main'>
          <div className='con-kicker'>Plan gate</div>
          <div className='con-hero-title'>Decisions</div>
          <div className='con-hero-sub'>
            Approve, reject, or inspect work that is paused before code changes begin.
          </div>
        </div>
        <div className='con-cockpit-metrics'>
          <button className='con-metric-card' onClick={() => navigate('/compose')}>
            <span className='con-metric-label'>New work</span>
            <strong>+</strong>
          </button>
          <button className='con-metric-card' onClick={() => navigate('/runs')}>
            <span className='con-metric-label'>All work</span>
            <strong>↗</strong>
          </button>
        </div>
      </section>

      <section className='con-section con-section-priority'>
        <div className='con-section-title attn'>Awaiting approval</div>
        {runs === null ? (
          <IdeLoader label='Loading decisions…' />
        ) : runs.length === 0 ? (
          <div className='con-empty'>No plan gates are waiting.</div>
        ) : (
          runs.map((r) => (
            <button
              key={r.runId}
              className='con-runrow'
              onClick={() => navigate(`/plan/${r.runId}`)}
              title={runTitleFor(r)}
            >
              <StatusDot kind={statusKind(r.status)} size={9} />
              <span className='con-runrow-main'>
                <span className='con-runrow-title'>{runTitleFor(r)}</span>
                <span className='con-runrow-meta'>
                  {(r.projectName || 'Sandbox') + ' · ' + relTime(r.startedAt)}
                </span>
              </span>
              <IdeBadge kind='warn'>Gate</IdeBadge>
              <span className='con-runrow-action'>Review plan</span>
            </button>
          ))
        )}
      </section>
    </div>
  );
}
