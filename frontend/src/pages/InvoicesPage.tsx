import { useEffect, useState, useCallback } from 'react';
import { Download, Send, CreditCard } from 'lucide-react';
import PageShell from '@/components/shared/PageShell';
import { useAuthStore } from '@/stores/useAuthStore';
import { useTierPermissions } from '@/hooks/useTierPermissions';
import {
  getMyInvoices, getAllInvoices, resendInvoice, downloadInvoicePdf,
  type Invoice, formatPaise,
} from '@/lib/invoice-api';

export default function InvoicesPage() {
  const user = useAuthStore((s) => s.user);
  const perms = useTierPermissions();
  const isAdmin = perms?.canViewAccounting || user?.globalRole === 'SUPER_ADMIN';
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<'all' | 'SUBSCRIPTION' | 'TOPUP' | 'CREDIT_NOTE'>('all');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = isAdmin
        ? await getAllInvoices(page, 20)
        : await getMyInvoices(page, 20);
      setInvoices(result.content);
      setTotalPages(result.totalPages);
    } catch {
      // handled
    } finally {
      setLoading(false);
    }
  }, [page, isAdmin]);

  useEffect(() => { load(); }, [load]);

  const filtered = filter === 'all' ? invoices : invoices.filter(i => i.invoiceType === filter);

  const handleDownload = async (inv: Invoice) => {
    try {
      await downloadInvoicePdf(inv.id, inv.invoiceNumber, !!isAdmin);
    } catch {
      // handled
    }
  };

  const handleResend = async (inv: Invoice) => {
    try {
      await resendInvoice(inv.id);
    } catch {
      // handled
    }
  };

  const statusColor = (status: string) => {
    switch (status) {
      case 'PAID': return 'text-status-success bg-status-success/10';
      case 'DRAFT': return 'text-secondary bg-[var(--gray-200)]';
      case 'ISSUED': return 'text-accent-blue bg-accent-blue/10';
      case 'VOID': return 'text-status-error bg-status-error/10';
      default: return 'text-secondary bg-[var(--gray-200)]';
    }
  };

  const typeLabel = (type: string) => {
    switch (type) {
      case 'SUBSCRIPTION': return 'Subscription';
      case 'TOPUP': return 'Top-up';
      case 'CREDIT_NOTE': return 'Credit Note';
      default: return type;
    }
  };

  return (
    <PageShell
      eyebrow="Invoices"
      title="Every receipt, right here."
      accent="receipt,"
      description="Download PDFs, resend to your AP team, filter by type."
      hero
      actions={
        <div className="flex items-center gap-1.5 flex-wrap">
          {(['all', 'SUBSCRIPTION', 'TOPUP', 'CREDIT_NOTE'] as const).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`rounded-md px-3 py-1.5 text-xs font-medium transition ${
                filter === f
                  ? 'bg-[color-mix(in_srgb,var(--accent-blue)_20%,transparent)] text-accent-blue border border-[color-mix(in_srgb,var(--accent-blue)_40%,transparent)]'
                  : 'text-muted hover:text-secondary border border-[var(--border-default)] hover:border-[var(--border-hover)]'
              }`}
            >
              {f === 'all' ? 'All' : typeLabel(f)}
            </button>
          ))}
        </div>
      }
    >

      {/* Table — desktop view */}
      <div className="rounded-sm border border-surface-700 bg-surface-800">
        <div className="hidden md:block overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-surface-700 text-left text-xs text-muted">
                <th className="px-4 py-3">Invoice #</th>
                <th className="px-4 py-3">Date</th>
                <th className="px-4 py-3">Type</th>
                <th className="px-4 py-3 hidden md:table-cell">Description</th>
                <th className="px-4 py-3 text-right">Amount</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={7} className="px-4 py-8 text-center text-muted">Loading...</td>
                </tr>
              ) : filtered.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-8 text-center text-muted">No invoices found</td>
                </tr>
              ) : (
                filtered.map((inv) => (
                  <tr key={inv.id} className="border-b border-surface-700/50 hover:bg-surface-700/30">
                    <td className="px-4 py-3 font-mono text-xs text-secondary">{inv.invoiceNumber}</td>
                    <td className="px-4 py-3 text-muted">{inv.invoiceDate}</td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center gap-1 text-xs ${
                        inv.invoiceType === 'CREDIT_NOTE' ? 'text-accent-amber' : 'text-muted'
                      }`}>
                        {inv.invoiceType === 'CREDIT_NOTE' && <CreditCard className="h-3 w-3" />}
                        {typeLabel(inv.invoiceType)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-secondary max-w-[200px] truncate hidden md:table-cell">{inv.description}</td>
                    <td className="px-4 py-3 text-right font-mono text-slate-200">{formatPaise(inv.totalAmount)}</td>
                    <td className="px-4 py-3">
                      <span className={`rounded-sm px-2 py-0.5 text-xs font-medium ${statusColor(inv.status)}`}>
                        {inv.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          onClick={() => handleDownload(inv)}
                          className="rounded p-1 text-muted hover:bg-surface-600 hover:text-slate-200"
                          title="Download PDF"
                        >
                          <Download className="h-4 w-4" />
                        </button>
                        {isAdmin && (
                          <button
                            onClick={() => handleResend(inv)}
                            className="rounded p-1 text-muted hover:bg-surface-600 hover:text-slate-200"
                            title="Resend email"
                          >
                            <Send className="h-4 w-4" />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Mobile cards — table doesn't survive the 375px squeeze (dates split to 3 lines, columns disappear).
            Cards keep every field visible in a tap-friendly layout. */}
        <ul className="md:hidden divide-y divide-surface-700">
          {loading ? (
            <li className="px-4 py-8 text-center text-muted text-sm">Loading...</li>
          ) : filtered.length === 0 ? (
            <li className="px-4 py-8 text-center text-muted text-sm">No invoices found</li>
          ) : (
            filtered.map((inv) => (
              <li key={inv.id} className="px-4 py-3 space-y-2">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-mono text-xs text-secondary truncate">{inv.invoiceNumber}</p>
                    <p className="text-[11px] text-muted mt-0.5">{inv.invoiceDate} · {typeLabel(inv.invoiceType)}</p>
                  </div>
                  <span className={`rounded-sm px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider ${statusColor(inv.status)}`}>
                    {inv.status}
                  </span>
                </div>
                {inv.description && (
                  <p className="text-xs text-secondary line-clamp-2">{inv.description}</p>
                )}
                <div className="flex items-center justify-between pt-1">
                  <span className="font-mono text-sm text-slate-200">{formatPaise(inv.totalAmount)}</span>
                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => handleDownload(inv)}
                      className="flex items-center gap-1 rounded-sm border border-surface-700 px-2.5 py-1 text-xs text-muted hover:bg-surface-700 hover:text-slate-200"
                    >
                      <Download className="h-3.5 w-3.5" />
                      PDF
                    </button>
                    {isAdmin && (
                      <button
                        onClick={() => handleResend(inv)}
                        className="flex items-center gap-1 rounded-sm border border-surface-700 px-2.5 py-1 text-xs text-muted hover:bg-surface-700 hover:text-slate-200"
                      >
                        <Send className="h-3.5 w-3.5" />
                        Resend
                      </button>
                    )}
                  </div>
                </div>
              </li>
            ))
          )}
        </ul>

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-surface-700 px-4 py-3">
            <button
              disabled={page === 0}
              onClick={() => setPage(p => p - 1)}
              className="rounded-md px-3 py-1 text-xs text-muted hover:bg-surface-700 disabled:opacity-50"
            >
              Previous
            </button>
            <span className="text-xs text-muted">Page {page + 1} of {totalPages}</span>
            <button
              disabled={page >= totalPages - 1}
              onClick={() => setPage(p => p + 1)}
              className="rounded-md px-3 py-1 text-xs text-muted hover:bg-surface-700 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        )}
      </div>
    </PageShell>
  );
}
