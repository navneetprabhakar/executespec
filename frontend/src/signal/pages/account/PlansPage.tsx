import {useEffect, useMemo, useState} from 'react';
import {TopBar} from '@/signal/components/Chrome';
import {Feed, Card, Button, TabsStrip} from '@/signal/components/Primitives';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

type CycleTab = 'monthly' | 'yearly';

// Feature copy by tier — these are display strings shown alongside the
// pricing rows. The backend's /api/billing/plans is the source of truth for
// price + currency; this map is the source for the bullet list.
const FEATURES: Record<string, string[]> = {
  FREE: [
    '✓ 15 runs / month',
    '✓ 1-credit specs only',
    '✓ $3 hard cost cap',
    '○ Single repo only',
    '○ No team invites',
  ],
  PRO: [
    '✓ Unlimited runs',
    '✓ Multi-repo + BYOK',
    '✓ Project memory & chat',
    '✓ All 4 autofix surfaces',
    '○ HIDDEN — grandfathered',
  ],
  TEAM: [
    '✓ Unlimited runs',
    '✓ 500 credits / seat',
    '✓ 1–20 seats (start solo)',
    '✓ $25 hard cost cap',
    '✓ Multi-repo + BYOK',
    '✓ Project sharing + audit',
    '✓ All 4 autofix surfaces',
  ],
  ENTERPRISE: [
    '✓ Everything in TEAM',
    '✓ Unlimited credits + no cap',
    '✓ Cross-org sharing',
    '✓ Contracts, Standards, ADRs',
    '✓ SSO + SCIM',
    '✓ 99.9% SLA + dedicated CSM',
  ],
};

interface PlanRow {
  tier: string;
  monthly: number | null;
  yearly: number | null;
  currency: string;
  perSeat: boolean;
  recommended?: boolean;
}

function fmtMoney(amount: number | null, currency: string): string {
  if (amount == null) return 'Custom';
  if (amount === 0) return 'Free';
  const symbol = currency === 'USD' ? '$' : currency === 'INR' ? '₹' : `${currency} `;
  return `${symbol}${amount}`;
}

export default function PlansPage() {
  const tier = useAuthStore(selectNormalizedTier);
  const [cycle, setCycle] = useState<CycleTab>('yearly');
  const [pricing, setPricing] = useState<api.PricingRow[] | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    let cancelled = false;
    api
      .getPricing()
      .then((rows) => {
        if (!cancelled) setPricing(rows);
      })
      .catch((e: unknown) => {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load plans.');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Group raw pricing rows by tier so we can render one card per tier.
  const plans: PlanRow[] = useMemo(() => {
    if (!pricing) {
      // Fallback display while loading.
      return [
        {tier: 'FREE', monthly: 0, yearly: 0, currency: 'USD', perSeat: false},
        {tier: 'TEAM', monthly: 39, yearly: 31, currency: 'USD', perSeat: true, recommended: true},
        {tier: 'ENTERPRISE', monthly: null, yearly: null, currency: 'USD', perSeat: false},
      ];
    }
    const byTier = new Map<string, PlanRow>();
    for (const row of pricing) {
      const t =
        (row as unknown as {tier?: string; planTier?: string}).tier ||
        (row as unknown as {planTier?: string}).planTier ||
        (row as unknown as {chainTier?: string}).chainTier ||
        'TEAM';
      const cur = (row as unknown as {currency?: string}).currency || 'USD';
      const monthly =
        (row as unknown as {monthlyPrice?: number; monthly?: number}).monthlyPrice ??
        (row as unknown as {monthly?: number}).monthly ??
        null;
      const yearly =
        (row as unknown as {yearlyPrice?: number; yearly?: number}).yearlyPrice ??
        (row as unknown as {yearly?: number}).yearly ??
        null;
      const perSeat = (row as unknown as {perSeat?: boolean}).perSeat ?? t === 'TEAM';
      if (!byTier.has(t.toUpperCase())) {
        byTier.set(t.toUpperCase(), {
          tier: t.toUpperCase(),
          monthly,
          yearly,
          currency: cur,
          perSeat,
          recommended: t.toUpperCase() === 'TEAM',
        });
      }
    }
    // Ensure FREE + ENTERPRISE always show.
    if (!byTier.has('FREE'))
      byTier.set('FREE', {tier: 'FREE', monthly: 0, yearly: 0, currency: 'USD', perSeat: false});
    if (!byTier.has('TEAM'))
      byTier.set('TEAM', {
        tier: 'TEAM',
        monthly: 39,
        yearly: 31,
        currency: 'USD',
        perSeat: true,
        recommended: true,
      });
    if (!byTier.has('ENTERPRISE'))
      byTier.set('ENTERPRISE', {
        tier: 'ENTERPRISE',
        monthly: null,
        yearly: null,
        currency: 'USD',
        perSeat: false,
      });
    const order = ['FREE', 'TEAM', 'ENTERPRISE'];
    return order.flatMap((k) => {
      const row = byTier.get(k);
      return row ? [row] : [];
    });
  }, [pricing]);

  async function subscribe(t: string) {
    if (t === 'ENTERPRISE') {
      window.location.href = 'mailto:sales@executespec.ai?subject=Enterprise%20plan';
      return;
    }
    if (t === 'FREE') return;
    try {
      const resp = await fetch('/api/billing/subscribe', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`,
        },
        body: JSON.stringify({tier: t, cycle: cycle.toUpperCase()}),
      });
      if (!resp.ok) throw new Error(`Subscribe failed (${resp.status}).`);
      const data = (await resp.json()) as {redirectUrl?: string; checkoutUrl?: string};
      const url = data.redirectUrl || data.checkoutUrl;
      if (url) window.location.href = url;
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Subscribe failed.');
    }
  }

  return (
    <>
      <TopBar title='Plans' sub={`Currently on ${tier}`} back backTo='/billing' />
      <Feed>
        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}

        {tier === 'PRO' && (
          <Card
            style={{
              background: 'var(--signal-lavender-soft)',
              borderColor: 'transparent',
              marginBottom: 12,
            }}
          >
            <div
              style={{
                fontSize: 11,
                fontWeight: 700,
                color: '#5443a8',
                letterSpacing: '0.14em',
                textTransform: 'uppercase',
                marginBottom: 6,
              }}
            >
              You're on PRO (grandfathered)
            </div>
            <div style={{fontSize: 13, color: '#5443a8', lineHeight: 1.5}}>
              The PRO plan is no longer offered to new customers — you keep all of its perks at your
              current rate. Switch to TEAM for project sharing, audit log, and external invites.
            </div>
          </Card>
        )}

        <TabsStrip
          items={[
            {label: 'Monthly', active: cycle === 'monthly', onClick: () => setCycle('monthly')},
            {
              label: 'Yearly · save 20%',
              active: cycle === 'yearly',
              onClick: () => setCycle('yearly'),
            },
          ]}
        />

        {/* On desktop, lay the three tier cards side-by-side so the comparison
            fits above the fold instead of forcing a scroll past FREE to see
            TEAM and ENTERPRISE. Stacks on mobile / tablet. */}
        <div className='signal-plans-grid'>
          {plans.map((p) => {
            const price = cycle === 'monthly' ? p.monthly : p.yearly;
            const features = FEATURES[p.tier] || ['—'];
            const isCurrent = p.tier === tier;
            return (
              <Card
                key={p.tier}
                style={
                  p.recommended && !isCurrent
                    ? {borderColor: 'var(--signal-ink)', position: 'relative'}
                    : isCurrent
                      ? {borderColor: 'var(--signal-mint)', position: 'relative'}
                      : {position: 'relative'}
                }
              >
                {p.recommended && !isCurrent && (
                  <div
                    style={{
                      position: 'absolute',
                      top: -10,
                      right: 16,
                      background: 'var(--signal-y)',
                      color: 'var(--signal-y-text)',
                      fontSize: 10,
                      fontWeight: 700,
                      padding: '3px 9px',
                      borderRadius: 999,
                      letterSpacing: '0.06em',
                    }}
                  >
                    RECOMMENDED
                  </div>
                )}
                {isCurrent && (
                  <div
                    style={{
                      position: 'absolute',
                      top: -10,
                      right: 16,
                      background: 'var(--signal-mint)',
                      color: 'var(--signal-mint-text)',
                      fontSize: 10,
                      fontWeight: 700,
                      padding: '3px 9px',
                      borderRadius: 999,
                      letterSpacing: '0.06em',
                    }}
                  >
                    CURRENT
                  </div>
                )}
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'baseline',
                    marginBottom: 6,
                  }}
                >
                  <div style={{fontSize: 16, fontWeight: 700}}>{p.tier}</div>
                  <div>
                    <span style={{fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em'}}>
                      {fmtMoney(price, p.currency)}
                    </span>
                    {price != null && price > 0 && p.perSeat && (
                      <span
                        style={{fontSize: 13, color: 'var(--signal-text-dim)', fontWeight: 400}}
                      >
                        /seat/mo
                      </span>
                    )}
                  </div>
                </div>
                <div style={{fontSize: 12, color: 'var(--signal-text-dim)', marginBottom: 14}}>
                  {p.tier === 'FREE'
                    ? 'Try ExecuteSpec without a card'
                    : p.tier === 'TEAM'
                      ? 'Start with one seat — add up to 20 as you grow. Annual billing.'
                      : p.tier === 'ENTERPRISE'
                        ? 'SSO, SLA, dedicated support'
                        : 'Hidden plan — grandfathered users only'}
                </div>
                <ul
                  style={{
                    listStyle: 'none',
                    padding: 0,
                    margin: 0,
                    display: 'grid',
                    gap: 8,
                    fontSize: 13.5,
                  }}
                >
                  {features.map((f) => (
                    <li
                      key={f}
                      style={f.startsWith('○') ? {color: 'var(--signal-text-mute)'} : undefined}
                    >
                      {f}
                    </li>
                  ))}
                </ul>
                <Button
                  kind={isCurrent ? 'secondary' : p.recommended ? 'primary' : 'secondary'}
                  block
                  style={{marginTop: 14}}
                  onClick={() => subscribe(p.tier)}
                  disabled={isCurrent || p.tier === 'PRO'}
                >
                  {isCurrent
                    ? 'Current plan'
                    : p.tier === 'ENTERPRISE'
                      ? 'Talk to sales'
                      : p.tier === 'PRO'
                        ? 'Hidden plan'
                        : p.tier === 'FREE'
                          ? 'Start free'
                          : 'Upgrade'}
                </Button>
              </Card>
            );
          })}
        </div>
      </Feed>
    </>
  );
}
