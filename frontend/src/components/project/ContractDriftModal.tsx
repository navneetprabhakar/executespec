import { useState } from 'react';
import ResponsiveModal from '@/components/shared/ResponsiveModal';
import {
  refreshContractFromSource,
  dismissContractDrift,
  type DriftCheckResult,
} from '@/lib/api';

/**
 * E2.9 — drift resolution modal. Shows the drift summary returned by
 * E2.8 + two actions: <em>Refresh from source</em> (opens a PR to update
 * the .executespec/ snapshot) and <em>Dismiss</em> (rationale ≥10 chars).
 *
 * UX: reuses {@link ResponsiveModal} (focus-trap + Escape + body-lock
 * already hardened). No new tokens or component primitives — buttons are
 * the existing brand-blue / muted pattern; rationale textarea is plain
 * styled input. Structural side-by-side diff is deferred — v1 shows the
 * server-side diff summary as text below the header.
 */
export default function ContractDriftModal({
  open,
  onClose,
  projectId,
  contractId,
  contractName,
  drift,
  onResolved,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  contractId: string;
  contractName?: string;
  drift: DriftCheckResult | null;
  onResolved?: () => void;
}) {
  const [rationale, setRationale] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [prUrl, setPrUrl] = useState<string | null>(null);

  const reset = () => { setRationale(''); setError(null); setPrUrl(null); setBusy(false); };
  const closeAndReset = () => { reset(); onClose(); };

  const doRefresh = async () => {
    if (busy) return;
    setBusy(true); setError(null);
    try {
      const res = await refreshContractFromSource(projectId, contractId);
      setPrUrl(res.prUrl);
      onResolved?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const doDismiss = async () => {
    if (busy) return;
    if (rationale.trim().length < 10) {
      setError('Rationale must be at least 10 characters.');
      return;
    }
    setBusy(true); setError(null);
    try {
      await dismissContractDrift(projectId, contractId, rationale.trim());
      onResolved?.();
      closeAndReset();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <ResponsiveModal open={open} onClose={closeAndReset} title="Resolve contract drift">
      <div className="p-4 space-y-3 min-w-[320px] sm:min-w-[480px] max-w-[640px]">
        <div>
          <h2 className="text-sm font-medium text-primary">
            Drift on {contractName ? <span className="font-mono">{contractName}</span> : 'contract'}
          </h2>
          <p className="text-[11px] text-muted mt-1">
            The .executespec/ snapshot diverges from the source path the
            contract was originally detected from. Refresh the snapshot
            (opens a PR) or dismiss with a rationale.
          </p>
        </div>

        {drift && (
          <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-2 text-[11px] font-mono text-muted whitespace-pre-wrap">
            {drift.summary}
          </div>
        )}

        {prUrl && (
          <div className="rounded-sm border border-status-success/40 bg-status-success/10 p-2 text-[11px] text-status-success">
            Refresh PR opened: <a href={prUrl} target="_blank" rel="noreferrer" className="underline">{prUrl}</a>
          </div>
        )}

        <div className="space-y-1">
          <label className="text-[11px] text-muted">Dismissal rationale (≥10 chars)</label>
          <textarea
            value={rationale}
            onChange={(e) => setRationale(e.target.value)}
            rows={3}
            className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-2 text-xs text-primary"
            placeholder="Why is this drift acceptable?"
          />
        </div>

        {error && <div className="text-xs text-accent-red">{error}</div>}

        <div className="flex items-center justify-end gap-2 pt-1">
          <button
            onClick={closeAndReset}
            disabled={busy}
            className="text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={doDismiss}
            disabled={busy || rationale.trim().length < 10}
            title="Mark every unresolved drift event for this contract as DISMISSED with the rationale below."
            className="text-[11px] px-2 py-1 rounded-sm border border-accent-amber/40 bg-accent-amber/10 text-accent-amber hover:bg-accent-amber/20 disabled:opacity-50"
          >
            {busy ? 'Working…' : 'Dismiss'}
          </button>
          <button
            onClick={doRefresh}
            disabled={busy}
            title="Open a PR overwriting the .executespec/ snapshot with current source content."
            className="text-[11px] px-2 py-1 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50"
          >
            {busy ? 'Working…' : 'Refresh from source'}
          </button>
        </div>
      </div>
    </ResponsiveModal>
  );
}
