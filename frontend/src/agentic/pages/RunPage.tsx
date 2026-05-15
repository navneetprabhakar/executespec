/**
 * Run detail — mission control surface.
 *
 * Lead with the MissionCard (verdict + score + actions). Under it,
 * progressive disclosure:
 *   - Summary (collapsed): goal text + provider · model · duration · cost
 *   - Plan (collapsed): task list with statuses
 *   - Files (chevron link → opens the v3 run detail's file tab for diff)
 *   - Validation report (link to v3 report panel)
 *
 * The agent input at the bottom lets the user iterate: "tighten the
 * test coverage on payment flows" → fires a follow-up run on this PR.
 */
import {useEffect, useState} from 'react';
import {Link, useParams} from 'react-router-dom';
import {ChevronRight, FileCode, Sparkles} from 'lucide-react';
import {getRun, type RunDetail} from '@/lib/api';
import {fmtCost, fmtMs} from '@/lib/utils';
import LargeTitle from '../components/LargeTitle';
import MissionCard, {type MissionRun} from '../components/MissionCard';
import {useAgentContext} from '../lib/useAgentContext';
import ListGroup from '../components/ListGroup';

export default function RunPage() {
  const {runId, projectId} = useParams<{runId: string; projectId?: string}>();
  const [run, setRun] = useState<RunDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const setAgentCtx = useAgentContext((s) => s.set);
  const clearAgentCtx = useAgentContext((s) => s.clear);

  useEffect(() => {
    if (!runId) return;
    let cancelled = false;
    getRun(runId)
      .then((r) => {
        if (!cancelled) setRun(r);
      })
      .catch((e) => !cancelled && setErr(String(e)));
    return () => {
      cancelled = true;
    };
  }, [runId]);

  useEffect(() => {
    if (run?.runTitle) {
      setAgentCtx({label: run.runTitle, projectId: run.projectId ?? undefined});
    }
    return () => clearAgentCtx();
  }, [run?.runTitle, run?.projectId, setAgentCtx, clearAgentCtx]);

  if (err) {
    return (
      <div className='a-card' style={{padding: 24, color: 'var(--a-fail)'}}>
        Couldn't load run: {err}
      </div>
    );
  }
  if (!run) {
    return (
      <p style={{color: 'var(--a-text-subtle)', padding: 16}}>Loading run…</p>
    );
  }

  // RunDetail doesn't carry verdict/score/buildOk/test-ratio directly — those
  // are derived from the ValidationReport (loaded separately by the v3 detail
  // page). For the mission-control hero here we lead with status + cost +
  // duration and let the user tap through for the full report. A future pass
  // can wire the ValidationReport API for verified-score-first display.
  const completed = run.startedAt && run.completedAt
    ? new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime()
    : null;
  const mission: MissionRun = {
    runId: run.runId,
    runTitle: run.runTitle,
    status: run.status,
    verdict: null,
    verifiedScore: null,
    estimatedScore: null,
    completedAt: run.completedAt ?? null,
    startedAt: run.startedAt ?? null,
    durationMs: completed,
    actualCostUsd: run.actualCostUsd ?? null,
    githubPrUrl: run.githubPrUrl ?? run.scmPrUrl ?? null,
    buildOk: null,
    reviewerVerdict: null,
    testRatio: null,
    projectId: run.projectId ?? projectId ?? null,
  };

  return (
    <>
      <LargeTitle
        title={run.runTitle ?? 'Run'}
        subtitle={
          <>
            {run.status.toLowerCase()}
            {completed ? ` · ${fmtMs(completed)}` : ''}
            {run.actualCostUsd != null && run.actualCostUsd > 0
              ? ` · ${fmtCost(run.actualCostUsd)}`
              : ''}
            {run.aiProvider ? ` · ${run.aiProvider.toLowerCase()}` : ''}
          </>
        }
      />

      <div style={{marginBottom: 20}}>
        <MissionCard run={mission} />
      </div>

      {run.userRequest && (
        <section style={{marginBottom: 20}}>
          <ListGroup label='Spec'>
            <div
              className='a-list-row'
              style={{
                cursor: 'default',
                alignItems: 'flex-start',
                padding: '14px 14px',
              }}
            >
              <Sparkles
                size={14}
                aria-hidden='true'
                style={{
                  color: 'var(--a-brand)',
                  marginTop: 3,
                  flexShrink: 0,
                }}
              />
              <p
                style={{
                  margin: 0,
                  flex: 1,
                  fontSize: 'var(--a-text-sm)',
                  color: 'var(--a-text)',
                  lineHeight: 1.55,
                  whiteSpace: 'pre-wrap',
                }}
              >
                {run.userRequest}
              </p>
            </div>
          </ListGroup>
        </section>
      )}

      {run.tasks && run.tasks.length > 0 && (
        <ListGroup label={`Plan (${run.tasks.length} task${run.tasks.length === 1 ? '' : 's'})`}>
          {run.tasks.slice(0, 6).map((t) => (
            <ListGroup.Row
              key={t.taskId}
              title={t.title}
              detail={t.status.toLowerCase().replace(/_/g, ' ')}
              trailing={
                t.status === 'COMPLETE'
                  ? '✓'
                  : t.status === 'FAILED'
                    ? '✗'
                    : null
              }
            />
          ))}
          {run.tasks.length > 6 && (
            <ListGroup.Row
              title={`Show all ${run.tasks.length} tasks`}
              chevron
              href={`/runs/${run.runId}?tab=plan`}
            />
          )}
        </ListGroup>
      )}

      <ListGroup label='Files & report'>
        <ListGroup.Row
          icon={<FileCode size={14} />}
          iconBg='var(--a-brand-soft)'
          title='Changed files'
          detail='Diff viewer'
          chevron
          href={`/runs/${run.runId}?tab=overview`}
        />
        <ListGroup.Row
          title='Validation report'
          detail='Score · build · reviewer · tests'
          chevron
          href={`/runs/${run.runId}?tab=plan`}
        />
        {(run.githubPrUrl || run.scmPrUrl) && (
          <ListGroup.Row
            title='Open pull request'
            detail={(run.githubPrUrl ?? run.scmPrUrl)!
              .replace(/^https?:\/\/github\.com\//, '')
              .replace(/^https?:\/\/bitbucket\.org\//, '')}
            chevron
            href={run.githubPrUrl ?? run.scmPrUrl ?? '#'}
          />
        )}
      </ListGroup>

      <ListGroup label='Quick jump'>
        <ListGroup.Row
          title='Live log'
          detail='Tool calls + agent narration'
          chevron
          href={`/runs/${run.runId}?tab=log`}
        />
        <ListGroup.Row
          title='Test report'
          detail='Generated tests + outcomes'
          chevron
          href={`/runs/${run.runId}?tab=tests`}
        />
        <ListGroup.Row
          title='Preview'
          detail='Cloud Run preview (5-min TTL)'
          chevron
          href={`/runs/${run.runId}?tab=preview`}
        />
      </ListGroup>
    </>
  );
}
