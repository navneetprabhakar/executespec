interface Props {
  tier: 'FREE' | 'PRO' | 'ENTERPRISE' | string;
  small?: boolean;
}

const TIER_STYLES: Record<string, string> = {
  FREE: 'bg-slate-700 text-slate-300',
  PRO: 'bg-purple-900/60 text-purple-300 border border-purple-700',
  ENTERPRISE: 'bg-amber-900/60 text-amber-300 border border-amber-700',
};

export default function TierBadge({ tier, small }: Props) {
  const cls = TIER_STYLES[tier] ?? TIER_STYLES.FREE;
  return (
    <span className={`rounded-sm font-semibold uppercase tracking-wide ${small ? 'px-2 py-0.5 text-[10px]' : 'px-3 py-1 text-xs'} ${cls}`}>
      {tier}
    </span>
  );
}
