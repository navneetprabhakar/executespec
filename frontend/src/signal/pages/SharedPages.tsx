import {useEffect, useState} from 'react';
import {Link, useLocation, useParams} from 'react-router-dom';

import * as api from '@/lib/api';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Card, KvList} from '@/signal/components/Primitives';
import {LoadingState} from '@/signal/components/Loaders';
import {ScoreCard, PrCard} from '@/signal/components/Compose';
import {verdictFor} from '@/signal/components/VerdictPill';

function SharedProjectSurfaceView({surface}: {surface: api.SharedProjectSurface}) {
  const meta = surface.meta;
  const surfaceText = meta.surface.replace(/_/g, ' ');
  const label = meta.label || surfaceText.toLowerCase();
  const expires = meta.expiresAt ? new Date(meta.expiresAt).toLocaleDateString() : 'Unavailable';

  return (
    <div className='public-share-console'>
      <TopBar title='Public project link' sub='read-only · no auth' back backTo='/compose' />
      <Feed>
        <Card className='public-share-notice'>
          <div className='public-share-notice-eyebrow'>Shared project surface · public link</div>
          <div className='public-share-notice-copy'>
            This read-only link can be opened without an ExecuteSpec account. The owner can revoke
            it at any time.
          </div>
        </Card>

        <SectionHeader>{label}</SectionHeader>
        <Card>
          <KvList
            pairs={[
              {k: 'Surface', v: surfaceText},
              {
                k: 'Created',
                v: meta.createdAt ? new Date(meta.createdAt).toLocaleDateString() : '—',
              },
              {k: 'Expires', v: expires},
            ]}
          />
        </Card>

        {surface.kind === 'PROJECT_OVERVIEW' && (
          <>
            <SectionHeader>Project snapshot</SectionHeader>
            <Card>
              <KvList
                pairs={[
                  {k: 'Name', v: surface.project.name || 'Untitled project'},
                  {k: 'Status', v: surface.project.status || '—'},
                  {
                    k: 'Updated',
                    v: surface.project.updatedAt
                      ? new Date(surface.project.updatedAt).toLocaleDateString()
                      : '—',
                  },
                ]}
              />
              {surface.project.description && (
                <p
                  style={{
                    fontSize: 14,
                    color: 'var(--signal-ink)',
                    lineHeight: 1.55,
                    margin: '14px 0 0',
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {surface.project.description}
                </p>
              )}
            </Card>
          </>
        )}

        {surface.kind === 'RUN_DETAIL' && (
          <>
            <SectionHeader>Run</SectionHeader>
            <Card>
              <KvList
                pairs={[
                  {k: 'Title', v: surface.run.title || 'Untitled run'},
                  {k: 'Status', v: surface.run.status || '—'},
                  {
                    k: 'Completed',
                    v: surface.run.completedAt
                      ? new Date(surface.run.completedAt).toLocaleDateString()
                      : 'active',
                  },
                ]}
              />
            </Card>
          </>
        )}

        {surface.kind === 'VALIDATION_REPORT' &&
          (surface.report.verifiedScore != null ? (
            <ScoreCard
              verdict={`${verdictFor(surface.report.verifiedScore, undefined).label} · unverified`}
              score={Math.round(surface.report.verifiedScore)}
              label='Verified score · unverified'
              cells={[
                {
                  v:
                    surface.report.testsTotal != null
                      ? `${surface.report.testsPassed ?? 0}/${surface.report.testsTotal} passed · ${surface.report.testsSkipped ?? 0} skipped`
                      : '—',
                  l: 'Tests',
                },
                {v: surface.report.finalBuildPassed ? 'pass' : '—', l: 'Build'},
                {v: surface.report.reviewerVerdict || '—', l: 'Review'},
              ]}
            />
          ) : (
            <Card>
              <strong>Verified score unavailable</strong>
              <p style={{margin: '8px 0 0', color: 'var(--signal-text-mute)'}}>
                This shared report does not contain a deterministic score. No score is inferred from
                legacy assessment fields.
              </p>
            </Card>
          ))}

        {surface.kind === 'CODE_REVIEW' && (
          <>
            <ScoreCard
              verdict={surface.review.status || 'Code review'}
              score={surface.review.totalIssues}
              label='Issues found'
              cells={[
                {v: `${surface.review.criticalCount}`, l: 'Critical'},
                {v: `${surface.review.majorCount}`, l: 'Major'},
                {v: surface.review.repoFullName || '—', l: 'Repo'},
              ]}
            />
            {surface.review.reportMarkdown && (
              <>
                <SectionHeader>Report</SectionHeader>
                <Card>
                  <pre
                    style={{
                      whiteSpace: 'pre-wrap',
                      margin: 0,
                      fontSize: 13,
                      color: 'var(--signal-ink)',
                      lineHeight: 1.55,
                    }}
                  >
                    {surface.review.reportMarkdown}
                  </pre>
                </Card>
              </>
            )}
          </>
        )}

        {surface.kind === 'PR_REVIEW' && (
          <>
            <ScoreCard
              verdict={surface.review.verdict || 'PR review'}
              score={surface.review.commentCount}
              label='Review comments'
              cells={[
                {v: surface.review.repoFullName || '—', l: 'Repo'},
                {v: surface.review.prNumber != null ? `#${surface.review.prNumber}` : '—', l: 'PR'},
                {v: surface.review.verdict || '—', l: 'Verdict'},
              ]}
            />
            {surface.review.summary && (
              <>
                <SectionHeader>Summary</SectionHeader>
                <Card>
                  <p style={{whiteSpace: 'pre-wrap', margin: 0, lineHeight: 1.55}}>
                    {surface.review.summary}
                  </p>
                </Card>
              </>
            )}
          </>
        )}

        {surface.kind === 'ARCHITECTURE_DOC' && (
          <>
            <SectionHeader>Architecture v{surface.architecture.version ?? '—'}</SectionHeader>
            <Card>
              <pre
                style={{
                  whiteSpace: 'pre-wrap',
                  margin: 0,
                  fontSize: 13,
                  color: 'var(--signal-ink)',
                  lineHeight: 1.55,
                }}
              >
                {surface.architecture.content || 'Architecture doc is empty.'}
              </pre>
            </Card>
          </>
        )}

        <p
          style={{
            textAlign: 'center',
            fontSize: 12,
            color: 'var(--signal-text-mute)',
            marginTop: 16,
          }}
        >
          Shipped by <strong style={{color: 'var(--signal-ink)'}}>ExecuteSpec</strong> ·{' '}
          <Link to='/login' style={{color: 'var(--signal-coral)'}}>
            Build your own →
          </Link>
        </p>
      </Feed>
    </div>
  );
}

export function SharedPage() {
  const {token} = useParams<{token: string}>();
  const location = useLocation();
  const [data, setData] = useState<api.SharedReportData | null>(null);
  const [projectSurface, setProjectSurface] = useState<api.SharedProjectSurface | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!token) return;
    setData(null);
    setProjectSurface(null);
    setErr('');
    // RR-1331 — public share bearers are confidential; a slow response for token A must never be
    // painted under token B after the viewer navigates. Gate every setter on a per-effect
    // generation so an out-of-order or superseded completion is discarded.
    let cancelled = false;
    const legacyOnly = location.pathname.startsWith('/reports/shared/');
    if (legacyOnly) {
      api
        .getSharedReport(token)
        .then((d) => {
          if (cancelled) return;
          if (d) setData(d);
          else setErr('Share link expired or revoked.');
        })
        .catch((e: unknown) => {
          if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load.');
        });
      return () => {
        cancelled = true;
      };
    }

    api
      .getSharedProjectSurface(token)
      .then((s) => {
        if (!cancelled) setProjectSurface(s);
      })
      .catch(() => {
        if (cancelled) return;
        api
          .getSharedReport(token)
          .then((d) => {
            if (cancelled) return;
            if (d) setData(d);
            else setErr('Share link expired or revoked.');
          })
          .catch((e: unknown) => {
            if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load.');
          });
      });
    return () => {
      cancelled = true;
    };
  }, [location.pathname, token]);

  if (err) {
    return (
      <div className='public-share-console'>
        <TopBar title='Public report' sub='read-only · no auth' back backTo='/compose' />
        <Feed>
          <Card className='public-share-error'>
            <div>{err}</div>
          </Card>
        </Feed>
      </div>
    );
  }

  if (!data) {
    if (projectSurface) return <SharedProjectSurfaceView surface={projectSurface} />;
    return (
      <div className='public-share-console'>
        <TopBar title='Public report' sub='loading…' back backTo='/compose' />
        <Feed>
          <LoadingState label='Loading shared report…' />
        </Feed>
      </div>
    );
  }

  const score = (data as unknown as {verifiedScore?: number | null}).verifiedScore ?? null;
  // Shared verdict helper — was inline ≥90 here, which disagreed with the
  // canonical ≥85 band used everywhere else. Shared report has no run status,
  // so pass undefined and let the score drive the verdict.
  const verdict = score == null ? null : `${verdictFor(score, undefined).label} · unverified`;
  const userRequest = (data as unknown as {userRequest?: string}).userRequest || '—';
  const prUrl =
    (data as unknown as {prUrl?: string; githubPrUrl?: string}).prUrl ||
    (data as unknown as {githubPrUrl?: string}).githubPrUrl;
  const cost =
    (data as unknown as {totalCost?: number; actualCost?: number}).totalCost ??
    (data as unknown as {actualCost?: number}).actualCost;
  const tests = (data as unknown as {testsTotal?: number; testsPassed?: number}).testsTotal;

  return (
    <div className='public-share-console'>
      <TopBar title='Public report' sub='read-only · no auth' back backTo='/compose' />
      <Feed>
        <Card className='public-share-notice'>
          <div className='public-share-notice-eyebrow'>Shared report · public link</div>
          <div className='public-share-notice-copy'>
            This page is publicly accessible. The original owner can revoke at any time.
          </div>
        </Card>

        {score != null ? (
          <ScoreCard
            verdict={verdict as string}
            score={Math.round(score)}
            label='Verified score · unverified'
            cells={[
              {
                v: tests
                  ? `${(data as unknown as {testsPassed?: number}).testsPassed ?? 0}/${tests}`
                  : '—',
                l: 'Tests',
              },
              {v: cost != null ? `$${cost.toFixed(4)}` : '—', l: 'Cost'},
              {v: prUrl ? 'open' : '—', l: 'PR'},
            ]}
          />
        ) : (
          <Card>
            <strong>Verified score unavailable</strong>
            <p style={{margin: '8px 0 0', color: 'var(--signal-text-mute)'}}>
              This shared report has no deterministic score. Legacy score fields are not treated as
              verified evidence.
            </p>
          </Card>
        )}

        <SectionHeader>Spec</SectionHeader>
        <Card>
          <p
            style={{
              fontSize: 14,
              color: 'var(--signal-ink)',
              lineHeight: 1.55,
              margin: 0,
              whiteSpace: 'pre-wrap',
            }}
          >
            {userRequest}
          </p>
        </Card>

        {prUrl && (
          <>
            <SectionHeader>Pull request</SectionHeader>
            <PrCard
              label='PR · OPEN'
              title={userRequest.slice(0, 80)}
              repo={prUrl.replace(/^https?:\/\/[^/]+\//, '').replace(/\/pull\//, '#')}
              ciStatus={<span className='ci-ok'>open</span>}
              actions={
                <button type='button' onClick={() => window.open(prUrl, '_blank', 'noopener')}>
                  Open on GitHub
                </button>
              }
            />
          </>
        )}

        <p
          style={{
            textAlign: 'center',
            fontSize: 12,
            color: 'var(--signal-text-mute)',
            marginTop: 16,
          }}
        >
          Shipped by <strong style={{color: 'var(--signal-ink)'}}>ExecuteSpec</strong> ·{' '}
          <Link to='/login' style={{color: 'var(--signal-coral)'}}>
            Build your own →
          </Link>
        </p>
      </Feed>
    </div>
  );
}
