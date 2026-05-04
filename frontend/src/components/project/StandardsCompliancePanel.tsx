import {useEffect, useState} from 'react';
import {errMsg} from '@/lib/utils';
import {
  getStandardsReport,
  type ComplianceReport,
  type StandardEntry,
  type ViolationEntry,
  type OverrideEntry,
} from '@/lib/api';

/**
 * E3.16 — read-only standards compliance summary for the Project
 * Knowledge group. Reuses the same card / chip / row layout as
 * TechDebtPanel + WorkspaceHealthPanel — no new tokens, primitives,
 * or fonts (UX-preservation contract).
 *
 * Three sections:
 * 1. Standards in effect (id + severity chip + source + locked badge)
 * 2. Recent violations (last N days, default 30) — per-standard rows
 * 3. Persistent overrides (NOT_APPLICABLE / SEVERITY_DOWNGRADE rows
 *    with rationale)
 *
 * Data fetched once on mount + on orgId/projectId change.
 */
export default function StandardsCompliancePanel({
  projectId,
  orgId,
}: {
  projectId: string;
  orgId: string;
}) {
  const [report, setReport] = useState<ComplianceReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    if (!orgId) return;
    setLoading(true);
    setError(null);
    try {
      setReport(await getStandardsReport(projectId, orgId, 30));
    } catch (e) {
      setError(e instanceof Error ? errMsg(e) : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, orgId]);

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3'>
      <div className='flex items-center justify-between mb-2'>
        <h3 className='text-sm font-medium text-primary'>Standards compliance</h3>
        <button
          onClick={load}
          disabled={loading}
          className='text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary disabled:opacity-50'
        >
          {loading ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {error && <div className='text-xs text-accent-red'>{error}</div>}

      {!error && report && (
        <>
          {/* In-effect standards */}
          <div className='text-[11px] text-muted mb-1'>
            {report.standards.length} standard{report.standards.length === 1 ? '' : 's'} in effect
          </div>
          {report.standards.length === 0 ? (
            <div className='text-[11px] text-muted'>
              No standards configured at the org level for {report.orgId}.
            </div>
          ) : (
            <div className='space-y-1 mb-3'>
              {report.standards.map((s) => (
                <StandardRow key={s.id} s={s} />
              ))}
            </div>
          )}

          {/* Recent violations */}
          <div className='mt-3 pt-2 border-t border-[var(--border-default)]'>
            <div className='text-[11px] text-muted mb-1'>
              Recent violations (last {report.sinceDays} days): {report.recentViolations.length}
            </div>
            {report.recentViolations.length > 0 && (
              <div className='space-y-1'>
                {report.recentViolations.slice(0, 10).map((v) => (
                  <ViolationRow key={v.id} v={v} />
                ))}
                {report.recentViolations.length > 10 && (
                  <div className='text-[10px] text-muted'>
                    +{report.recentViolations.length - 10} older violation
                    {report.recentViolations.length - 10 === 1 ? '' : 's'}
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Persistent overrides */}
          {report.persistentOverrides.length > 0 && (
            <div className='mt-3 pt-2 border-t border-[var(--border-default)]'>
              <div className='text-[11px] text-muted mb-1'>
                Persistent project overrides: {report.persistentOverrides.length}
              </div>
              <div className='space-y-1'>
                {report.persistentOverrides.map((o) => (
                  <OverrideRow key={o.id} o={o} />
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function StandardRow({s}: {s: StandardEntry}) {
  const sevCls =
    s.severity === 'BLOCKING'
      ? 'bg-accent-red/10 text-accent-red'
      : s.severity === 'WARNING'
        ? 'bg-accent-amber/10 text-accent-amber'
        : 'bg-accent-blue/10 text-accent-blue';
  return (
    <div className='flex items-center justify-between gap-2 text-xs py-1 border-b border-[var(--border-default)] last:border-b-0'>
      <div className='flex items-center gap-2 min-w-0'>
        <span className='font-mono truncate text-primary'>{s.id}</span>
        <span className='text-[11px] text-muted'>{s.enforcementType}</span>
      </div>
      <div className='flex items-center gap-1.5 shrink-0'>
        <span className={`inline-flex items-center rounded-sm px-1.5 py-0.5 text-[9px] ${sevCls}`}>
          {s.severity.toLowerCase()}
        </span>
        {s.overridden && (
          <span
            title={`Project downgraded from ${s.originalSeverity}`}
            className='inline-flex items-center rounded-sm bg-accent-amber/10 text-accent-amber px-1.5 py-0.5 text-[9px]'
          >
            ↓ from {s.originalSeverity.toLowerCase()}
          </span>
        )}
        {s.orgLevelLocked && (
          <span
            title='Org-level locked — cannot be overridden at project level'
            className='inline-flex items-center rounded-sm bg-[var(--gray-400)]/50 text-muted px-1.5 py-0.5 text-[9px]'
          >
            locked
          </span>
        )}
        <span className='inline-flex items-center rounded-sm bg-[var(--gray-400)]/30 text-muted px-1.5 py-0.5 text-[9px]'>
          {s.source.toLowerCase()}
        </span>
      </div>
    </div>
  );
}

function ViolationRow({v}: {v: ViolationEntry}) {
  const sevCls =
    v.severity === 'BLOCKING'
      ? 'bg-accent-red/10 text-accent-red'
      : v.severity === 'WARNING'
        ? 'bg-accent-amber/10 text-accent-amber'
        : 'bg-accent-blue/10 text-accent-blue';
  return (
    <div className='flex items-start justify-between gap-2 text-[11px] py-0.5'>
      <div className='flex items-start gap-2 min-w-0'>
        <span
          className={`shrink-0 inline-flex items-center rounded-sm px-1.5 py-0.5 text-[9px] ${sevCls}`}
        >
          {v.severity.toLowerCase()}
        </span>
        <span className='font-mono text-primary truncate'>{v.standardId}</span>
        <span className='text-muted truncate'>{v.summary}</span>
      </div>
      {v.overrideApplied && (
        <span className='shrink-0 inline-flex items-center rounded-sm bg-status-success/10 text-status-success px-1.5 py-0.5 text-[9px]'>
          overridden
        </span>
      )}
    </div>
  );
}

function OverrideRow({o}: {o: OverrideEntry}) {
  return (
    <div className='text-[11px] py-0.5'>
      <div className='flex items-center gap-2'>
        <span className='font-mono text-primary'>{o.standardId}</span>
        <span className='text-muted'>{o.overrideType}</span>
        {o.newSeverity && (
          <span className='text-[10px] text-muted'>→ {o.newSeverity.toLowerCase()}</span>
        )}
      </div>
      <div className='text-[10px] text-muted ml-1 mt-0.5 italic'>"{o.rationale}"</div>
    </div>
  );
}
