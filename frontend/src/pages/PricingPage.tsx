import {useEffect, useState} from 'react';
import PricingTable from '@/components/pricing/PricingTable';
import {getPricing} from '@/lib/api';
import type {PricingRow} from '@/lib/api';
import {Skeleton} from '@/components/shared/SkeletonLoader';
import PageShell, {Section} from '@/components/shared/PageShell';

export default function PricingPage() {
  const [rows, setRows] = useState<PricingRow[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      setRows(await getPricing());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  return (
    <PageShell
      eyebrow='Model pricing'
      title='Transparent, per-1M-token rates.'
      accent='Transparent,'
      description='Live pricing for every model. Click any cost to edit inline — changes apply immediately to new runs.'
      hero
    >
      <Section eyebrow='Cost matrix' title='Per-model input + output rates' card={false}>
        {loading ? (
          <div className='rounded-lg border border-[var(--border-default)] bg-[var(--gray-100)] p-4 space-y-3'>
            {Array.from({length: 7}).map((_, i) => (
              <Skeleton key={i} className='h-8' />
            ))}
          </div>
        ) : (
          <PricingTable rows={rows} onRefresh={load} />
        )}
      </Section>
    </PageShell>
  );
}
