import { useEffect, useState } from 'react';
import GlassPanel from '@/components/shared/GlassPanel';
import PricingTable from '@/components/pricing/PricingTable';
import { getPricing } from '@/lib/api';
import type { PricingRow } from '@/lib/api';
import { Skeleton } from '@/components/shared/SkeletonLoader';

export default function PricingPage() {
  const [rows, setRows] = useState<PricingRow[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try { setRows(await getPricing()); } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      <GlassPanel className="overflow-hidden">
        <div className="px-4 py-3 border-b border-[var(--border-default)]">
          <h2 className="text-base font-semibold text-primary">Model Pricing</h2>
          <p className="text-xs text-muted mt-0.5">Click a cost value to edit inline</p>
        </div>
        {loading
          ? <div className="p-4 space-y-3">{Array.from({ length: 7 }).map((_, i) => <Skeleton key={i} className="h-8" />)}</div>
          : <PricingTable rows={rows} onRefresh={load} />
        }
      </GlassPanel>
    </div>
  );
}
