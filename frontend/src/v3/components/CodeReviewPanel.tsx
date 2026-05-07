import {useEffect, useState} from 'react';
import {
  submitCodeReview,
  getCodeReview,
  listCodeReviewsForProject,
  getProjectRepos,
  codeReviewReportDownloadUrl,
  type CodeReviewScanDto,
  type CodeReviewScanWithIssues,
  isRetryableError,
} from '@/lib/api';
import type {ProjectRepo} from '@/types/project';

/**
 * Audit r5 (#8, 2026-05-06) — Code Review agent UI on the v3 surface.
 *
 * The /api/code-reviews endpoint is paid-tier-gated and fully wired
 * server-side (V172) but had ZERO frontend surface on the v3 chat
 * page. New users with their second-best commercial differentiator
 * (whole-repo, semgrep + LLM-triaged review) had no way to trigger
 * one without curl. PrReviewButton (the per-PR equivalent) was wired
 * into the v2 dashboard's ProjectPrRollup; this is its peer for
 * whole-repo scans.
 *
 * Backend behaviour:
 *   - POST /api/code-reviews → 202 (QUEUED row) on PRO+
 *   - 403 on FREE — we surface the message verbatim
 *   - 429 on quota exhausted — same
 *   - GET /code-reviews/{id} polled until status terminal
 *
 * Renders one row per linked repo with the latest scan's status +
 * action buttons. Hidden when the project has no repos linked.
 */
interface Props {
  projectId: string;
}

const TERMINAL = new Set(['COMPLETE', 'FAILED']);

export default function CodeReviewPanel({projectId}: Props) {
  const [collapsed, setCollapsed] = useState(true);
  const [repos, setRepos] = useState<ProjectRepo[]>([]);
  const [latestByRepo, setLatestByRepo] = useState<Record<string, CodeReviewScanDto | null>>({});
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState<string | null>(null); // repoFullName in flight

  // Initial load: fetch repos + all scans, take the newest per repo.
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      getProjectRepos(projectId).catch(() => []),
      listCodeReviewsForProject(projectId).catch((e) => {
        // Surface tier / quota errors but allow the repo list to render
        // so the user sees a "Run scan" button that explains why on click.
        if (cancelled) return [] as CodeReviewScanDto[];
        setError((e as Error)?.message ?? 'Could not load code reviews.');
        return [] as CodeReviewScanDto[];
      }),
    ]).then(([links, scans]) => {
      if (cancelled) return;
      setRepos(links.map((l) => l.repo).filter((r): r is ProjectRepo => !!r));
      const latest: Record<string, CodeReviewScanDto> = {};
      for (const s of scans) {
        const cur = latest[s.repoFullName];
        if (!cur || new Date(s.startedAt) > new Date(cur.startedAt)) {
          latest[s.repoFullName] = s;
        }
      }
      setLatestByRepo(latest);
    });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  // Poll any non-terminal scans every 4s.
  useEffect(() => {
    const inflight = Object.values(latestByRepo).filter(
      (s): s is CodeReviewScanDto => !!s && !TERMINAL.has(s.status),
    );
    if (inflight.length === 0) return;
    const handle = setInterval(() => {
      Promise.all(
        inflight.map((s) =>
          getCodeReview(s.id)
            .then((r: CodeReviewScanWithIssues) => r.scan)
            .catch(() => null),
        ),
      ).then((results) => {
        setLatestByRepo((prev) => {
          const next = {...prev};
          for (const r of results) {
            if (!r) continue;
            next[r.repoFullName] = r;
          }
          return next;
        });
      });
    }, 4000);
    return () => clearInterval(handle);
  }, [latestByRepo]);

  async function onRunReview(repo: ProjectRepo) {
    setError(null);
    setSubmitting(repo.githubRepoFullName);
    try {
      const scan = await submitCodeReview({
        projectId,
        repoId: repo.id,
        repoFullName: repo.githubRepoFullName,
      });
      setLatestByRepo((prev) => ({...prev, [repo.githubRepoFullName]: scan}));
      setCollapsed(false);
    } catch (e) {
      setError((e as Error)?.message ?? 'Could not start code review.');
      // Network / 5xx — keep button enabled so user can retry.
      if (!isRetryableError(e)) {
        // 403/429 are paid-tier or quota errors — surface and keep
        // disabled until next render.
      }
    } finally {
      setSubmitting(null);
    }
  }

  if (repos.length === 0) return null;

  return (
    <div
      style={{
        marginBottom: 12,
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 8,
        padding: '8px 12px',
        fontSize: 12.5,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
        }}
      >
        <button
          type='button'
          onClick={() => setCollapsed((c) => !c)}
          style={{
            background: 'transparent',
            border: 'none',
            color: 'var(--v3-text-muted)',
            cursor: 'pointer',
            padding: 0,
            fontFamily: 'inherit',
            fontSize: 12.5,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
          }}
          aria-expanded={!collapsed}
        >
          <span style={{color: 'var(--v3-text-subtle)'}}>{collapsed ? '▸' : '▾'}</span>
          <span>Code review (whole repo)</span>
          <span
            style={{
              fontSize: 10.5,
              padding: '1px 5px',
              borderRadius: 3,
              background: 'rgba(91,141,239,0.16)',
              color: 'var(--v3-brand)',
            }}
            title='Paid tier — Solo / Team / Enterprise. Free tier returns 403.'
          >
            Solo+
          </span>
        </button>
      </div>

      {!collapsed && (
        <div style={{marginTop: 8, display: 'flex', flexDirection: 'column', gap: 6}}>
          {error && (
            <div
              style={{
                fontSize: 11.5,
                color: 'var(--v3-error)',
                padding: '4px 6px',
                background: 'rgba(239,68,68,0.08)',
                border: '1px solid rgba(239,68,68,0.24)',
                borderRadius: 4,
              }}
              role='alert'
            >
              {error}
            </div>
          )}
          {repos.map((repo) => {
            const scan = latestByRepo[repo.githubRepoFullName];
            const inflight = scan && !TERMINAL.has(scan.status);
            const submittingThis = submitting === repo.githubRepoFullName;
            return (
              <div
                key={repo.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 6,
                  padding: '6px 8px',
                  background: 'var(--v3-bg)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 4,
                }}
              >
                <div style={{minWidth: 0, flex: 1}}>
                  <code
                    style={{
                      fontSize: 11.5,
                      color: 'var(--v3-text)',
                      background: 'transparent',
                    }}
                  >
                    {repo.githubRepoFullName}
                  </code>
                  {scan && (
                    <div
                      style={{
                        fontSize: 10.5,
                        color: 'var(--v3-text-subtle)',
                        marginTop: 2,
                      }}
                    >
                      {scan.status === 'COMPLETE'
                        ? `${scan.staticFindingsCount ?? 0} static + ${scan.llmFindingsCount ?? 0} LLM findings · ${(scan.costUsd ?? 0).toFixed(3)} USD`
                        : scan.status === 'FAILED'
                          ? `Failed — ${scan.failureReason ?? 'unknown error'}`
                          : `${scan.status.toLowerCase()}…`}
                    </div>
                  )}
                </div>
                <div style={{display: 'flex', gap: 4, flexShrink: 0}}>
                  {scan && scan.status === 'COMPLETE' && (
                    <a
                      href={codeReviewReportDownloadUrl(scan.id)}
                      target='_blank'
                      rel='noreferrer'
                      style={{
                        padding: '3px 8px',
                        fontSize: 11,
                        background: 'transparent',
                        color: 'var(--v3-text-muted)',
                        border: '1px solid var(--v3-border)',
                        borderRadius: 3,
                        textDecoration: 'none',
                      }}
                    >
                      View report ↗
                    </a>
                  )}
                  <button
                    type='button'
                    onClick={() => onRunReview(repo)}
                    disabled={!!inflight || submittingThis}
                    style={{
                      padding: '3px 8px',
                      fontSize: 11,
                      background: inflight ? 'transparent' : 'var(--v3-brand)',
                      color: inflight ? 'var(--v3-text-subtle)' : 'var(--v3-text-on-brand)',
                      border: '1px solid ' + (inflight ? 'var(--v3-border)' : 'var(--v3-brand)'),
                      borderRadius: 3,
                      cursor: inflight || submittingThis ? 'not-allowed' : 'pointer',
                      fontFamily: 'inherit',
                      fontWeight: 500,
                    }}
                    title={
                      inflight
                        ? 'Scan already in progress for this repo.'
                        : 'Run a fresh whole-repo scan (semgrep + LLM triage).'
                    }
                  >
                    {submittingThis
                      ? 'Starting…'
                      : inflight
                        ? scan!.status.toLowerCase()
                        : scan
                          ? 'Re-scan'
                          : 'Run scan'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
