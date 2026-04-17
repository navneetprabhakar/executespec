import { useState } from 'react';
import { Gift } from 'lucide-react';
import * as api from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';
import ResponsiveModal from '@/components/shared/ResponsiveModal';
import Button from '@/components/shared/Button';

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
    <ResponsiveModal open={true} onClose={onClose} title="Grant Bonus Runs" maxWidth="max-w-md">
      <p className="text-sm text-secondary">
        Granting to <span className="text-primary font-medium">{targetName}</span>
        <span className="text-muted ml-1">({targetType})</span>
      </p>

      <form onSubmit={handleSubmit} className="space-y-4 mt-4">
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

        <Button
          type="submit"
          variant="success"
          size="lg"
          fullWidth
          loading={submitting}
          disabled={submitting || quantity <= 0}
        >
          <Gift className="h-4 w-4" />
          {submitting ? 'Granting…' : `Grant ${quantity} Runs`}
        </Button>
      </form>
    </ResponsiveModal>
  );
}
