import { useState } from 'react';
import { X, Gift } from 'lucide-react';
import { cn } from '@/lib/utils';
import * as api from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';

interface Props {
  targetType: 'user' | 'org';
  targetId: string;
  targetName: string;
  onClose: () => void;
  onSuccess: () => void;
}

export default function GrantRunsModal({ targetType, targetId, targetName, onClose, onSuccess }: Props) {
  const { addToast } = useUiStore();

  const [quantity, setQuantity] = useState(10);
  const [expiryDays, setExpiryDays] = useState<number | ''>(90);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (quantity <= 0) {
      addToast({ type: 'error', message: 'Quantity must be positive' });
      return;
    }
    setSubmitting(true);
    try {
      const expiry = expiryDays === '' ? null : expiryDays;
      if (targetType === 'user') {
        await api.adminGrantUserRuns(targetId, quantity, expiry, reason || undefined);
      } else {
        await api.adminGrantOrgRuns(targetId, quantity, expiry, reason || undefined);
      }
      addToast({ type: 'success', message: `Granted ${quantity} bonus runs to ${targetName}` });
      onSuccess();
      onClose();
    } catch (err: any) {
      addToast({ type: 'error', message: err.message });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-2 md:p-4"
      style={{ backdropFilter: 'blur(4px)', background: 'rgba(0,0,0,0.65)' }}
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-2xl bg-surface-2 border border-white/[0.08] shadow-2xl p-6 space-y-5 overflow-y-auto"
        style={{ maxHeight: '95dvh' }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-white flex items-center gap-2">
            <Gift className="h-4 w-4 text-accent-emerald" />
            Grant Bonus Runs
          </h2>
          <button onClick={onClose} className="text-zinc-500 hover:text-zinc-300 p-2 -m-1">
            <X className="h-4 w-4" />
          </button>
        </div>

        <p className="text-sm text-zinc-400">
          Granting to <span className="text-zinc-200 font-medium">{targetName}</span>
          <span className="text-zinc-500 ml-1">({targetType})</span>
        </p>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Quantity */}
          <div>
            <label className="block text-xs text-zinc-400 mb-1">
              Number of runs <span className="text-red-400 ml-0.5">*</span>
            </label>
            <input
              type="number"
              min={1}
              max={10000}
              value={quantity}
              onChange={(e) => setQuantity(parseInt(e.target.value) || 0)}
              className="w-full bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-zinc-200 focus:outline-none focus:border-accent-violet/40"
              required
            />
          </div>

          {/* Expiry Days */}
          <div>
            <label className="block text-xs text-zinc-400 mb-1">Expires in (days)</label>
            <input
              type="number"
              min={1}
              max={365}
              value={expiryDays}
              onChange={(e) => setExpiryDays(e.target.value === '' ? '' : parseInt(e.target.value) || 0)}
              placeholder="90"
              className="w-full bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-zinc-200 placeholder:text-zinc-600 focus:outline-none focus:border-accent-violet/40"
            />
            <p className="text-[10px] text-zinc-500 mt-1">Default: 90 days. Admin grants always expire.</p>
          </div>

          {/* Reason */}
          <div>
            <label className="block text-xs text-zinc-400 mb-1">Reason</label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g., Promotional credit, customer support"
              rows={2}
              className="w-full bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-zinc-200 placeholder:text-zinc-600 focus:outline-none focus:border-accent-violet/40 resize-none"
            />
          </div>

          <button
            type="submit"
            disabled={submitting || quantity <= 0}
            className={cn(
              'w-full py-2.5 rounded-lg font-medium text-sm text-white transition-all',
              'bg-accent-emerald hover:bg-accent-emerald/80 disabled:opacity-40 disabled:cursor-not-allowed',
              'flex items-center justify-center gap-2',
            )}
          >
            {submitting
              ? <span className="h-4 w-4 rounded-full border-2 border-white/30 border-t-white animate-spin" />
              : <Gift className="h-4 w-4" />
            }
            {submitting ? 'Granting…' : `Grant ${quantity} Runs`}
          </button>
        </form>
      </div>
    </div>
  );
}
