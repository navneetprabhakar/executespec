/* IDE-native port of the Signal BILLING cluster:
 *   - IdeBilling ← signal/pages/account/BillingPage.tsx (/billing)
 *   - IdePlans   ← signal/pages/account/PlansPage.tsx   (/plans)
 *
 * IdeBilling mirrors the Signal original's endpoints (getAnalyticsBilling /
 * getAnalyticsUsage / raw payment-history fetch) re-rendered in the IDE visual
 * language. IdePlans was rewritten for TIER-1 (UI/UX review 2026-06-11, P0):
 * tier cards now render from GET /api/billing/plans (the DB-driven endpoint,
 * same source of truth as the public /pricing page) instead of a hardcoded
 * array that contradicted the DB ladder (no PRO card; phantom yearly-TEAM
 * pricing under a default-on toggle; invented "500 credits / seat" and
 * "$25 hard cost cap" limits). The subscribe → gateway-redirect flow is
 * unchanged.
 */

import {useEffect, useMemo, useState} from 'react';
import {Link, useSearchParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {fetchPlans, type BillingPlan} from '@/lib/billing-api';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import {useDocumentTitle} from '@/hooks/useDocumentTitle';
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
  useDocumentTitle('Billing');
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
            {/* C9 — auto-fit grid so the stats wrap cleanly at 375px instead of
                squeezing three fixed columns. */}
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
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

          {/* FREE → BYOK upsell (TIER-2) — targets TEAM (the BYOK tier that is
              actually purchasable on /plans) and names all five key lanes
              (V237 added DeepSeek + Fireworks). Deep-links the TEAM card. */}
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
                    Upgrade to TEAM and run on your own Anthropic / OpenAI / Gemini / DeepSeek /
                    Fireworks keys — you pay the LLM bill directly, no platform cap.
                  </span>
                </div>
                <Link
                  className='ide-btn ghost'
                  to='/plans?highlight=TEAM'
                  style={{flexShrink: 0, textDecoration: 'none'}}
                >
                  See TEAM
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
              {/* TIER-6 — a real 0 renders as 0; '—' only when the metric is
                  genuinely unknown (usage endpoint unavailable). */}
              <span>{typeof usage?.successfulRuns === 'number' ? usage.successfulRuns : '—'}</span>
            </div>
          </div>

          {/* Invoices */}
          <SectionTitle>Invoices</SectionTitle>
          {payments.length === 0 ? (
            <div className='ide-card' style={{fontSize: 13}}>
              <div style={{fontWeight: 700, color: 'var(--ide-fg-bright)', marginBottom: 4}}>
                Invoices
              </div>
              <div style={{color: 'var(--ide-fg-muted)', lineHeight: 1.5}}>
                Invoices appear at the end of each billing cycle. Your subscription status is shown
                above.
              </div>
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

/* TIER-1 (P0) — cards render from GET /api/billing/plans (active + LISTED
 * billing_plan rows). Prices/cadence/yearly availability are the API's call;
 * the maps below carry only product truths (caps, seats, feature bullets),
 * which mirror tier_config + the truth-passed public /pricing page. */

// Feature bullets by tier. ✓ = included, ○ = constraint (rendered muted).
const FEATURES: Record<string, string[]> = {
  FREE: [
    '✓ 15 runs / month',
    '✓ Small specs (1 credit)',
    '✓ Email support',
    '○ $3 per-run cost cap',
    '○ 1 project · 1 repo',
  ],
  PRO: [
    '✓ 30 runs / month on managed keys',
    '✓ Anthropic · OpenAI · Gemini',
    '✓ PR review + code review (4 scans/mo)',
    '✓ 2 projects · 2 repos each',
    '○ $5 per-run cap — BYOK lifts it',
  ],
  TEAM: [
    '✓ Unlimited runs on your keys',
    '✓ No platform cost cap',
    '✓ Org spend caps + approval thresholds',
    '✓ 5 projects · cross-repo runs',
    '✓ Unlimited code reviews + sharing',
    '✓ Autofix: PR review · tech debt · code scans',
  ],
  ENTERPRISE: [
    '✓ Unlimited runs · no cost cap',
    '✓ Model matrix override + per-run model pins',
    '✓ OpenRouter gateway models',
    '✓ Standards · Contracts · ADRs',
    '✓ Governance: audit trail · repo allowlists',
    '✓ Dedicated support',
  ],
};

// Card sub-copy — product truths (runs/caps/seats), not prices.
const SUBCOPY: Record<string, string> = {
  FREE: 'For solo developers · 15 runs/month · $3/run cap',
  PRO: 'For individuals · 30 runs/month on our keys · $5/run cap · BYOK lifts the cap',
  TEAM: 'Unlimited runs on your own keys · 1–20 seats · no per-run cap',
  ENTERPRISE: 'SSO · SLA · custom models · dedicated support',
};

interface PlanCard {
  tier: string;
  /** Monthly price in display units (dollars / rupees); null ⇒ "Custom". */
  monthly: number | null;
  /** Yearly total in display units; null ⇒ the tier has no yearly plan row. */
  yearly: number | null;
  /** Discount % for the yearly line (shown only when > 0). */
  yearlySavePct: number | null;
  currency: string;
  perSeat: boolean;
  recommended?: boolean;
}

const TIER_ORDER = ['FREE', 'PRO', 'TEAM', 'ENTERPRISE'];

// Used ONLY when GET /api/billing/plans fails or returns nothing. Mirrors the
// live DB ladder — PRO $29/mo + $289/yr at 17% off (V176/V177), TEAM $39/seat
// monthly-only (V165/V206) — so even the degraded page can't resurrect the old
// invented pricing.
const FALLBACK_CARDS: PlanCard[] = [
  {tier: 'FREE', monthly: 0, yearly: null, yearlySavePct: null, currency: 'USD', perSeat: false},
  {tier: 'PRO', monthly: 29, yearly: 289, yearlySavePct: 17, currency: 'USD', perSeat: false},
  {
    tier: 'TEAM',
    monthly: 39,
    yearly: null,
    yearlySavePct: null,
    currency: 'USD',
    perSeat: true,
    recommended: true,
  },
  {
    tier: 'ENTERPRISE',
    monthly: null,
    yearly: null,
    yearlySavePct: null,
    currency: 'USD',
    perSeat: false,
  },
];

function fmtMoney(amount: number | null, currency: string): string {
  if (amount == null) return 'Custom';
  if (amount === 0) return 'Free';
  const symbol = currency === 'USD' ? '$' : currency === 'INR' ? '₹' : `${currency} `;
  return `${symbol}${amount}`;
}

/**
 * Group raw /api/billing/plans rows (one row per tier × period × currency —
 * USD amounts in cents, INR in paise; the other currency's columns are 0)
 * into one card per tier. USD rows win when both currencies exist. FREE +
 * ENTERPRISE render even without a row (nothing to buy — $0 / talk to sales);
 * PRO / TEAM presence is deliberately API-driven so lifecycle changes in the
 * DB (LISTED/HIDDEN) flow straight through.
 */
function planCardsFrom(rows: BillingPlan[]): PlanCard[] {
  interface Bucket {
    monthly: number | null;
    yearly: number | null;
    savePct: number | null;
    monthlyPerSeat: boolean | null;
    yearlyPerSeat: boolean | null;
  }
  const buckets = new Map<string, Bucket>();
  const apiTierOrder: string[] = [];

  for (const row of rows) {
    if (!row || row.active === false) continue;
    const tier = (row.tier || '').toUpperCase();
    if (!tier) continue;
    const seatUsd = row.perSeatUsd ?? 0;
    const baseUsd = row.baseAmountUsd ?? 0;
    const seatInr = row.perSeatInr ?? 0;
    const baseInr = row.baseAmountInr ?? 0;
    let currency: 'USD' | 'INR';
    let perSeat: boolean;
    let amount: number;
    if (seatUsd > 0 || baseUsd > 0) {
      currency = 'USD';
      perSeat = seatUsd > 0;
      amount = (perSeat ? seatUsd : baseUsd) / 100;
    } else if (seatInr > 0 || baseInr > 0) {
      currency = 'INR';
      perSeat = seatInr > 0;
      amount = (perSeat ? seatInr : baseInr) / 100;
    } else {
      continue; // zero-amount row (e.g. a FREE placeholder) — synthesized below
    }
    const period = (row.period || '').toUpperCase();
    const isYearly = period === 'YEARLY' || period === 'ANNUAL';
    if (!isYearly && period !== 'MONTHLY') continue;
    const key = `${tier}:${currency}`;
    let b = buckets.get(key);
    if (!b) {
      b = {monthly: null, yearly: null, savePct: null, monthlyPerSeat: null, yearlyPerSeat: null};
      buckets.set(key, b);
    }
    // First row per slot wins — V207 guarantees one canonical active plan per
    // tier/period/currency, so dupes here mean stale rows we shouldn't prefer.
    if (isYearly) {
      if (b.yearly == null) {
        b.yearly = amount;
        b.yearlyPerSeat = perSeat;
        const pct = row.annualDiscountPct ?? 0;
        if (pct > 0) b.savePct = pct;
      }
    } else if (b.monthly == null) {
      b.monthly = amount;
      b.monthlyPerSeat = perSeat;
    }
    if (!apiTierOrder.includes(tier)) apiTierOrder.push(tier);
  }

  const tiers = [...TIER_ORDER, ...apiTierOrder.filter((t) => !TIER_ORDER.includes(t))];
  const cards: PlanCard[] = [];
  for (const tier of tiers) {
    const usd = buckets.get(`${tier}:USD`);
    const b = usd ?? buckets.get(`${tier}:INR`);
    if (b) {
      let savePct = b.savePct;
      if (savePct == null && b.monthly != null && b.monthly > 0 && b.yearly != null) {
        const computed = Math.round((1 - b.yearly / (b.monthly * 12)) * 100);
        if (computed > 0) savePct = computed;
      }
      cards.push({
        tier,
        monthly: b.monthly,
        yearly: b.yearly,
        yearlySavePct: savePct,
        currency: usd ? 'USD' : 'INR',
        perSeat: b.monthlyPerSeat ?? b.yearlyPerSeat ?? false,
        recommended: tier === 'TEAM',
      });
    } else if (tier === 'FREE') {
      cards.push({
        tier,
        monthly: 0,
        yearly: null,
        yearlySavePct: null,
        currency: 'USD',
        perSeat: false,
      });
    } else if (tier === 'ENTERPRISE') {
      cards.push({
        tier,
        monthly: null,
        yearly: null,
        yearlySavePct: null,
        currency: 'USD',
        perSeat: false,
      });
    }
  }
  return cards;
}

export function IdePlans() {
  useDocumentTitle('Plans');
  const tier = useAuthStore(selectNormalizedTier);
  // TIER-2 deep link (/plans?highlight=TEAM) — the named card gets the accent
  // treatment so the upsell lands on a visibly emphasized tier.
  const [searchParams] = useSearchParams();
  const highlightTier = (searchParams.get('highlight') || '').toUpperCase();
  const [planRows, setPlanRows] = useState<BillingPlan[] | null>(null);
  const [plansFailed, setPlansFailed] = useState(false);
  const [err, setErr] = useState('');
  const [busyTier, setBusyTier] = useState('');

  useEffect(() => {
    let cancelled = false;
    fetchPlans()
      .then((rows) => {
        if (!cancelled) setPlanRows(Array.isArray(rows) ? rows : []);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setPlansFailed(true);
        setErr(e instanceof Error ? e.message : 'Failed to load plans.');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const plans: PlanCard[] = useMemo(() => {
    if (plansFailed) return FALLBACK_CARDS;
    if (planRows === null) return []; // still loading
    // An empty 200 is indistinguishable from a misconfigured plan table —
    // degrade to the known ladder rather than selling nothing.
    if (planRows.length === 0) return FALLBACK_CARDS;
    return planCardsFrom(planRows);
  }, [planRows, plansFailed]);

  const loading = planRows === null && !plansFailed;

  // Subscribe → gateway hosted checkout. Flow unchanged by TIER-1 (same
  // endpoint + redirect handling): ENTERPRISE → sales mailto, FREE → no-op,
  // else POST /api/billing/subscribe and redirect to the returned checkout
  // URL. The button always buys the headline (monthly) cadence now that the
  // global cycle toggle is gone.
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
        body: JSON.stringify({tier: t, cycle: 'MONTHLY'}),
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

      {loading ? (
        <IdeLoader label='Loading plans…' />
      ) : (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))',
            gap: 12,
            alignItems: 'start',
          }}
        >
          {plans.map((p) => {
            const features = FEATURES[p.tier] || ['—'];
            const isCurrent = p.tier === tier;
            const isHighlight = p.tier === highlightTier && !isCurrent;
            const monthlyKnown = p.monthly != null;
            // Headline = monthly price; a yearly-only tier (no monthly row)
            // degrades to its yearly price; no rows at all ⇒ "Custom".
            const headlineAmount = monthlyKnown ? p.monthly : p.yearly;
            const headlineUnit =
              monthlyKnown && p.monthly != null && p.monthly > 0
                ? p.perSeat
                  ? '/seat/mo'
                  : '/mo'
                : !monthlyKnown && p.yearly != null && p.yearly > 0
                  ? p.perSeat
                    ? '/seat/yr'
                    : '/yr'
                  : '';
            const showYearlyLine = monthlyKnown && p.yearly != null && p.yearly > 0;
            return (
              <div
                className='ide-card'
                key={p.tier}
                style={{
                  position: 'relative',
                  borderColor: isCurrent
                    ? 'var(--ide-green)'
                    : isHighlight || p.recommended
                      ? 'var(--ide-accent)'
                      : undefined,
                  boxShadow: isHighlight ? '0 0 0 1px var(--ide-accent)' : undefined,
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
                      {fmtMoney(headlineAmount, p.currency)}
                    </span>
                    {headlineUnit && (
                      <span style={{fontSize: 13, color: 'var(--ide-fg-muted)', fontWeight: 400}}>
                        {headlineUnit}
                      </span>
                    )}
                  </div>
                </div>
                {/* Per-card yearly line — only when the API has a yearly row
                    for this tier (TEAM is monthly-only per V165). */}
                {showYearlyLine && (
                  <div
                    style={{
                      fontSize: 11.5,
                      color: 'var(--ide-fg-muted)',
                      textAlign: 'right',
                      marginBottom: 6,
                    }}
                  >
                    or {fmtMoney(p.yearly, p.currency)}/yr
                    {p.yearlySavePct != null && p.yearlySavePct > 0
                      ? ` · save ${p.yearlySavePct}%`
                      : ''}
                  </div>
                )}
                <div
                  style={{
                    fontSize: 12,
                    color: 'var(--ide-fg-muted)',
                    marginBottom: p.recommended ? 4 : 14,
                  }}
                >
                  {SUBCOPY[p.tier] || ''}
                </div>
                {p.recommended && (
                  <div
                    style={{
                      fontSize: 11.5,
                      fontWeight: 600,
                      color: 'var(--ide-accent)',
                      marginBottom: 14,
                    }}
                  >
                    Most popular — bring your own keys
                  </div>
                )}
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
                  disabled={isCurrent || busyTier === p.tier}
                >
                  {busyTier === p.tier
                    ? 'Redirecting…'
                    : isCurrent
                      ? 'Current plan'
                      : p.tier === 'ENTERPRISE'
                        ? 'Talk to sales'
                        : p.tier === 'FREE'
                          ? 'Start free'
                          : 'Upgrade'}
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
