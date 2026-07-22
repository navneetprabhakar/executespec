import {useEffect, useMemo, useState} from 'react';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';

const DIMENSIONS: Array<[api.UsageAuditDimension, string]> = [
  ['user', 'User'],
  ['userRole', 'User role'],
  ['project', 'Project'],
  ['feature', 'Feature'],
  ['provider', 'Provider'],
  ['model', 'Model'],
  ['role', 'Agent role'],
  ['run', 'Run'],
  ['session', 'Session'],
  ['keySource', 'Key source'],
  ['status', 'Status'],
];

export interface UsageAuditSummaryRow {
  key: string;
  label: string;
  attempts: number;
  failedAttempts: number;
  tokens: number;
  cost: number;
  unknownCostAttempts: number;
}

// RR-1370 — map a reconciliation status to a severity badge so match / mismatch /
// indeterminate / unavailable states are visually distinct, not hidden.
export function reconciliationBadgeKind(status: string): 'ok' | 'warn' | 'bad' {
  const s = (status || '').toUpperCase();
  if (s.includes('MATCH') || s === 'OK' || s === 'RECONCILED') return 'ok';
  if (s.includes('MISMATCH') || s.includes('FAIL')) return 'bad';
  return 'warn';
}

function ReconciliationCard({
  reconciliation,
}: {
  reconciliation: api.LlmUsageAuditReport['reconciliation'];
}) {
  const rows: Array<{label: string; status: string; explanation?: string}> = [
    {
      label: 'Physical ledger vs run totals',
      status: reconciliation.runTotals.status,
      explanation: reconciliation.runTotals.explanation,
    },
    {
      label: 'Physical ledger vs managed-AI debits',
      status: reconciliation.managedAiEvents.status,
      explanation: reconciliation.managedAiEvents.explanation,
    },
    {label: 'Provider invoice', status: reconciliation.providerInvoiceStatus},
  ];
  return (
    <div className='ide-card' style={{marginBottom: 12}}>
      <div style={{fontSize: 12.5, fontWeight: 700, marginBottom: 8}}>Reconciliation</div>
      <div style={{display: 'flex', flexDirection: 'column', gap: 8}}>
        {rows.map((row) => (
          <div key={row.label} style={{display: 'flex', gap: 8, alignItems: 'flex-start'}}>
            <IdeBadge kind={reconciliationBadgeKind(row.status)}>
              {row.status || 'UNKNOWN'}
            </IdeBadge>
            <div style={{minWidth: 0}}>
              <div style={{fontSize: 12.5, color: 'var(--ide-fg-bright)'}}>{row.label}</div>
              {row.explanation && (
                <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>{row.explanation}</div>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function dimensionValue(
  row: api.LlmUsageRollup,
  dimension: api.UsageAuditDimension,
): string | null {
  switch (dimension) {
    case 'user':
      return row.userEmail || row.userId;
    case 'userRole':
      return row.userRole;
    case 'project':
      return row.projectName || row.projectId;
    case 'feature':
      return row.feature;
    case 'provider':
      return row.provider;
    case 'model':
      return row.model;
    case 'role':
      return row.agentRole;
    case 'run':
      return row.runId;
    case 'session':
      return row.sessionId;
    case 'keySource':
      return row.keySource;
    case 'status':
      return row.attemptStatus;
    default:
      return null;
  }
}

/** Pure client projection over server-owned rollups; never recomputes provider cost formulas. */
export function summarizeUsageRollups(
  rows: api.LlmUsageRollup[],
  dimension: api.UsageAuditDimension,
): UsageAuditSummaryRow[] {
  const grouped = new Map<string, UsageAuditSummaryRow>();
  for (const row of rows) {
    const label = dimensionValue(row, dimension) || 'Unattributed';
    const current = grouped.get(label) ?? {
      key: label,
      label,
      attempts: 0,
      failedAttempts: 0,
      tokens: 0,
      cost: 0,
      unknownCostAttempts: 0,
    };
    current.attempts += row.attempts;
    if (row.attemptStatus === 'FAILED') current.failedAttempts += row.attempts;
    // Reasoning is a subset of normalized output tokens, not a third billable token bucket.
    current.tokens += row.inputTokens + row.outputTokens;
    current.cost += Number(row.knownCostUsdLowerBound || 0);
    current.unknownCostAttempts += row.unknownCostAttempts;
    grouped.set(label, current);
  }
  return [...grouped.values()].sort(
    (a, b) => b.cost - a.cost || b.attempts - a.attempts || a.label.localeCompare(b.label),
  );
}

function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

function defaultRange(): {from: string; to: string} {
  const to = new Date();
  const from = new Date(to);
  from.setUTCDate(from.getUTCDate() - 29);
  return {from: isoDate(from), to: isoDate(to)};
}

function fmt(value: number): string {
  return new Intl.NumberFormat(undefined, {maximumFractionDigits: 0}).format(value);
}

export function LlmUsageAuditPanel({orgId, scopeLabel}: {orgId: string; scopeLabel: string}) {
  const initial = useMemo(defaultRange, []);
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [timezone, setTimezone] = useState(
    () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
  );
  const [page, setPage] = useState(0);
  const [dimension, setDimension] = useState<api.UsageAuditDimension>('user');
  const [report, setReport] = useState<api.LlmUsageAuditReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [download, setDownload] = useState<'csv' | 'json' | null>(null);
  const [error, setError] = useState('');
  // RR-1369 — disclose export truncation so a partial CSV/JSON is not saved as if
  // it were the complete audit evidence.
  const [downloadNote, setDownloadNote] = useState('');

  const params = useMemo(
    () => ({orgId, from, to, timezone, page, size: 100}),
    [orgId, from, to, timezone, page],
  );

  const load = (requestedPage = page) => {
    setLoading(true);
    setError('');
    api
      .getLlmUsageAudit({...params, page: requestedPage})
      .then((next) => {
        setPage(requestedPage);
        setReport(next);
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Usage report failed.'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    setPage(0);
    setReport(null);
  }, [orgId]);

  const rows = useMemo(
    () => summarizeUsageRollups(report?.rollups ?? [], dimension),
    [report?.rollups, dimension],
  );

  async function runDownload(kind: 'csv' | 'json') {
    setDownload(kind);
    setError('');
    setDownloadNote('');
    try {
      if (kind === 'csv') {
        const meta = await api.downloadLlmUsageAuditCsv(params);
        // RR-1369 — the CSV endpoint caps at its row limit and reports truncation.
        if (meta.truncated) {
          const limit = meta.rowLimit != null ? meta.rowLimit.toLocaleString() : 'the export limit';
          setDownloadNote(
            `CSV truncated at ${limit} rows${
              report ? ` of ${report.page.totalRows.toLocaleString()} total` : ''
            }. Narrow the window to export the rest.`,
          );
        } else {
          setDownloadNote('CSV export complete.');
        }
      } else {
        await api.downloadLlmUsageAuditJson(params);
        // RR-1369 — the JSON export is always the first page of 500 rows.
        const total = report?.page.totalRows ?? null;
        if (total != null && total > api.JSON_EXPORT_ROW_LIMIT) {
          setDownloadNote(
            `JSON export contains only the first ${api.JSON_EXPORT_ROW_LIMIT} of ${total.toLocaleString()} rows. Use CSV or narrow the window for the rest.`,
          );
        } else {
          setDownloadNote(`JSON export complete (first ${api.JSON_EXPORT_ROW_LIMIT} rows max).`);
        }
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Download failed.');
    } finally {
      setDownload(null);
    }
  }

  return (
    <section aria-label='LLM usage audit'>
      <div className='ide-h2'>LLM token and cost audit</div>
      <div className='ide-card'>
        <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', marginBottom: 12}}>
          {scopeLabel}. Physical provider attempts include retries and failures. Cost is explicitly
          a lower bound whenever a provider did not report failed-attempt cost.
        </div>
        <div style={{display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end'}}>
          <label style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
            From
            <input
              aria-label='Usage from date'
              className='ide-input'
              type='date'
              value={from}
              onChange={(e) => setFrom(e.target.value)}
            />
          </label>
          <label style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
            To
            <input
              aria-label='Usage to date'
              className='ide-input'
              type='date'
              value={to}
              onChange={(e) => setTo(e.target.value)}
            />
          </label>
          <label style={{fontSize: 11, color: 'var(--ide-fg-muted)', flex: 1, minWidth: 180}}>
            IANA timezone
            <input
              aria-label='Usage timezone'
              className='ide-input'
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
              placeholder='Asia/Kolkata'
            />
          </label>
          <button className='ide-btn' type='button' disabled={loading} onClick={() => load(0)}>
            {loading ? 'Loading…' : 'Run report'}
          </button>
          <button
            className='ide-btn ghost'
            type='button'
            disabled={download !== null}
            onClick={() => runDownload('csv')}
          >
            {download === 'csv' ? 'Downloading…' : 'Download CSV'}
          </button>
          <button
            className='ide-btn ghost'
            type='button'
            disabled={download !== null}
            onClick={() => runDownload('json')}
          >
            {download === 'json' ? 'Downloading…' : 'Download JSON (first 500)'}
          </button>
        </div>
        {error && (
          <div style={{color: 'var(--ide-red)', fontSize: 12.5, marginTop: 10}}>{error}</div>
        )}
        {downloadNote && (
          <div role='status' style={{color: 'var(--ide-yellow)', fontSize: 12.5, marginTop: 10}}>
            {downloadNote}
          </div>
        )}
      </div>

      {loading && <IdeLoader label='Loading usage attempts…' />}
      {report && !loading && (
        <>
          <div style={{display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12}}>
            {[
              ['Attempts', fmt(report.totals.attempts)],
              ['Failed', fmt(report.totals.failedAttempts)],
              ['Missing terminal', fmt(report.totals.missingTerminalAttempts)],
              ['Tokens', fmt(report.totals.inputTokens + report.totals.outputTokens)],
              ['Reasoning (of output)', fmt(report.totals.reasoningTokens)],
              ['Cache read', fmt(report.totals.cacheReadTokens)],
              ['Known cost', `$${Number(report.totals.knownCostUsdLowerBound).toFixed(4)}`],
            ].map(([label, value]) => (
              <div className='ide-card' key={label} style={{minWidth: 120, flex: 1}}>
                <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>{label}</div>
                <div style={{fontSize: 19, fontWeight: 700, marginTop: 3}}>{value}</div>
              </div>
            ))}
          </div>

          {report.totals.hasUnknownProviderCost && (
            <div className='ide-card' style={{color: 'var(--ide-yellow)', fontSize: 12.5}}>
              {fmt(report.totals.unknownCostAttempts)} attempt(s) have unknown provider cost. The
              displayed USD amount is a lower bound, not a reconciled invoice total.
            </div>
          )}

          {/* RR-1370 — surface the computed reconciliation evidence (physical ledger
              vs run totals vs managed debits, plus provider-invoice availability). */}
          <ReconciliationCard reconciliation={report.reconciliation} />

          <div className='ide-card' style={{marginBottom: 12}}>
            <div style={{display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap'}}>
              <strong style={{fontSize: 12.5}}>Group by</strong>
              <select
                className='ide-input'
                aria-label='Usage grouping dimension'
                value={dimension}
                onChange={(e) => setDimension(e.target.value as api.UsageAuditDimension)}
                style={{width: 180}}
              >
                {DIMENSIONS.map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
              {report.limits.rollupsTruncated && (
                <IdeBadge kind='warn'>Rollups truncated at {report.limits.rollupRowLimit}</IdeBadge>
              )}
            </div>
            <div style={{overflowX: 'auto', marginTop: 10}}>
              <table className='ide-table' style={{width: '100%'}}>
                <thead>
                  <tr>
                    <th>{DIMENSIONS.find(([value]) => value === dimension)?.[1]}</th>
                    <th>Attempts</th>
                    <th>Failed</th>
                    <th>Tokens</th>
                    <th>Known cost</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.key}>
                      <td className='ide-mono'>{row.label}</td>
                      <td>{fmt(row.attempts)}</td>
                      <td>{fmt(row.failedAttempts)}</td>
                      <td>{fmt(row.tokens)}</td>
                      <td>
                        ${row.cost.toFixed(4)}
                        {row.unknownCostAttempts > 0 ? ' +' : ''}
                      </td>
                    </tr>
                  ))}
                  {rows.length === 0 && (
                    <tr>
                      <td colSpan={5}>No usage in this window.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>

          <div className='ide-card'>
            <div style={{display: 'flex', justifyContent: 'space-between', gap: 8}}>
              <strong style={{fontSize: 12.5}}>Physical attempts</strong>
              <span style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>
                {report.page.totalRows} total · page {report.page.number + 1} of{' '}
                {Math.max(1, report.page.totalPages)}
              </span>
            </div>
            <div style={{overflowX: 'auto', marginTop: 10}}>
              <table className='ide-table' style={{width: '100%'}}>
                <thead>
                  <tr>
                    <th>Time</th>
                    <th>User</th>
                    <th>Feature / role</th>
                    <th>Provider / model</th>
                    <th>Attempt</th>
                    <th>Status</th>
                    <th>Tokens</th>
                    <th>Cost</th>
                    <th>AI credits</th>
                  </tr>
                </thead>
                <tbody>
                  {report.attempts.map((attempt) => (
                    <tr key={attempt.id}>
                      <td>{new Date(attempt.createdAt).toLocaleString()}</td>
                      <td>{attempt.userEmail || attempt.userId || 'Unattributed'}</td>
                      <td>{`${attempt.feature || 'Unknown'} / ${attempt.agentRole || 'Unknown'}`}</td>
                      <td>{`${attempt.provider || 'Unknown'} / ${attempt.model || 'Unknown'} / ${attempt.modelTier}`}</td>
                      <td
                        className='ide-mono'
                        title={`logical=${attempt.logicalCallId}\nphysical=${attempt.physicalAttemptId}\ncorrelation=${attempt.correlationId}`}
                      >
                        {attempt.attemptKind} #{attempt.attemptOrdinal}
                      </td>
                      <td>{attempt.attemptStatus}</td>
                      <td
                        title={`${fmt(attempt.reasoningTokens)} reasoning tokens are included in output`}
                      >
                        {fmt(attempt.inputTokens + attempt.outputTokens)}
                      </td>
                      <td>
                        {attempt.knownCostUsd === null
                          ? 'Unknown'
                          : `$${Number(attempt.knownCostUsd).toFixed(4)}`}
                      </td>
                      <td title={attempt.managedAiProjectionStatus}>
                        {attempt.managedAiProjectionStatus === 'RECORDED'
                          ? fmt(attempt.managedAiCredits)
                          : attempt.managedAiProjectionStatus}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div style={{display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 10}}>
              <button
                className='ide-btn ghost'
                type='button'
                disabled={page === 0}
                onClick={() => load(Math.max(0, page - 1))}
              >
                Previous
              </button>
              <button
                className='ide-btn ghost'
                type='button'
                disabled={page + 1 >= report.page.totalPages}
                onClick={() => load(page + 1)}
              >
                Next
              </button>
            </div>
          </div>
        </>
      )}
    </section>
  );
}
