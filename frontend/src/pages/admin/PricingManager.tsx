import { useState, useEffect } from 'react';
import { Save, Loader2, Check } from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import PageShell from '@/components/shared/PageShell';
import AccessDenied from '@/components/shared/AccessDenied';
import { useAuthStore, selectIsSuperAdmin } from '@/stores/useAuthStore';
import { getAllPricing, updateModelPricing, type PricingRow  } from '@/lib/api';
import { cn } from '@/lib/utils';

export default function PricingManager() {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const userLoaded = useAuthStore((s) => s.user !== null);
  const [rows, setRows] = useState<PricingRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [edits, setEdits] = useState<Record<string, Partial<PricingRow>>>({});
  const [saving, setSaving] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  useEffect(() => {
    getAllPricing()
      .then(setRows)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, []);

  // R1 (2026-05-01 follow-up) — render AccessDenied in place instead of
  // redirecting to "/" (which bounces to /projects/empty for non-admins
  // via V3HomeRedirect, leaving them confused). The userLoaded check
  // prevents a flash of AccessDenied during auth hydration.
  if (userLoaded && !isSuperAdmin) {
    return (
      <AccessDenied
        feature="Pricing manager"
        requiredRole="SUPER_ADMIN"
      />
    );
  }
  if (!userLoaded) return null;

  const key = (r: PricingRow) => `${r.provider}/${r.model}`;

  const handleEdit = (r: PricingRow, field: 'inputCostPer1m' | 'outputCostPer1m' | 'notes', value: string) => {
    const k = key(r);
    setEdits(prev => ({
      ...prev,
      [k]: {
        ...prev[k],
        [field]: field === 'notes' ? value : parseFloat(value) || 0,
      },
    }));
  };

  const handleSave = async (r: PricingRow) => {
    const k = key(r);
    const edit = edits[k];
    if (!edit) return;

    setSaving(k);
    try {
      const updated = await updateModelPricing(r.provider, r.model, {
        inputCostPer1m: edit.inputCostPer1m ?? r.inputCostPer1m,
        outputCostPer1m: edit.outputCostPer1m ?? r.outputCostPer1m,
        notes: (edit.notes as string) ?? r.notes,
      });
      setRows(prev => prev.map(row => key(row) === k ? updated : row));
      setEdits(prev => { const next = { ...prev }; delete next[k]; return next; });
      setSaved(k);
      setTimeout(() => setSaved(null), 2000);
    } catch (e) {
      console.error('Save failed', e);
    } finally {
      setSaving(null);
    }
  };

  const getValue = (r: PricingRow, field: 'inputCostPer1m' | 'outputCostPer1m' | 'notes'): string | number => {
    const k = key(r);
    const edit = edits[k];
    if (edit && field in edit) return edit[field] as string | number ?? '';
    return r[field] ?? '';
  };

  const isDirty = (r: PricingRow) => !!edits[key(r)];

  // Group by provider
  const byProvider = rows.reduce<Record<string, PricingRow[]>>((acc, r) => {
    (acc[r.provider] ??= []).push(r);
    return acc;
  }, {});

  return (
    <PageShell
      eyebrow="Admin · Pricing"
      title="Model cost matrix."
      accent="cost"
      description="Edit input + output rates per 1M tokens. Changes take effect immediately on new runs."
      hero
    >
      {loading ? (
        <div className="flex items-center justify-center py-20">
          <div className="animate-spin h-6 w-6 border-2 border-[var(--gray-400)] border-t-[#8B5CF6] rounded-full" />
        </div>
      ) : (
        Object.entries(byProvider).map(([provider, models]) => (
          <GlassPanel key={provider} className="p-4">
            <p className="text-xs font-semibold text-secondary uppercase tracking-wider mb-3">{provider}</p>
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b border-[var(--border-default)]">
                    <th className="text-left py-2 px-2 text-muted font-medium">Model</th>
                    <th className="text-right py-2 px-2 text-muted font-medium">Input $/1M</th>
                    <th className="text-right py-2 px-2 text-muted font-medium">Output $/1M</th>
                    <th className="text-left py-2 px-2 text-muted font-medium">Notes</th>
                    <th className="text-center py-2 px-2 text-muted font-medium w-16"></th>
                  </tr>
                </thead>
                <tbody>
                  {models.map(r => (
                    <tr key={key(r)} className="border-b border-[var(--gray-200)] hover:bg-[var(--gray-100)] transition-colors">
                      <td className="py-2 px-2 text-primary font-mono">{r.model}</td>
                      <td className="py-2 px-2 text-right">
                        <input
                          type="number"
                          step="0.01"
                          value={getValue(r, 'inputCostPer1m')}
                          onChange={e => handleEdit(r, 'inputCostPer1m', e.target.value)}
                          className="w-24 text-right rounded border border-[var(--border-default)] bg-[var(--gray-200)] px-2 py-1 text-xs text-primary focus:outline-none focus:ring-1 focus:ring-[#8B5CF6]/50"
                        />
                      </td>
                      <td className="py-2 px-2 text-right">
                        <input
                          type="number"
                          step="0.01"
                          value={getValue(r, 'outputCostPer1m')}
                          onChange={e => handleEdit(r, 'outputCostPer1m', e.target.value)}
                          className="w-24 text-right rounded border border-[var(--border-default)] bg-[var(--gray-200)] px-2 py-1 text-xs text-primary focus:outline-none focus:ring-1 focus:ring-[#8B5CF6]/50"
                        />
                      </td>
                      <td className="py-2 px-2">
                        <input
                          type="text"
                          value={String(getValue(r, 'notes') ?? '')}
                          onChange={e => handleEdit(r, 'notes', e.target.value)}
                          className="w-full rounded border border-[var(--border-default)] bg-[var(--gray-200)] px-2 py-1 text-xs text-secondary focus:outline-none focus:ring-1 focus:ring-[#8B5CF6]/50"
                          placeholder="e.g. WORKHORSE tier"
                        />
                      </td>
                      <td className="py-2 px-2 text-center">
                        {isDirty(r) && (
                          <button
                            onClick={() => handleSave(r)}
                            disabled={saving === key(r)}
                            className="inline-flex items-center gap-1 px-2 py-1 rounded text-[10px] font-medium bg-[#8B5CF6]/20 text-[#8B5CF6] hover:bg-[#8B5CF6]/30 disabled:opacity-50"
                          >
                            {saving === key(r) ? <Loader2 className="h-3 w-3 animate-spin" /> : <Save className="h-3 w-3" />}
                            Save
                          </button>
                        )}
                        {saved === key(r) && (
                          <span className="inline-flex items-center gap-1 text-status-success text-[10px]">
                            <Check className="h-3 w-3" /> Saved
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-[10px] text-muted mt-2">
              Last updated: {models[0]?.updatedAt ? new Date(models[0].updatedAt).toLocaleString() : 'unknown'}
            </p>
          </GlassPanel>
        ))
      )}
    </PageShell>
  );
}
