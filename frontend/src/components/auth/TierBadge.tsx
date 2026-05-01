interface Props {
  tier: 'FREE' | 'PRO' | 'TEAM' | 'ENTERPRISE' | string;
  small?: boolean;
}

const TIER_STYLES: Record<string, string> = {
  FREE: 'bg-[var(--gray-400)] text-secondary',
  PRO: 'bg-purple-900/60 text-accent-blue border border-purple-700',
  TEAM: 'bg-status-success/60 text-status-success border border-status-success',
  ENTERPRISE: 'bg-status-waiting/60 text-status-waiting border border-status-waiting',
};

export default function TierBadge({tier, small}: Props) {
  const cls = TIER_STYLES[tier] ?? TIER_STYLES.FREE;
  return (
    <span
      className={`rounded-sm font-semibold uppercase tracking-wide ${small ? 'px-2 py-0.5 text-[10px]' : 'px-3 py-1 text-xs'} ${cls}`}
    >
      {tier}
    </span>
  );
}
