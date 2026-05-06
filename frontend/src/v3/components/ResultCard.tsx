import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {
  cancelRun,
  getRun,
  getValidationReport,
  getRunPrs,
  type RunDetail,
  type ValidationReportData,
} from '@/lib/api';
import type {PrSummary} from '@/types/project';
import Pill from './Pill';
import {STATUS_LABELS, VERDICT_LABELS, type V3Verdict, COST_PREFIX} from '../lib/terminology';
import {
  deriveVerdict as canonicalDeriveVerdict,
  toV3Verdict,
  verdictTone as canonicalVerdictTone,
} from '@/lib/verdict';

/**
 * R-122 — inline run-result card.
 *
 * Compact summary of a finished (or in-flight) run. Lives in the
 * project conversation thread. Click "Open run" → /projects/{slug}/runs/{runShort}
 * for the PR-shaped detail (R-130).
 *
 * Shows the honest 3-state verdict (Ready / Needs review / Failed)
 * + 4 evidence chips (Build / Tests / Reviewer / Cost). NO "Verified
 * N/100" headline; NO "Shippable" auto-claim.
 *
 * For in-flight runs (PLANNING/WORKING/REVIEWING), shows a status
 * pill with a dot. For terminal runs without report data, shows
 * just the status pill (still honest — no fabricated evidence chips).
 */
interface ResultCardProps {
  runId: string;
  /** Initial status hint from the bubble — saves a fetch when the parent
   *  thread already knows the status (e.g. seeded from /api/runs). */
  initialStatus?: string;
}

/**
 * Audit r5 (P0-3): every verdict-derivation site now goes through
 * `lib/verdict.ts` so v2 RunsPage / v2 dashboard / v3 ResultCard /
 * v3 RunDetailPage all agree on the same run.
 */
function deriveVerdict(
  detail: RunDetail | null,
  report: ValidationReportData | null,
): V3Verdict | null {
  if (!detail) return null;
  return toV3Verdict(
    canonicalDeriveVerdict({
      status: detail.status,
      finalBuildPassed: report?.finalBuildPassed,
      testsPassed: report?.testsPassed,
      testsTotal: report?.testsTotal,
      reviewerVerdict: report?.reviewerVerdict,
      verifiedScore: report?.verifiedScore,
      statusReason: detail.statusReason,
    }),
  );
}

const verdictTone = canonicalVerdictTone;

export default function ResultCard({runId, initialStatus}: ResultCardProps) {
  const [detail, setDetail] = useState<RunDetail | null>(null);
  const [report, setReport] = useState<ValidationReportData | null>(null);
  // Multi-repo PR list — same fallback as RunDetailPage. detail.githubPrUrl is null on multi-repo
  // runs, so we have to consult the registry to know if there are any PRs at all.
  const [prs, setPrs] = useState<PrSummary[]>([]);
  const [loading, setLoading] = useState(true);
  // Audit r5 (#3, 2026-05-06) — cancel-while-running. Tracks the
  // in-flight cancel call so the button can disable + show a label
  // while the DELETE roundtrips.
  const [cancelling, setCancelling] = useState(false);
  const navigate = useNavigate();

  async function onCancel() {
    if (cancelling) return;
    if (
      !confirm(
        'Cancel this run? Any work the agent has already done will be discarded.',
      )
    ) {
      return;
    }
    setCancelling(true);
    try {
      await cancelRun(runId);
      // Optimistically reflect cancellation; the polling layer above
      // (useLiveRunStatus) will catch the real CANCELLED status on
      // its next tick and morph the bubble appropriately.
      setDetail((d) => (d ? {...d, status: 'CANCELLED'} : d));
    } catch (e) {
      alert((e as Error)?.message ?? 'Cancel failed.');
    } finally {
      setCancelling(false);
    }
  }

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getRun(runId)
      .then((d) => {
        if (cancelled) return;
        setDetail(d);
        // Pull report + PR list only if terminal (no point thrashing on in-flight).
        if (d.status === 'COMPLETE' || d.status === 'PARTIAL') {
          return Promise.all([
            getValidationReport(runId).catch(() => null),
            getRunPrs(runId).catch(() => [] as PrSummary[]),
          ]).then(([r, p]) => {
            if (cancelled) return;
            setReport(r);
            setPrs(p);
          });
        }
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [runId]);

  const status = detail?.status ?? initialStatus ?? 'PENDING';
  const verdict = deriveVerdict(detail, report);
  const isInFlight = !['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED', 'ABORTED'].includes(status);

  const headerLabel = verdict ? VERDICT_LABELS[verdict] : (STATUS_LABELS[status] ?? status);
  const headerTone = verdict ? verdictTone(verdict) : isInFlight ? 'info' : 'neutral';

  return (
    <div
      style={{
        border: '1px solid var(--v3-border-strong)',
        background: 'var(--v3-surface)',
        borderRadius: 8,
        padding: 14,
        maxWidth: 640,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 10,
          flexWrap: 'wrap',
        }}
      >
        <Pill tone={headerTone} dot={isInFlight}>
          {headerLabel}
        </Pill>
        {detail?.runTitle && (
          <span style={{fontSize: 13, fontWeight: 600, color: 'var(--v3-text)'}}>
            {detail.runTitle}
          </span>
        )}
      </div>

      {loading && !detail && (
        <div style={{fontSize: 12, color: 'var(--v3-text-subtle)'}}>Loading…</div>
      )}

      {detail && (
        <>
          {/* Evidence chips — only what we can stand behind */}
          <div style={{display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 10}}>
            {report?.finalBuildPassed === true && (
              <Pill tone='ok' size='sm'>
                Build ✓
              </Pill>
            )}
            {report?.finalBuildPassed === false && (
              <Pill tone='err' size='sm'>
                Build ✗
              </Pill>
            )}
            {report?.testsTotal != null && report.testsTotal > 0 && (
              <Pill tone={report.testsPassed === report.testsTotal ? 'ok' : 'warn'} size='sm'>
                {report.testsPassed}/{report.testsTotal} tests
              </Pill>
            )}
            {report?.reviewerVerdict === 'CLEAN' && (
              <Pill tone='ok' size='sm'>
                Reviewer 0 issues
              </Pill>
            )}
            {report?.reviewerVerdict === 'FIXED_ISSUES' && (
              <Pill tone='warn' size='sm'>
                {(() => {
                  const n = report.issuesCaught ?? 0;
                  if (n === 0) return 'Reviewer auto-fixed';
                  if (n === 1) return 'Reviewer fixed 1 issue';
                  return `Reviewer fixed ${n} issues`;
                })()}
              </Pill>
            )}
            {report?.reviewerVerdict === 'UNRESOLVED_ISSUES' && (
              <Pill tone='err' size='sm'>
                Reviewer unresolved
              </Pill>
            )}
            {detail.actualCostUsd != null && (
              <Pill tone='neutral' size='sm'>
                {COST_PREFIX} ${detail.actualCostUsd.toFixed(2)}
              </Pill>
            )}
          </div>

          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
            <button
              type='button'
              // 2026-05-02 — was `/r/${runId}` which only matches the short
              // alias `/p/:slug/r/:run` (rewritten in App.tsx). The long form
              // `/projects/:slug/...` has NO `/r/` rewrite, so the path
              // dropped through every route and rendered the landing page.
              // Fix: route to canonical `/projects/:slug/runs/:runShort` when
              // we have the projectId; fall back to `/runs/{runId}` (which
              // mounts RunsPage with a focused detail view) when the bubble
              // didn't carry projectId — happens for older bubbles + when the
              // run detail blob is loading. Either way, NEVER produce
              // `/projects//runs/...` (the empty-projectId double-slash that
              // also dropped through to the landing page).
              onClick={() => {
                const pid = detail.projectId;
                if (pid && pid.length > 0) {
                  navigate(`/projects/${pid}/runs/${runId}`);
                } else {
                  navigate(`/runs/${runId}`);
                }
              }}
              style={{
                padding: '5px 12px',
                fontSize: 12,
                fontWeight: 500,
                background: 'var(--v3-brand)',
                color: 'var(--v3-text-on-brand)',
                border: 'none',
                borderRadius: 6,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              Open run
            </button>
            {/* Audit r5 (#3, 2026-05-06): cancel-while-running. Pre-fix
                v3 had no escape hatch for an in-flight run — the user
                had to wait the full ~30-90s to terminal even if they
                spotted a typo in their spec. The DELETE /api/runs/{id}
                endpoint already exists and the V2 RunOrchestrator's
                cancel path is robust. We surface it only on in-flight
                runs (PLANNING/AWAITING_APPROVAL/WORKING/REVIEWING) so
                terminal cards stay clean. */}
            {isInFlight && (
              <button
                type='button'
                onClick={onCancel}
                disabled={cancelling}
                style={{
                  padding: '5px 12px',
                  fontSize: 12,
                  fontWeight: 500,
                  background: 'transparent',
                  color: cancelling ? 'var(--v3-text-subtle)' : 'var(--v3-error)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 6,
                  cursor: cancelling ? 'not-allowed' : 'pointer',
                  fontFamily: 'inherit',
                }}
                title='Stop this run. The agent will discard any in-progress work.'
              >
                {cancelling ? 'Cancelling…' : 'Cancel run'}
              </button>
            )}
            {/* Multi-repo aware: same logic as RunDetailPage. 0 → none, 1 → "Open PR", N>1 →
                 one button per repo labeled "<short>#<num>". Falls back to detail.githubPrUrl
                 for legacy runs without registry rows. */}
            {(() => {
              const legacyUrl = detail.githubPrUrl;
              if (prs.length === 0 && !legacyUrl) return null;
              if (prs.length <= 1) {
                // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
                const single = prs[0]?.prUrl ?? legacyUrl!;
                return (
                  <a
                    href={single}
                    target='_blank'
                    rel='noreferrer'
                    style={{
                      padding: '5px 12px',
                      fontSize: 12,
                      fontWeight: 500,
                      background: 'transparent',
                      color: 'var(--v3-text-muted)',
                      border: '1px solid var(--v3-border)',
                      borderRadius: 6,
                      cursor: 'pointer',
                      textDecoration: 'none',
                      fontFamily: 'inherit',
                    }}
                  >
                    Open PR ↗
                  </a>
                );
              }
              return prs.map((pr) => {
                const shortRepo = pr.repoFullName?.includes('/')
                  ? pr.repoFullName.slice(pr.repoFullName.lastIndexOf('/') + 1)
                  : pr.repoFullName;
                return (
                  <a
                    key={`${pr.repoFullName}-${pr.prNumber}`}
                    href={pr.prUrl}
                    target='_blank'
                    rel='noreferrer'
                    title={`${pr.repoFullName} · ${pr.status}`}
                    style={{
                      padding: '5px 10px',
                      fontSize: 12,
                      fontWeight: 500,
                      background: 'transparent',
                      color: 'var(--v3-text-muted)',
                      border: '1px solid var(--v3-border)',
                      borderRadius: 6,
                      cursor: 'pointer',
                      textDecoration: 'none',
                      fontFamily: 'inherit',
                    }}
                  >
                    {shortRepo}#{pr.prNumber} ↗
                  </a>
                );
              });
            })()}
          </div>
        </>
      )}
    </div>
  );
}
