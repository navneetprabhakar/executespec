import { useState } from 'react';
import { updatePricing } from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';
import type { PricingRow } from '@/lib/api';
import { cn } from '@/lib/utils';

const ROLE_BADGES: Record<string, string> = {
  anthropic: 'text-accent-violet bg-accent-violet/10',
  openai:    'text-accent-cyan bg-accent-cyan/10',
  google:    'text-accent-emerald bg-accent-emerald/10',
};

const MODEL_ROLES: Record<string, string> = {
  'claude-opus-4-5-20251101':    'Architect',
  'claude-sonnet-4-5-20250929':  'Workhorse',
  'claude-haiku-4-5-20251022':   'Utility',
  'gpt-4o':                      'Capable',
  'gpt-4o-mini':                 'Utility',
  'gemini-2.5-pro':              'Architect',
  'gemini-2.0-flash':            'Workhorse',
};

interface EditCell {
  provider: string;
  model: string;
  field: 'inputCostPer1k' | 'outputCostPer1k';
  value: string;
}

interface PricingTableProps {
  rows: PricingRow[];
  onRefresh: () => void;
}

export default function PricingTable({ rows, onRefresh }: PricingTableProps) {
  const [editing, setEditing] = useState<EditCell | null>(null);
  const { addToast } = useUiStore();

  const saveEdit = async () => {
    if (!editing) return;
    try {
      await updatePricing(editing.provider, editing.model, {
        [editing.field]: parseFloat(editing.value),
      });
      addToast({ type: 'success', message: 'Pricing updated' });
      onRefresh();
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
    }
    setEditing(null);
  };

  const EditableCell = ({ row, field }: { row: PricingRow; field: 'inputCostPer1k' | 'outputCostPer1k' }) => {
    const isEditing = editing?.provider === row.provider && editing.model === row.model && editing.field === field;
    const val = row[field];

    if (isEditing) {
      return (
        <input
          autoFocus
          value={editing.value}
          onChange={(e) => setEditing({ ...editing, value: e.target.value })}
          onBlur={saveEdit}
          onKeyDown={(e) => { if (e.key === 'Enter') saveEdit(); if (e.key === 'Escape') setEditing(null); }}
          className="w-20 bg-surface-1 border border-accent-violet/40 rounded px-2 py-0.5 text-xs text-zinc-200 focus:outline-none font-mono"
        />
      );
    }
    return (
      <button
        onClick={() => setEditing({ provider: row.provider, model: row.model, field, value: String(val) })}
        className="font-mono text-xs text-zinc-300 hover:text-white hover:underline"
      >
        ${val.toFixed(4)}
      </button>
    );
  };

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-white/[0.06]">
            {['Provider', 'Model', 'Role', 'Input /1k', 'Output /1k', 'Active'].map((h) => (
              <th key={h} className="text-left px-4 py-2.5 text-xs font-medium text-zinc-500 uppercase tracking-wider">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.provider}-${row.model}`} className="border-b border-white/[0.04] hover:bg-white/[0.02] transition-colors">
              <td className="px-4 py-2.5">
                <span className={cn('text-xs px-2 py-0.5 rounded-full font-medium capitalize', ROLE_BADGES[row.provider] ?? 'text-zinc-400 bg-zinc-400/10')}>
                  {row.provider}
                </span>
              </td>
              <td className="px-4 py-2.5 font-mono text-xs text-zinc-300">{row.model}</td>
              <td className="px-4 py-2.5">
                <span className="text-xs text-zinc-500">{MODEL_ROLES[row.model] ?? '—'}</span>
              </td>
              <td className="px-4 py-2.5"><EditableCell row={row} field="inputCostPer1k" /></td>
              <td className="px-4 py-2.5"><EditableCell row={row} field="outputCostPer1k" /></td>
              <td className="px-4 py-2.5">
                {row.active ? (
                  <span className="relative flex h-2 w-2">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-emerald opacity-75" />
                    <span className="relative inline-flex rounded-full h-2 w-2 bg-accent-emerald" />
                  </span>
                ) : (
                  <span className="h-2 w-2 rounded-full bg-zinc-700 inline-flex" />
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
