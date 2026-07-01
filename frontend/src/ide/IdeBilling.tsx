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
import {useAuthStore, selectNormalizedTier, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {useDocumentTitle} from '@/hooks/useDocumentTitle';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {
  billingUsageSummary,
  FALLBACK_PLAN_CARDS,
  invoiceDisplay,
  managedKind,
  planCardsFrom,
  planMoney,
  PLAN_FEATURES,
  PLAN_SUBCOPY,
  type PaymentRowLite,
  type PlanCard,
} from '@/ide/billingUtils';
import {
  formatManagedAiPlanMessage,
  hasUnlimitedAiCredits,
  managedAiStatusLabel,
} from '@/ide/planUtils';

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

export function IdeBilling() {
  useDocumentTitle('Billing');
  const user = useAuthStore((s) => s.user);
  const tier = useAuthStore(selectNormalizedTier);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const permissions = useAuthStore((s) => s.permissions);
  const [billing, setBilling] = useState<api.BillingOverviewData | null>(null);
  const [usage, setUsage] = useState<api.UsageMetrics | null>(null);
  const [managedAi, setManagedAi] = useState<api.ManagedAiBalance | null>(null);
  const [managedAiEvents, setManagedAiEvents] = useState<api.ManagedAiUsageEvent[]>([]);
  const [payments, setPayments] = useState<PaymentRowLite[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (!user?.orgId) return;
    let cancelled = false;
    Promise.allSettled([
      api.getAnalyticsBilling('org', user.orgId).catch(() => null),
      api.getAnalyticsUsage('org', user.orgId, 30).catch(() => null),
      api.getManagedAiBalance().catch(() => null),
      api.getManagedAiEvents(5).catch(() => []),
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
    ]).then(([b, u, managed, managedEvents, p]) => {
      if (cancelled) return;
      if (b.status === 'fulfilled' && b.value) setBilling(b.value);
      if (u.status === 'fulfilled' && u.value) setUsage(u.value);
      if (managed.status === 'fulfilled' && managed.value) setManagedAi(managed.value);
      if (managedEvents.status === 'fulfilled' && Array.isArray(managedEvents.value)) {
        setManagedAiEvents(managedEvents.value);
      }
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
  const {periodRuns, totalSpend, planStatus} = billingUsageSummary(usage, permissions, tier);
  const next = billing?.nextBillingDate || '';

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
              <Stat label='Work runs' value={<>{periodRuns}</>} />
              {isSuperAdmin && <Stat label='Model spend' value={`$${totalSpend.toFixed(2)}`} />}
              <Stat label='Next invoice' value={next ? new Date(next).toLocaleDateString() : '—'} />
            </div>
          </div>

          {/* Managed-AI usage is separate from run credits: run credits reserve work at
              approval, while AI credits debit per successful model call. */}
          <SectionTitle>Managed AI</SectionTitle>
          <div className='ide-card'>
            {managedAi ? (
              <>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 10,
                    flexWrap: 'wrap',
                    marginBottom: 12,
                  }}
                >
                  <div>
                    <div
                      style={{
                        fontSize: 13.5,
                        fontWeight: 700,
                        color: 'var(--ide-fg-bright)',
                      }}
                    >
                      AI credits
                    </div>
                    <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', marginTop: 2}}>
                      $1.25 = 100 AI credits. Frontier calls step down automatically near policy
                      thresholds.
                    </div>
                  </div>
                  <IdeBadge
                    kind={
                      hasUnlimitedAiCredits(managedAi) ? 'ok' : managedKind(managedAi.routingState)
                    }
                  >
                    {managedAiStatusLabel(managedAi)}
                  </IdeBadge>
                </div>
                <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', marginBottom: 12}}>
                  {formatManagedAiPlanMessage(managedAi)}
                </div>
                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
                    gap: 12,
                    paddingTop: 12,
                    borderTop: '1px solid var(--ide-border)',
                  }}
                >
                  {(() => {
                    const unlimited = hasUnlimitedAiCredits(managedAi);
                    const totalCredits =
                      managedAi.includedAiCredits + (managedAi.purchasedAiCredits ?? 0);
                    return (
                      <>
                        <Stat
                          label='Credits used'
                          value={
                            unlimited ? 'No limit' : `${managedAi.aiCreditsUsed} / ${totalCredits}`
                          }
                        />
                        <Stat
                          label={unlimited ? 'Credit limit' : 'Credits left'}
                          value={unlimited ? 'Unlimited' : managedAi.aiCreditsRemaining}
                        />
                      </>
                    );
                  })()}
                </div>
                {managedAiEvents.length > 0 && (
                  <div style={{marginTop: 14, borderTop: '1px solid var(--ide-border)'}}>
                    {managedAiEvents.map((event) => (
                      <div
                        key={event.id}
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          gap: 10,
                          paddingTop: 10,
                          fontSize: 12,
                          color: 'var(--ide-fg-muted)',
                        }}
                      >
                        <span style={{minWidth: 0}}>
                          <span style={{color: 'var(--ide-fg-bright)'}}>
                            {event.agentRole || event.sourceType}
                          </span>{' '}
                          {event.model || event.provider || 'model call'}
                        </span>
                        <span className='ide-mono' style={{flexShrink: 0}}>
                          {event.aiCreditsCharged} cr
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </>
            ) : (
              <div style={{fontSize: 13, color: 'var(--ide-fg-muted)'}}>
                Managed-AI usage is unavailable for this account or server profile.
              </div>
            )}
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
                    Bring your own provider keys, bypass the managed-AI cap.
                  </strong>{' '}
                  <span style={{color: 'var(--ide-fg-muted)'}}>
                    Upgrade to TEAM and use your own Anthropic / OpenAI / Gemini / DeepSeek /
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
              Work runs this cycle
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
                runs
              </span>
            </div>
            <div className='ide-kv'>
              <span>Tier</span>
              <span>{tier}</span>
            </div>
            <div className='ide-kv'>
              <span>{isSuperAdmin ? 'Model spend (cycle)' : 'Billing meter'}</span>
              <span>{isSuperAdmin ? `$${totalSpend.toFixed(2)}` : 'AI credits'}</span>
            </div>
            <div className='ide-kv' style={{borderBottom: 'none'}}>
              <span>Successful work (30d)</span>
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
                const invoice = invoiceDisplay(p);
                return (
                  <div
                    key={String(p.id || invoice.number)}
                    className='ide-invoice-row'
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
                      <div
                        className='ide-invoice-title'
                        style={{fontSize: 13, fontWeight: 600, color: 'var(--ide-fg-bright)'}}
                      >
                        <span className='ide-invoice-number'>{invoice.number}</span>
                        <span className='ide-invoice-sep'> — </span>
                        <span>{invoice.amount}</span>
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
                        <span>{invoice.statusLabel}</span>
                        {p.createdAt && (
                          <>
                            <span>·</span>
                            <span>{new Date(p.createdAt).toLocaleDateString()}</span>
                          </>
                        )}
                      </div>
                    </div>
                    <IdeBadge kind={invoice.paid ? 'ok' : 'warn'}>
                      {invoice.paid ? 'Paid' : 'Pending'}
                    </IdeBadge>
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
    if (plansFailed) return FALLBACK_PLAN_CARDS;
    if (planRows === null) return []; // still loading
    // An empty 200 is indistinguishable from a misconfigured plan table —
    // degrade to the known ladder rather than selling nothing.
    if (planRows.length === 0) return FALLBACK_PLAN_CARDS;
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
            const features = PLAN_FEATURES[p.tier] || ['—'];
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
                      {planMoney(headlineAmount, p.currency)}
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
                    or {planMoney(p.yearly, p.currency)}/yr
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
                  {PLAN_SUBCOPY[p.tier] || ''}
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
