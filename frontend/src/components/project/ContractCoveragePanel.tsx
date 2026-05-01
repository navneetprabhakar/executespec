import {useEffect, useState} from 'react';
import {
  getContractCoverage,
  rescanContractCoverage,
  type ContractCoverageResponse,
  type DetectorStatus,
} from '@/lib/api';

/**
 * E2.4 — per-repo contract coverage panel for the Project Overview tab.
 *
 * UX contract: reuses {@link WorkspaceHealthPanel} layout shape (rounded-sm
 * card, monospace repo name, 4-band semantic chips, no accordions) so the
 * dashboard's visual language stays consistent. Two action buttons:
 * <em>Refresh</em> (cached read) and <em>Re-scan</em> (POST /rescan ⇒ runs
 * every detector against the live repo + opens .executespec/ writes).
 *
 * Always renders the <em>Not auto-detected</em> section at the bottom — the
 * customer should see what categories ExecuteSpec deliberately doesn't cover
 * (gRPC-in-code, runtime OpenAPI, external registries, monorepo scoping)
 * so they don't silently miss a contract surface.
 */
export default function ContractCoveragePanel({
  projectId,
  repoId,
  repoFullName,
}: {
  projectId: string;
  repoId: string;
  repoFullName?: string;
}) {
  const [coverage, setCoverage] = useState<ContractCoverageResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [rescanning, setRescanning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setCoverage(await getContractCoverage(projectId, repoId));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, repoId]);

  const doRescan = async () => {
    if (rescanning) return;
    setRescanning(true);
    setError(null);
    try {
      setCoverage(await rescanContractCoverage(projectId, repoId));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRescanning(false);
    }
  };

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3'>
      <div className='flex items-center justify-between mb-2'>
        <h3 className='text-sm font-medium text-primary'>
          Contract coverage
          {coverage?.githubRepoFullName || repoFullName ? (
            <span className='ml-2 text-[11px] font-mono text-muted'>
              {coverage?.githubRepoFullName || repoFullName}
            </span>
          ) : null}
        </h3>
        <div className='flex items-center gap-2'>
          <button
            onClick={load}
            disabled={loading}
            className='text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary disabled:opacity-50'
          >
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
          <button
            onClick={doRescan}
            disabled={rescanning}
            title='Run every detector against the live repo content (slow; opens .executespec/ writes when contracts found)'
            className='text-[11px] px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50'
          >
            {rescanning ? 'Re-scanning…' : 'Re-scan'}
          </button>
        </div>
      </div>

      {error && <div className='text-xs text-accent-red'>{error}</div>}

      {!error && coverage && (
        <>
          <div className='space-y-1'>
            {coverage.scans.map((s) => (
              <div
                key={s.detector}
                className='flex items-center justify-between gap-2 text-xs py-1 border-b border-[var(--border-default)] last:border-b-0'
              >
                <div className='flex items-center gap-2 min-w-0'>
                  <span className='font-mono truncate text-primary'>{s.detector}</span>
                  <span className='text-[11px] text-muted'>{s.type.toLowerCase()}</span>
                </div>
                <div className='flex items-center gap-1.5 shrink-0'>
                  <StatusChip
                    status={s.status}
                    found={s.contractIds.length}
                    failureReason={s.failureReason ?? undefined}
                  />
                </div>
              </div>
            ))}
          </div>

          {coverage.knownGaps.length > 0 && (
            <div className='mt-3 pt-2 border-t border-[var(--border-default)]'>
              <div className='text-[11px] text-muted mb-1'>
                Not auto-detected — declare manually if relevant
              </div>
              <div className='space-y-1'>
                {coverage.knownGaps.map((g) => (
                  <div
                    key={g.category}
                    className='flex items-start justify-between gap-2 text-[11px] py-0.5'
                  >
                    <span className='text-primary'>{g.explanation}</span>
                    <span className='shrink-0 inline-flex items-center rounded-sm bg-accent-amber/10 text-accent-amber px-1.5 py-0.5 text-[9px]'>
                      {g.actionable.replace(/_/g, ' ')}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function StatusChip({
  status,
  found,
  failureReason,
}: {
  status: DetectorStatus;
  found: number;
  failureReason?: string;
}) {
  if (status === 'FAILED') {
    return (
      <span
        title={failureReason || 'detector failed'}
        className='inline-flex items-center rounded-sm bg-accent-red/10 text-accent-red px-1.5 py-0.5 text-[9px]'
      >
        failed
      </span>
    );
  }
  if (status === 'SKIPPED') {
    return (
      <span className='inline-flex items-center rounded-sm bg-[var(--gray-400)]/50 text-muted px-1.5 py-0.5 text-[9px]'>
        skipped
      </span>
    );
  }
  // SCANNED — found N contracts (or 0)
  return (
    <span
      className={`inline-flex items-center rounded-sm px-1.5 py-0.5 text-[9px] ${
        found > 0
          ? 'bg-status-success/10 text-status-success'
          : 'bg-[var(--gray-400)]/50 text-muted'
      }`}
    >
      {found > 0 ? `${found} found` : 'scanned · none'}
    </span>
  );
}
