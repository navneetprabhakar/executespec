import { useMetricsStore } from '@/stores/useMetricsStore';
import { cn } from '@/lib/utils';

const PRESETS = [
  { label: '7d',       value: 7 },
  { label: '30d',      value: 30 },
  { label: '90d',      value: 90 },
  { label: 'All Time', value: 0 },
];

export default function DateRangeFilter() {
  const { days, setDays } = useMetricsStore();

  return (
    <div className="flex items-center gap-1.5">
      {PRESETS.map((p) => (
        <button
          key={p.value}
          onClick={() => setDays(p.value)}
          className={cn(
            'px-3 py-1 text-xs font-medium rounded-sm transition-colors',
            days === p.value
              ? 'bg-white/10 text-white border border-white/20'
              : 'text-zinc-500 hover:text-zinc-300 hover:bg-white/[0.04] border border-transparent'
          )}
        >
          {p.label}
        </button>
      ))}
    </div>
  );
}
