import {useNavigate} from 'react-router-dom';
import {Modal} from '@/signal/components/Modal';
import {Button} from '@/signal/components/Primitives';

/**
 * Reusable upgrade prompt. Opened as the at-cap action on ComposePage (when the
 * user has spent their monthly quota) and as the upgrade CTA on PlansPage.
 *
 * Targets a BUYABLE tier only — PRO is HIDDEN (grandfathered, new buyers can't
 * pick it), so the default upgrade path off FREE is TEAM (single seat allowed
 * now — TEAM no longer has a 2-seat minimum). ENTERPRISE routes to a sales
 * conversation rather than self-serve checkout.
 *
 * Routing: TEAM/PRO → the destination route (default `/plans`, the self-serve
 * picker); ENTERPRISE → mailto sales.
 */
export function UpgradeModal({
  open,
  onClose,
  reason,
  targetTier = 'TEAM',
  to = '/plans',
}: {
  open: boolean;
  onClose: () => void;
  /** Plain-English why the user is seeing this (e.g. "You've used all 15 runs this month."). */
  reason: string;
  /** The tier we're nudging toward. PRO is hidden; default is TEAM. */
  targetTier?: 'TEAM' | 'PRO' | 'ENTERPRISE';
  /** Destination for the primary CTA. `/plans` (picker) or `/billing`. */
  to?: string;
}) {
  const navigate = useNavigate();

  const isEnterprise = targetTier === 'ENTERPRISE';
  const headline = isEnterprise ? 'Talk to our team' : `Upgrade to ${targetTier}`;

  // What the target tier unlocks — short, benefit-led, no pricing (the picker
  // owns the numbers). Keep it to the conversion-critical wins.
  const perks: Record<string, string[]> = {
    TEAM: [
      'Unlimited runs — no monthly cap',
      '500 credits per seat each month',
      'From 1 seat (no 2-seat minimum)',
      'Multi-repo, BYOK, project sharing',
    ],
    PRO: ['Unlimited runs', 'Multi-repo + BYOK', 'Project memory & chat'],
    ENTERPRISE: [
      'Unlimited credits + no cost cap',
      'Cross-org sharing, SSO + SCIM',
      'Standards, Contracts, ADRs',
      '99.9% SLA + dedicated CSM',
    ],
  };

  function goUpgrade() {
    if (isEnterprise) {
      window.location.href = 'mailto:sales@executespec.ai?subject=Enterprise%20plan';
      return;
    }
    onClose();
    navigate(to);
  }

  return (
    <Modal open={open} title={headline} onClose={onClose} size='sm'>
      <div
        style={{fontSize: 14, color: 'var(--signal-text-dim)', lineHeight: 1.55, marginBottom: 14}}
      >
        {reason}
      </div>
      <ul
        style={{
          listStyle: 'none',
          padding: 0,
          margin: '0 0 18px',
          display: 'grid',
          gap: 8,
          fontSize: 13.5,
        }}
      >
        {(perks[targetTier] || perks.TEAM).map((f) => (
          <li key={f} style={{color: 'var(--signal-ink)'}}>
            <span style={{color: 'var(--signal-mint-text)', marginRight: 8}}>✓</span>
            {f}
          </li>
        ))}
      </ul>
      <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end'}}>
        <Button kind='ghost' sm onClick={onClose}>
          Not now
        </Button>
        <Button kind='primary' sm onClick={goUpgrade}>
          {isEnterprise ? 'Talk to sales' : `Upgrade to ${targetTier}`}
        </Button>
      </div>
    </Modal>
  );
}
