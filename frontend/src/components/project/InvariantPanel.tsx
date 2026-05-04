import {useEffect, useState} from 'react';
import {
  listProjectInvariants,
  listProjectInvariantViolations,
  type InvariantSummary,
  type InvariantViolation,
} from '@/lib/api';
import {cn, errMsg} from '@/lib/utils';

/**
 * M-015 (Wave 2) — Project → Code → Invariants panel.
 * V138 architectural invariants live in customer git as
 * `.executespec/arch/invariants.yaml` per repo and are cached into
 * the `arch_invariant` table. This panel renders the cached entries
 * + recent violations so the user can see what rules are enforced
 * and what's currently in breach without leaving the dashboard.
 */
export default function InvariantPanel({projectId}: {projectId: string}) {
  const [invariants, setInvariants] = useState<InvariantSummary[] | null>(null);
  const [violations, setViolations] = useState<InvariantViolation[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setError(null);
    Promise.all([listProjectInvariants(projectId), listProjectInvariantViolations(projectId)])
      .then(([inv, vios]) => {
        if (!alive) return;
        setInvariants(inv);
        setViolations(vios);
      })
      .catch((e) => {
        if (alive) setError(errMsg(e) ?? 'Failed to load invariants');
      });
    return () => {
      alive = false;
    };
  }, [projectId]);

  const violationsByInvariant = (violations ?? []).reduce<Record<string, number>>((acc, v) => {
    if (v.invariantUuid) {
      acc[v.invariantUuid] = (acc[v.invariantUuid] ?? 0) + 1;
    }
    return acc;
  }, {});

  return (
    <div className='space-y-3'>
      <div>
        <h3 className='text-sm font-medium text-primary'>Architectural invariants</h3>
        <p className='text-xs text-muted mt-0.5'>
          Rules enforced at plan-time + on every architecture-doc regen. Defined in{' '}
          <code>.executespec/arch/invariants.yaml</code> per repo.
        </p>
      </div>

      {error && (
        <div className='rounded-sm bg-status-error/10 border border-status-error/30 px-3 py-2 text-xs text-status-error'>
          {error}
        </div>
      )}

      <div className='rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] divide-y divide-[var(--border-default)]'>
        {!invariants && !error && <p className='text-xs text-muted p-4'>Loading…</p>}
        {invariants && invariants.length === 0 && (
          <p className='text-xs text-muted p-4'>
            No invariants defined. Add one to <code>.executespec/arch/invariants.yaml</code> in any
            linked repo.
          </p>
        )}
        {invariants?.map((inv) => {
          const isOpen = expanded === inv.id;
          const violationCount = violationsByInvariant[inv.id] ?? 0;
          return (
            <div key={inv.id}>
              <button
                onClick={() => setExpanded(isOpen ? null : inv.id)}
                className='w-full text-left px-3 py-2.5 hover:bg-[var(--gray-200)] transition-colors flex items-baseline gap-3'
              >
                <span
                  className={cn(
                    'inline-block px-1.5 py-0.5 rounded-sm text-[10px] uppercase tracking-wider border',
                    inv.status === 'ACTIVE'
                      ? 'bg-status-success/15 text-status-success border-status-success/30'
                      : 'bg-[var(--gray-300)] text-muted border-[var(--border-default)]',
                  )}
                >
                  {inv.status}
                </span>
                <span className='text-xs font-mono text-secondary truncate flex-1'>
                  {inv.invariantId}
                </span>
                {violationCount > 0 && (
                  <span className='text-[11px] text-status-error font-semibold'>
                    {violationCount} violation{violationCount === 1 ? '' : 's'}
                  </span>
                )}
                <span className='text-muted text-xs'>{isOpen ? '−' : '+'}</span>
              </button>
              {isOpen && (
                <div className='px-3 pb-3 space-y-2 text-xs'>
                  <pre className='bg-[var(--gray-300)] rounded-sm p-2 overflow-x-auto text-secondary text-[11px] whitespace-pre-wrap font-mono'>
                    {inv.invariantYaml}
                  </pre>
                  {violationCount > 0 && (
                    <details className='text-secondary'>
                      <summary className='cursor-pointer text-status-error'>
                        {violationCount} recent violation{violationCount === 1 ? '' : 's'}
                      </summary>
                      <ul className='mt-2 space-y-1'>
                        {violations
                          ?.filter((v) => v.invariantUuid === inv.id)
                          .slice(0, 10)
                          .map((v) => (
                            <li key={v.id} className='text-[11px] text-muted'>
                              <span className='font-mono'>{v.detectedAt}</span> · {v.phase}
                              {v.detail && <span className='ml-1'>— {v.detail.slice(0, 200)}</span>}
                            </li>
                          ))}
                      </ul>
                    </details>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Cross-invariant violation feed (shown only when violations exist
          but the user hasn't expanded any specific invariant yet) */}
      {violations && violations.length > 0 && expanded === null && (
        <div className='rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-3'>
          <p className='text-xs font-medium text-secondary mb-2'>
            Recent violations across all invariants
          </p>
          <ul className='space-y-1 text-[11px]'>
            {violations.slice(0, 10).map((v) => (
              <li key={v.id} className='text-muted'>
                <span className='font-mono'>{v.detectedAt}</span> · {v.phase}
                {v.detail && <span className='ml-1'>— {v.detail.slice(0, 150)}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
