import { useEffect, useState } from 'react';
import { Calculator, Download, TrendingUp, IndianRupee } from 'lucide-react';
import { useTierPermissions } from '@/hooks/useTierPermissions';
import {
  getRevenueMetrics, getGstSummary, getTrialBalance, getGstr1ExportUrl,
  formatPaise,
  type RevenueMetrics, type GstSummary, type TrialBalanceEntry,
} from '@/lib/invoice-api';

function currentFy(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth(); // 0-based
  if (month >= 3) { // April onwards
    return `${String(year % 100).padStart(2, '0')}${String((year + 1) % 100).padStart(2, '0')}`;
  }
  return `${String((year - 1) % 100).padStart(2, '0')}${String(year % 100).padStart(2, '0')}`;
}

function currentQuarter(): number {
  const month = new Date().getMonth(); // 0-based
  if (month >= 3 && month <= 5) return 1;
  if (month >= 6 && month <= 8) return 2;
  if (month >= 9 && month <= 11) return 3;
  return 4;
}

export default function AccountingPage() {
  const perms = useTierPermissions();
  const [metrics, setMetrics] = useState<RevenueMetrics | null>(null);
  const [gst, setGst] = useState<GstSummary | null>(null);
  const [trial, setTrial] = useState<TrialBalanceEntry[]>([]);
  const [fy] = useState(currentFy());
  const [quarter] = useState(currentQuarter());

  useEffect(() => {
    const from = new Date(new Date().getFullYear(), new Date().getMonth() >= 3 ? 3 : -9, 1)
      .toISOString().split('T')[0];
    const to = new Date().toISOString().split('T')[0];

    getRevenueMetrics(from, to).then(setMetrics).catch(() => {});
    getGstSummary(fy, quarter).then(setGst).catch(() => {});
    getTrialBalance(from, to).then(setTrial).catch(() => {});
  }, [fy, quarter]);

  if (!perms?.canViewAccounting) {
    return (
      <div className="flex h-full items-center justify-center text-slate-500">
        <p>403 — Accounting access required</p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Calculator className="h-6 w-6 text-violet-400" />
        <h1 className="text-xl font-semibold text-slate-100">Accounting</h1>
        <span className="ml-auto text-xs text-slate-500">FY {fy} &middot; Q{quarter}</span>
      </div>

      {/* Revenue metric cards */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <MetricCard label="MRR" value={metrics?.mrr} />
        <MetricCard label="ARR" value={metrics?.arr} />
        <MetricCard label="Total Revenue" value={metrics?.totalRevenue} />
        <MetricCard label="Total Collections" value={metrics?.totalCollections} />
      </div>

      {/* GST summary */}
      <div className="rounded-xl border border-surface-700 bg-surface-800 p-6">
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-slate-200">GST Summary — Q{quarter}</h3>
          {perms?.canExportGstr && (
            <a
              href={getGstr1ExportUrl(fy, quarter)}
              className="flex items-center gap-1.5 rounded-md bg-violet-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-violet-500"
            >
              <Download className="h-3.5 w-3.5" />
              Export GSTR-1
            </a>
          )}
        </div>
        {gst ? (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <div>
              <div className="text-xs text-slate-500">CGST</div>
              <div className="mt-1 text-lg font-semibold text-slate-200">{formatPaise(gst.cgstCollected)}</div>
            </div>
            <div>
              <div className="text-xs text-slate-500">SGST</div>
              <div className="mt-1 text-lg font-semibold text-slate-200">{formatPaise(gst.sgstCollected)}</div>
            </div>
            <div>
              <div className="text-xs text-slate-500">IGST</div>
              <div className="mt-1 text-lg font-semibold text-slate-200">{formatPaise(gst.igstCollected)}</div>
            </div>
            <div>
              <div className="text-xs text-slate-500">Total GST</div>
              <div className="mt-1 text-lg font-semibold text-violet-300">{formatPaise(gst.totalGst)}</div>
            </div>
          </div>
        ) : (
          <div className="text-sm text-slate-500">Loading...</div>
        )}
      </div>

      {/* Trial balance */}
      <div className="rounded-xl border border-surface-700 bg-surface-800 p-6">
        <h3 className="mb-4 text-sm font-semibold text-slate-200">Trial Balance</h3>
        {trial.length === 0 ? (
          <div className="text-sm text-slate-500">No entries found for this period</div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-surface-700 text-left text-xs text-slate-500">
                <th className="px-4 py-2">Account</th>
                <th className="px-4 py-2 text-right">Debit</th>
                <th className="px-4 py-2 text-right">Credit</th>
                <th className="px-4 py-2 text-right">Balance</th>
              </tr>
            </thead>
            <tbody>
              {trial.map((entry) => (
                <tr key={entry.account} className="border-b border-surface-700/50">
                  <td className="px-4 py-2 text-slate-300">{entry.account}</td>
                  <td className="px-4 py-2 text-right font-mono text-slate-400">{formatPaise(entry.debitTotal)}</td>
                  <td className="px-4 py-2 text-right font-mono text-slate-400">{formatPaise(entry.creditTotal)}</td>
                  <td className="px-4 py-2 text-right font-mono text-slate-200">
                    {formatPaise(Math.abs(entry.debitTotal - entry.creditTotal))}
                    <span className="ml-1 text-xs text-slate-500">
                      {entry.debitTotal > entry.creditTotal ? 'DR' : 'CR'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function MetricCard({ label, value }: { label: string; value?: number }) {
  return (
    <div className="rounded-xl border border-surface-700 bg-surface-800 p-4">
      <div className="flex items-center gap-2 text-xs text-slate-500">
        <TrendingUp className="h-3.5 w-3.5" />
        {label}
      </div>
      <div className="mt-2 text-xl font-bold text-slate-100">
        {value != null ? formatPaise(value) : '—'}
      </div>
    </div>
  );
}
