/* IDE-native port of the Signal BILLING cluster:
 *   - IdeBilling ← signal/pages/account/BillingPage.tsx (/billing)
 *   - IdePlans   ← signal/pages/account/PlansPage.tsx   (/plans)
 *
 * Data/API/actions mirror the Signal originals EXACTLY — same endpoints
 * (getAnalyticsBilling / getAnalyticsUsage / raw payment-history fetch /
 * getPricing / raw billing/subscribe fetch), same plan-grouping + fallbacks,
 * same subscribe → gateway-redirect flow, same FEATURES + pricing copy. Only
 * the presentation is re-rendered in the IDE visual language. Pricing numbers
 * ($3 cap / $39 TEAM / etc.) are intentionally left verbatim — they're a
 * product/pricing decision owned by the founder, not a UI-port concern.
 */

import {useEffect, useMemo, useState} from 'react';
import {Link} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';

function SectionTitle({children}: {children: React.ReactNode}) {
  return <div className='ide-h2'>{children}</div>;
}

// Soft blue informational callout (mirrors IdeOrg's InfoCard).
function InfoCard({children}: {children: React.ReactNode}) {
  return (
    <div
      className='ide-card'
      style={{
        marginBottom: 12,
        fontSize: 12.5,
        lineHeight: 1.55,
        color: 'var(--ide-fg)',
        background: 'color-mix(in srgb, var(--ide-blue) 12%, transparent)',
        borderColor: 'color-mix(in srgb, var(--ide-blue) 35%, transparent)',
      }}
    >
      {children}
    </div>
  );
}

function Stat({label, value}: {label: string; value: React.ReactNode}) {
  return (
    <div>
      <div style={{color: 'var(--ide-fg-muted)', fontSize: 11, marginBottom: 2}}>{label}</div>
      <div style={{fontWeight: 700, fontSize: 16, color: 'var(--ide-fg-bright)'}}>{value}</div>
    </div>
  );
}

// Billing dashboard — /billing

interface PaymentRowLite {
  id?: string | number;
  invoiceNumber?: string;
  number?: string;
  amount?: number;
  currency?: string;
  status?: string;
  gateway?: string;
  createdAt?: string;
  paidAt?: string;
}

export function IdeBilling() {
  const user = useAuthStore((s) => s.user);
  const tier = useAuthStore(selectNormalizedTier);
  const permissions = useAuthStore((s) => s.permissions);
  const [billing, setBilling] = useState<api.BillingOverviewData | null>(null);
  const [usage, setUsage] = useState<api.UsageMetrics | null>(null);
  const [payments, setPayments] = useState<PaymentRowLite[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (!user?.orgId) return;
    let cancelled = false;
    Promise.allSettled([
      api.getAnalyticsBilling('org', user.orgId).catch(() => null),
      api.getAnalyticsUsage('org', user.orgId, 30).catch(() => null),
      // Best-effort: paymentHistory may not return a typed shape; fail soft —
      // identical to the Signal BillingPage fetch.
      (async () => {
        try {
          const raw = await fetch(
            `/api/billing/payment-history?orgId=${encodeURIComponent(user.orgId ?? '')}`,
            {headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`}},
          );
          if (!raw.ok) return [];
          const data = (await raw.json()) as unknown;
          if (Array.isArray(data)) return data as PaymentRowLite[];
          if (
            data &&
            typeof data === 'object' &&
            Array.isArray((data as {content?: PaymentRowLite[]}).content)
          ) {
            return (data as {content: PaymentRowLite[]}).content;
          }
          return [];
        } catch {
          return [];
        }
      })(),
    ]).then(([b, u, p]) => {
      if (cancelled) return;
      if (b.status === 'fulfilled' && b.value) setBilling(b.value);
      if (u.status === 'fulfilled' && u.value) setUsage(u.value);
      if (p.status === 'fulfilled' && Array.isArray(p.value)) setPayments(p.value);
      setLoaded(true);
    });
    return () => {
      cancelled = true;
    };
  }, [user?.orgId]);

  // Run usage + cap come from the auth tier + permissions (authoritative,
  // tier-derived) — exactly as the Signal BillingPage did. The analytics/billing
  // endpoint reports a stale per-org plan/limit (e.g. it returns FREE/5 even for
  // an ENTERPRISE org), so we use it ONLY for gateway + renewal date + invoices,
  // never for the run cap or plan status.
  const periodRuns = usage?.totalRuns ?? permissions?.currentRunCount ?? 0;
  const rawCap = permissions?.monthlyRunLimit ?? -1;
  const runCap: number | '∞' = rawCap && rawCap !== -1 ? rawCap : '∞';
  const totalSpend = usage?.totalCost ?? 0;
  const next = billing?.nextBillingDate || '';
  const planStatus = tier === 'FREE' ? 'No subscription' : 'Subscription active';

  if (!user?.orgId) {
    return (
      <div className='ide-pane' style={{maxWidth: 760}}>
        <div className='ide-h1'>Billing</div>
        <div className='ide-empty'>No organization on this account.</div>
      </div>
    );
  }

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <div className='ide-h1'>Billing</div>
      <div className='ide-sub'>
        {tier}
        {billing?.gateway ? ` · billed via ${billing.gateway}` : ''}
      </div>

      {!loaded ? (
        <IdeLoader label='Loading billing…' />
      ) : (
        <>
          {/* Subscription summary */}
          <div className='ide-card'>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
                gap: 12,
                marginBottom: 14,
              }}
            >
              <div>
                <div
                  style={{
                    fontSize: 10,
                    fontWeight: 700,
                    color: 'var(--ide-fg-muted)',
                    letterSpacing: '0.12em',
                    textTransform: 'uppercase',
                    marginBottom: 4,
                  }}
                >
                  {tier} tier
                </div>
                <div
                  style={{
                    fontSize: 20,
                    fontWeight: 700,
                    letterSpacing: '-0.02em',
                    color: 'var(--ide-fg-bright)',
                  }}
                >
                  {planStatus}
                </div>
                <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 2}}>
                  {billing?.gateway
                    ? `billed via ${billing.gateway}${
                        next ? ` · renews ${new Date(next).toLocaleDateString()}` : ''
                      }`
                    : tier === 'FREE'
                      ? 'Upgrade to start a subscription'
                      : 'Managed plan'}
                </div>
              </div>
              <Link className='ide-btn' to='/plans' style={{flexShrink: 0, textDecoration: 'none'}}>
                Change plan
              </Link>
            </div>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(3, 1fr)',
                gap: 12,
                paddingTop: 12,
                borderTop: '1px solid var(--ide-border)',
              }}
            >
              <Stat
                label='Runs'
                value={
                  <>
                    {periodRuns} / {runCap}
                  </>
                }
              />
              <Stat label='Spent' value={`$${totalSpend.toFixed(2)}`} />
              <Stat label='Next invoice' value={next ? new Date(next).toLocaleDateString() : '—'} />
            </div>
          </div>

          {/* FREE → BYOK upsell (copy verbatim from Signal) */}
          {tier === 'FREE' && (
            <div className='ide-card' style={{marginTop: 12}}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  flexWrap: 'wrap',
                  gap: 10,
                  fontSize: 13,
                  lineHeight: 1.45,
                }}
              >
                <div>
                  <strong style={{color: 'var(--ide-fg-bright)'}}>
                    Bring your own keys, bypass the $3 cap.
                  </strong>{' '}
                  <span style={{color: 'var(--ide-fg-muted)'}}>
                    PRO uses your Anthropic / OpenAI / Gemini keys directly. You pay the LLM bill,
                    not us — no platform cost cap, no surprise overage.
                  </span>
                </div>
                <Link
                  className='ide-btn ghost'
                  to='/plans'
                  style={{flexShrink: 0, textDecoration: 'none'}}
                >
                  See PRO
                </Link>
              </div>
            </div>
          )}

          {/* This cycle */}
          <SectionTitle>This cycle</SectionTitle>
          <div className='ide-card'>
            <div
              style={{
                fontSize: 11,
                fontWeight: 700,
                color: 'var(--ide-fg-muted)',
                letterSpacing: '0.12em',
                textTransform: 'uppercase',
                marginBottom: 8,
              }}
            >
              Runs used
            </div>
            <div
              style={{
                fontSize: 36,
                fontWeight: 600,
                letterSpacing: '-0.02em',
                lineHeight: 1,
                marginBottom: 8,
                color: 'var(--ide-fg-bright)',
              }}
            >
              {periodRuns}
              <span style={{fontSize: 18, color: 'var(--ide-fg-muted)', fontWeight: 400}}>
                {' '}
                / {runCap}
              </span>
            </div>
            {typeof runCap === 'number' && runCap > 0 && (
              <div
                style={{
                  height: 6,
                  background: 'var(--ide-bg-tab-inactive)',
                  borderRadius: 3,
                  overflow: 'hidden',
                  marginBottom: 14,
                }}
              >
                <div
                  style={{
                    height: '100%',
                    width: `${Math.min(100, (periodRuns / runCap) * 100)}%`,
                    background: 'var(--ide-accent)',
                    borderRadius: 3,
                  }}
                />
              </div>
            )}
            <div className='ide-kv'>
              <span>Tier</span>
              <span>{tier}</span>
            </div>
            <div className='ide-kv'>
              <span>Spend (cycle)</span>
              <span>${totalSpend.toFixed(2)}</span>
            </div>
            <div className='ide-kv' style={{borderBottom: 'none'}}>
              <span>Successful runs (30d)</span>
              <span>{usage?.successfulRuns ?? '—'}</span>
            </div>
          </div>

          {/* Invoices */}
          <SectionTitle>Invoices</SectionTitle>
          {payments.length === 0 ? (
            <div className='ide-card' style={{fontSize: 13, color: 'var(--ide-fg-muted)'}}>
              No invoices yet. Your first invoice will appear here after billing closes.
            </div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {payments.slice(0, 6).map((p, i) => {
                const num = p.invoiceNumber || p.number || (p.id != null ? `INV-${p.id}` : 'INV-—');
                const amount =
                  p.amount != null ? `${p.currency || 'USD'} ${p.amount.toFixed(2)}` : '—';
                const paid = (p.status || '').toLowerCase() === 'paid' || !!p.paidAt;
                return (
                  <div
                    key={String(p.id || num)}
                    style={{
                      padding: '11px 16px',
                      borderBottom:
                        i < Math.min(payments.length, 6) - 1
                          ? '1px solid var(--ide-border)'
                          : 'none',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 10,
                    }}
                  >
                    <div style={{flex: 1, minWidth: 0}}>
                      <div style={{fontSize: 13, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
                        {num} — {amount}
                      </div>
                      <div
                        style={{
                          fontSize: 11.5,
                          color: 'var(--ide-fg-muted)',
                          display: 'flex',
                          gap: 6,
                          flexWrap: 'wrap',
                        }}
                      >
                        <span className='ide-mono'>{p.gateway || 'card'}</span>
                        <span>·</span>
                        <span>{paid ? 'Paid' : p.status || 'pending'}</span>
                        {p.createdAt && (
                          <>
                            <span>·</span>
                            <span>{new Date(p.createdAt).toLocaleDateString()}</span>
                          </>
                        )}
                      </div>
                    </div>
                    <IdeBadge kind={paid ? 'ok' : 'warn'}>{paid ? 'Paid' : 'Pending'}</IdeBadge>
                  </div>
                );
              })}
            </div>
          )}

          {/* Payment method (display-only — managed by the gateway, like Signal) */}
          <SectionTitle>Payment method</SectionTitle>
          <InfoCard>
            Cards are managed securely by your payment gateway
            {billing?.gateway ? ` (${billing.gateway})` : ''}. Use “Change plan” to start or update
            a subscription — checkout opens on the gateway’s hosted page.
          </InfoCard>
        </>
      )}
    </div>
  );
}

// Plans — /plans

type CycleTab = 'monthly' | 'yearly';

// Feature copy by tier — display strings shown alongside pricing. The backend's
// pricing endpoint is the source of truth for price + currency; this map is the
// source for the bullet list. Verbatim from the Signal PlansPage.
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

export function IdePlans() {
  const tier = useAuthStore(selectNormalizedTier);
  const [cycle, setCycle] = useState<CycleTab>('yearly');
  const [pricing, setPricing] = useState<api.PricingRow[] | null>(null);
  const [err, setErr] = useState('');
  const [busyTier, setBusyTier] = useState('');

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

  // Group raw pricing rows by tier so we render one card per tier — identical
  // logic + fallbacks to the Signal PlansPage.
  const plans: PlanRow[] = useMemo(() => {
    if (!pricing) {
      return [
        {tier: 'FREE', monthly: 0, yearly: 0, currency: 'USD', perSeat: false},
        {tier: 'TEAM', monthly: 39, yearly: 31, currency: 'USD', perSeat: true, recommended: true},
        {tier: 'ENTERPRISE', monthly: null, yearly: null, currency: 'USD', perSeat: false},
      ];
    }
    const byTier = new Map<string, PlanRow>();
    for (const row of pricing) {
      const t =
        (row as unknown as {tier?: string}).tier ||
        (row as unknown as {planTier?: string}).planTier ||
        (row as unknown as {chainTier?: string}).chainTier ||
        'TEAM';
      const cur = (row as unknown as {currency?: string}).currency || 'USD';
      const monthly =
        (row as unknown as {monthlyPrice?: number}).monthlyPrice ??
        (row as unknown as {monthly?: number}).monthly ??
        null;
      const yearly =
        (row as unknown as {yearlyPrice?: number}).yearlyPrice ??
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

  // Subscribe → gateway hosted checkout. Verbatim flow from the Signal page:
  // ENTERPRISE → sales mailto, FREE → no-op, else POST /api/billing/subscribe
  // and redirect to the returned checkout URL.
  async function subscribe(t: string) {
    if (t === 'ENTERPRISE') {
      window.location.href = 'mailto:sales@executespec.ai?subject=Enterprise%20plan';
      return;
    }
    if (t === 'FREE') return;
    setBusyTier(t);
    setErr('');
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
    } finally {
      setBusyTier('');
    }
  }

  return (
    <div className='ide-pane' style={{maxWidth: 920}}>
      <div className='ide-h1'>Plans</div>
      <div className='ide-sub'>Currently on {tier}</div>

      {err && (
        <div
          className='ide-card'
          style={{
            marginBottom: 12,
            fontSize: 13,
            color: 'var(--ide-red)',
            borderColor: 'var(--ide-red)',
          }}
        >
          {err}
        </div>
      )}

      {tier === 'PRO' && (
        <InfoCard>
          <strong style={{color: 'var(--ide-fg-bright)'}}>You’re on PRO (grandfathered).</strong>{' '}
          The PRO plan is no longer offered to new customers — you keep all of its perks at your
          current rate. Switch to TEAM for project sharing, audit log, and external invites.
        </InfoCard>
      )}

      {/* Billing-cycle toggle */}
      <div style={{display: 'flex', gap: 6, marginBottom: 14}}>
        {(
          [
            ['monthly', 'Monthly'],
            ['yearly', 'Yearly · save 20%'],
          ] as [CycleTab, string][]
        ).map(([c, label]) => (
          <button
            key={c}
            type='button'
            className='ide-badge'
            onClick={() => setCycle(c)}
            style={{
              padding: '6px 13px',
              cursor: 'pointer',
              borderColor: cycle === c ? 'var(--ide-accent)' : undefined,
              color: cycle === c ? 'var(--ide-accent)' : undefined,
            }}
          >
            {label}
          </button>
        ))}
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))',
          gap: 12,
          alignItems: 'start',
        }}
      >
        {plans.map((p) => {
          const price = cycle === 'monthly' ? p.monthly : p.yearly;
          const features = FEATURES[p.tier] || ['—'];
          const isCurrent = p.tier === tier;
          return (
            <div
              className='ide-card'
              key={p.tier}
              style={{
                position: 'relative',
                borderColor: isCurrent
                  ? 'var(--ide-green)'
                  : p.recommended
                    ? 'var(--ide-accent)'
                    : undefined,
              }}
            >
              {(p.recommended && !isCurrent) || isCurrent ? (
                <div
                  style={{
                    position: 'absolute',
                    top: -9,
                    right: 14,
                    background: isCurrent ? 'var(--ide-green)' : 'var(--ide-accent)',
                    color: 'var(--ide-accent-fg)',
                    fontSize: 9.5,
                    fontWeight: 700,
                    padding: '3px 9px',
                    borderRadius: 999,
                    letterSpacing: '0.06em',
                  }}
                >
                  {isCurrent ? 'CURRENT' : 'RECOMMENDED'}
                </div>
              ) : null}
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'baseline',
                  marginBottom: 6,
                }}
              >
                <div style={{fontSize: 16, fontWeight: 700, color: 'var(--ide-fg-bright)'}}>
                  {p.tier}
                </div>
                <div>
                  <span
                    style={{
                      fontSize: 22,
                      fontWeight: 700,
                      letterSpacing: '-0.02em',
                      color: 'var(--ide-fg-bright)',
                    }}
                  >
                    {fmtMoney(price, p.currency)}
                  </span>
                  {price != null && price > 0 && p.perSeat && (
                    <span style={{fontSize: 13, color: 'var(--ide-fg-muted)', fontWeight: 400}}>
                      /seat/mo
                    </span>
                  )}
                </div>
              </div>
              <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginBottom: 14}}>
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
                  fontSize: 13,
                }}
              >
                {features.map((f) => (
                  <li
                    key={f}
                    style={{
                      color: f.startsWith('○') ? 'var(--ide-fg-muted)' : 'var(--ide-fg)',
                    }}
                  >
                    {f}
                  </li>
                ))}
              </ul>
              <button
                className='ide-btn'
                style={{width: '100%', marginTop: 14}}
                onClick={() => subscribe(p.tier)}
                disabled={isCurrent || p.tier === 'PRO' || busyTier === p.tier}
              >
                {busyTier === p.tier
                  ? 'Redirecting…'
                  : isCurrent
                    ? 'Current plan'
                    : p.tier === 'ENTERPRISE'
                      ? 'Talk to sales'
                      : p.tier === 'PRO'
                        ? 'Hidden plan'
                        : p.tier === 'FREE'
                          ? 'Start free'
                          : 'Upgrade'}
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
