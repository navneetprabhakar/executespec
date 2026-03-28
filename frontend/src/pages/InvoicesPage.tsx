import { useEffect, useState, useCallback } from 'react';
import { Receipt, Download, Send, CreditCard } from 'lucide-react';
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
      case 'PAID': return 'text-emerald-400 bg-emerald-500/10';
      case 'DRAFT': return 'text-zinc-400 bg-zinc-500/10';
      case 'ISSUED': return 'text-blue-400 bg-blue-500/10';
      case 'VOID': return 'text-red-400 bg-red-500/10';
      default: return 'text-zinc-400 bg-zinc-500/10';
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
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Receipt className="h-6 w-6 text-violet-400" />
          <h1 className="text-xl font-semibold text-slate-100">Invoices</h1>
        </div>
        <div className="flex items-center gap-2">
          {(['all', 'SUBSCRIPTION', 'TOPUP', 'CREDIT_NOTE'] as const).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`rounded-md px-3 py-1.5 text-xs font-medium transition ${
                filter === f
                  ? 'bg-violet-500/20 text-violet-300'
                  : 'text-slate-400 hover:bg-surface-700 hover:text-slate-300'
              }`}
            >
              {f === 'all' ? 'All' : typeLabel(f)}
            </button>
          ))}
        </div>
      </div>

      {/* Table */}
      <div className="rounded-sm border border-surface-700 bg-surface-800">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-surface-700 text-left text-xs text-slate-500">
                <th className="px-4 py-3">Invoice #</th>
                <th className="px-4 py-3">Date</th>
                <th className="px-4 py-3">Type</th>
                <th className="px-4 py-3">Description</th>
                <th className="px-4 py-3 text-right">Amount</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={7} className="px-4 py-8 text-center text-slate-500">Loading...</td>
                </tr>
              ) : filtered.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-8 text-center text-slate-500">No invoices found</td>
                </tr>
              ) : (
                filtered.map((inv) => (
                  <tr key={inv.id} className="border-b border-surface-700/50 hover:bg-surface-700/30">
                    <td className="px-4 py-3 font-mono text-xs text-slate-300">{inv.invoiceNumber}</td>
                    <td className="px-4 py-3 text-slate-400">{inv.invoiceDate}</td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center gap-1 text-xs ${
                        inv.invoiceType === 'CREDIT_NOTE' ? 'text-amber-400' : 'text-slate-400'
                      }`}>
                        {inv.invoiceType === 'CREDIT_NOTE' && <CreditCard className="h-3 w-3" />}
                        {typeLabel(inv.invoiceType)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-slate-300 max-w-[200px] truncate">{inv.description}</td>
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
                          className="rounded p-1 text-slate-400 hover:bg-surface-600 hover:text-slate-200"
                          title="Download PDF"
                        >
                          <Download className="h-4 w-4" />
                        </button>
                        {isAdmin && (
                          <button
                            onClick={() => handleResend(inv)}
                            className="rounded p-1 text-slate-400 hover:bg-surface-600 hover:text-slate-200"
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

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-surface-700 px-4 py-3">
            <button
              disabled={page === 0}
              onClick={() => setPage(p => p - 1)}
              className="rounded-md px-3 py-1 text-xs text-slate-400 hover:bg-surface-700 disabled:opacity-50"
            >
              Previous
            </button>
            <span className="text-xs text-slate-500">Page {page + 1} of {totalPages}</span>
            <button
              disabled={page >= totalPages - 1}
              onClick={() => setPage(p => p + 1)}
              className="rounded-md px-3 py-1 text-xs text-slate-400 hover:bg-surface-700 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
