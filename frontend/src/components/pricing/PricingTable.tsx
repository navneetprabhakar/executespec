import {useState} from 'react';
import {updatePricing} from '@/lib/api';
import {useUiStore} from '@/stores/useUiStore';
import {useIsMobile} from '@/hooks/useIsMobile';
import type {PricingRow} from '@/lib/api';
import {cn, errMsg} from '@/lib/utils';

const ROLE_BADGES: Record<string, string> = {
  anthropic: 'text-accent-blue bg-accent-blue/10',
  openai: 'text-secondary bg-[var(--gray-500)]/10',
  google: 'text-accent-green bg-accent-green/10',
};

/**
 * 2026-05-06 — derive role label from PricingRow.chainTier (YAML model-matrix is the only
 * source). The prior hardcoded MODEL_ROLES map drifted on every YAML update + missed preview
 * variants. Returns "—" for legacy DB rows whose YAML entry has been pruned.
 */
function roleLabel(chainTier: string | null | undefined): string {
  switch (chainTier) {
    case 'ARCHITECT':
      return 'Architect';
    case 'CAPABLE':
      return 'Workhorse';
    case 'UTILITY':
      return 'Utility';
    default:
      return '—';
  }
}

interface EditCell {
  provider: string;
  model: string;
  field: 'inputCostPer1m' | 'outputCostPer1m';
  value: string;
}

interface PricingTableProps {
  rows: PricingRow[];
  onRefresh: () => void;
}

export default function PricingTable({rows, onRefresh}: PricingTableProps) {
  const [editing, setEditing] = useState<EditCell | null>(null);
  const {addToast} = useUiStore();
  const isMobile = useIsMobile();

  const saveEdit = async () => {
    if (!editing) return;
    try {
      await updatePricing(editing.provider, editing.model, {
        [editing.field]: parseFloat(editing.value),
      });
      addToast({type: 'success', message: 'Pricing updated'});
      onRefresh();
    } catch (e) {
      addToast({type: 'error', message: errMsg(e)});
    }
    setEditing(null);
  };

  const EditableCell = ({
    row,
    field,
  }: {
    row: PricingRow;
    field: 'inputCostPer1m' | 'outputCostPer1m';
  }) => {
    const isEditing =
      editing?.provider === row.provider && editing.model === row.model && editing.field === field;
    const val = row[field] ?? 0;

    if (isEditing) {
      return (
        <input
          autoFocus
          value={editing.value}
          onChange={(e) => setEditing({...editing, value: e.target.value})}
          onBlur={saveEdit}
          onKeyDown={(e) => {
            if (e.key === 'Enter') saveEdit();
            if (e.key === 'Escape') setEditing(null);
          }}
          className='w-20 bg-[var(--gray-100)] border border-accent-blue/40 rounded px-2 py-0.5 text-xs text-primary focus:outline-none font-mono'
        />
      );
    }
    return (
      <button
        onClick={() =>
          setEditing({provider: row.provider, model: row.model, field, value: String(val)})
        }
        className='font-mono text-xs text-secondary hover:text-primary hover:underline'
      >
        ${val.toFixed(4)}
      </button>
    );
  };

  if (isMobile) {
    return (
      <div className='space-y-3 px-1'>
        {rows.map((row) => (
          <div
            key={`${row.provider}-${row.model}`}
            className='p-3 rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] space-y-2'
          >
            <div className='flex items-center justify-between'>
              <span
                className={cn(
                  'text-xs px-2 py-0.5 rounded-sm font-medium capitalize',
                  ROLE_BADGES[row.provider] ?? 'text-secondary bg-[var(--gray-200)]',
                )}
              >
                {row.provider}
              </span>
              <span className='text-xs text-muted'>{roleLabel(row.chainTier)}</span>
            </div>
            <p className='font-mono text-xs text-secondary truncate'>{row.model}</p>
            <div className='flex gap-4 text-xs'>
              <div className='flex items-center gap-1'>
                <span className='text-muted'>In /1M:</span>{' '}
                <EditableCell row={row} field='inputCostPer1m' />
              </div>
              <div className='flex items-center gap-1'>
                <span className='text-muted'>Out /1M:</span>{' '}
                <EditableCell row={row} field='outputCostPer1m' />
              </div>
            </div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className='overflow-x-auto'>
      <table className='w-full text-sm'>
        <thead>
          <tr className='border-b border-[var(--border-default)]'>
            {['Provider', 'Model', 'Role', 'Input /1M', 'Output /1M'].map((h) => (
              <th
                key={h}
                className='text-left px-4 py-2.5 text-xs font-medium text-muted uppercase tracking-wider'
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={`${row.provider}-${row.model}`}
              className='border-b border-[var(--border-default)] hover:bg-[var(--gray-100)] transition-colors'
            >
              <td className='px-4 py-2.5'>
                <span
                  className={cn(
                    'text-xs px-2 py-0.5 rounded-sm font-medium capitalize',
                    ROLE_BADGES[row.provider] ?? 'text-secondary bg-[var(--gray-200)]',
                  )}
                >
                  {row.provider}
                </span>
              </td>
              <td className='px-4 py-2.5 font-mono text-xs text-secondary'>{row.model}</td>
              <td className='px-4 py-2.5'>
                <span className='text-xs text-muted'>{roleLabel(row.chainTier)}</span>
              </td>
              <td className='px-4 py-2.5'>
                <EditableCell row={row} field='inputCostPer1m' />
              </td>
              <td className='px-4 py-2.5'>
                <EditableCell row={row} field='outputCostPer1m' />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
