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
        className="w-full max-w-md rounded-sm bg-[var(--gray-200)] border border-[var(--border-default)]  p-6 space-y-5 overflow-y-auto"
        style={{ maxHeight: '95dvh' }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-primary flex items-center gap-2">
            <Gift className="h-4 w-4 text-accent-green" />
            Grant Bonus Runs
          </h2>
          <button onClick={onClose} className="text-muted hover:text-secondary p-2 -m-1">
            <X className="h-4 w-4" />
          </button>
        </div>

        <p className="text-sm text-secondary">
          Granting to <span className="text-primary font-medium">{targetName}</span>
          <span className="text-muted ml-1">({targetType})</span>
        </p>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Quantity */}
          <div>
            <label className="block text-xs text-secondary mb-1">
              Number of runs <span className="text-accent-red ml-0.5">*</span>
            </label>
            <input
              type="number"
              min={1}
              max={10000}
              value={quantity}
              onChange={(e) => setQuantity(parseInt(e.target.value) || 0)}
              className="w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-primary focus:outline-none focus:border-accent-blue/40"
              required
            />
          </div>

          {/* Expiry Days */}
          <div>
            <label className="block text-xs text-secondary mb-1">Expires in (days)</label>
            <input
              type="number"
              min={1}
              max={365}
              value={expiryDays}
              onChange={(e) => setExpiryDays(e.target.value === '' ? '' : parseInt(e.target.value) || 0)}
              placeholder="90"
              className="w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue/40"
            />
            <p className="text-[10px] text-muted mt-1">Default: 90 days. Admin grants always expire.</p>
          </div>

          {/* Reason */}
          <div>
            <label className="block text-xs text-secondary mb-1">Reason</label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g., Promotional credit, customer support"
              rows={2}
              className="w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue/40 resize-none"
            />
          </div>

          <button
            type="submit"
            disabled={submitting || quantity <= 0}
            className={cn(
              'w-full py-2.5 rounded-sm font-medium text-sm text-primary transition-all',
              'bg-accent-green hover:bg-accent-green/80 disabled:opacity-40 disabled:cursor-not-allowed',
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
