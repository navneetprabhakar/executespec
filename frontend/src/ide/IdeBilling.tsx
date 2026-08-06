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
import {fetchPlans, type BillingPlan} from '@/lib/api/billing';
import {useAuthStore, selectNormalizedTier, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {useDocumentTitle} from '@/hooks/useDocumentTitle';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {
  billingUsageSummary,
  invoiceDisplay,
  managedKind,
  paymentAuditToRow,
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
    <div className='ide-note info' style={{marginBottom: 12}}>
      {children}
    </div>
  );
}

function Stat({label, value}: {label: string; value: React.ReactNode}) {
  return (
    <div>
      <div className='ob-stat-label'>{label}</div>
      <div className='ob-stat-value'>{value}</div>
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
  const canManageBilling =
    isSuperAdmin || user?.orgRole === 'ORG_ADMIN' || user?.orgRole === 'BILLING_ADMIN';
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
      // Best-effort: payment history is optional for accounts without billing access.
      api.getPaymentHistory(user.orgId).catch(() => []),
    ]).then(([b, u, managed, managedEvents, p]) => {
      if (cancelled) return;
      if (b.status === 'fulfilled' && b.value) setBilling(b.value);
      if (u.status === 'fulfilled' && u.value) setUsage(u.value);
      if (managed.status === 'fulfilled' && managed.value) setManagedAi(managed.value);
      if (managedEvents.status === 'fulfilled' && Array.isArray(managedEvents.value)) {
        setManagedAiEvents(managedEvents.value);
      }
      if (p.status === 'fulfilled') {
        const rows = Array.isArray(p.value) ? p.value : p.value.content;
        // RR-359 — map the audit DTO (amountMinor / gatewayProvider / eventType) to the display
        // row once, rather than casting incompatible fields and rendering every paid invoice as
        // amount `—` / Pending.
        setPayments(Array.isArray(rows) ? rows.map(paymentAuditToRow) : []);
      }
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
        <h1 className='ide-h1'>Billing</h1>
        <div className='ide-empty'>No organization on this account.</div>
      </div>
    );
  }

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <h1 className='ide-h1'>Billing</h1>
      <div className='ide-sub'>{tier}</div>

      {!loaded ? (
        <IdeLoader label='Loading billing…' />
      ) : (
        <>
          {/* Subscription summary. This is the ONE place the cycle numbers are
              stated; the old separate "This cycle" card repeated runs, tier and
              spend a second time under a different heading. */}
          <div className='ide-card'>
            <div className='ob-split' style={{alignItems: 'center', marginBottom: 14}}>
              <div className='ob-split-main'>
                <div
                  style={{
                    fontSize: 'var(--ide-t-h2)',
                    fontWeight: 700,
                    letterSpacing: '-0.02em',
                    color: 'var(--ide-fg-bright)',
                  }}
                >
                  {planStatus}
                </div>
                <div className='ob-plan-note' style={{marginTop: 2}}>
                  {billing?.gateway
                    ? `Billed via ${billing.gateway}${
                        next ? ` · renews ${new Date(next).toLocaleDateString()}` : ''
                      }`
                    : tier === 'FREE'
                      ? 'Upgrade to start a subscription'
                      : 'Managed plan'}
                </div>
              </div>
              {canManageBilling && (
                <Link className='ide-btn ob-split-action' to='/plans'>
                  Change plan
                </Link>
              )}
            </div>
            {/* C9 — auto-fit grid so the stats wrap cleanly at 375px instead of
                squeezing fixed columns. */}
            <div className='ob-stats'>
              <Stat label='Work runs this cycle' value={<>{periodRuns}</>} />
              {/* TIER-6 — a real 0 renders as 0; '—' only when the metric is
                  genuinely unknown (usage endpoint unavailable). */}
              <Stat
                label='Successful (30d)'
                value={typeof usage?.successfulRuns === 'number' ? usage.successfulRuns : '—'}
              />
              {isSuperAdmin && <Stat label='Model spend' value={`$${totalSpend.toFixed(4)}`} />}
              <Stat label='Next invoice' value={next ? new Date(next).toLocaleDateString() : '—'} />
            </div>
          </div>

          {/* Managed-AI usage is separate from run credits: run credits reserve work at
              approval, while AI credits debit per successful model call. */}
          <SectionTitle>Managed AI</SectionTitle>
          <div className='ide-card'>
            {managedAi ? (
              <>
                <div className='ob-split' style={{alignItems: 'center', marginBottom: 10}}>
                  <div className='ob-split-main'>
                    <div className='ob-row-title'>AI credits</div>
                    <div className='ob-plan-note' style={{marginTop: 2}}>
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
                <div className='ob-plan-note' style={{marginBottom: 12}}>
                  {formatManagedAiPlanMessage(managedAi)}
                </div>
                <div className='ob-stats'>
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
                  <details style={{marginTop: 12}}>
                    <summary>Recent charges · {managedAiEvents.length}</summary>
                    <div style={{marginTop: 6}}>
                      {managedAiEvents.map((event) => (
                        <div
                          key={event.id}
                          className='ob-split'
                          style={{
                            alignItems: 'center',
                            paddingTop: 8,
                            fontSize: 'var(--ide-t-caption)',
                            color: 'var(--ide-fg-muted)',
                          }}
                        >
                          <span className='ob-split-main ob-truncate'>
                            <span style={{color: 'var(--ide-fg-bright)'}}>
                              {event.agentRole || event.sourceType}
                            </span>{' '}
                            {event.model || event.provider || 'model call'}
                          </span>
                          <span className='ide-mono ob-split-action'>
                            {event.aiCreditsCharged} cr
                          </span>
                        </div>
                      ))}
                    </div>
                  </details>
                )}
              </>
            ) : (
              <div style={{fontSize: 'var(--ide-t-body)', color: 'var(--ide-fg-muted)'}}>
                Managed-AI usage is unavailable for this account or server profile.
              </div>
            )}
          </div>

          {/* FREE → BYOK upsell (TIER-2) — targets TEAM (the BYOK tier that is
              actually purchasable on /plans) and names all five key lanes
              (V237 added DeepSeek + Fireworks). Deep-links the TEAM card. */}
          {tier === 'FREE' && (
            <div className='ide-card' style={{marginTop: 12}}>
              <div className='ob-split' style={{alignItems: 'center'}}>
                <div className='ob-split-main' style={{fontSize: 'var(--ide-t-body)'}}>
                  <strong style={{color: 'var(--ide-fg-bright)'}}>
                    Bring your own provider keys.
                  </strong>{' '}
                  <span style={{color: 'var(--ide-fg-muted)'}}>
                    On TEAM you use your own Anthropic / OpenAI / DeepSeek / Fireworks keys, pay the
                    LLM bill directly, and bypass both the managed-AI cap and the platform cost cap.
                  </span>
                </div>
                <Link className='ide-btn ghost ob-split-action' to='/plans?highlight=TEAM'>
                  See TEAM
                </Link>
              </div>
            </div>
          )}

          {/* Invoices */}
          <SectionTitle>Invoices</SectionTitle>
          {payments.length === 0 ? (
            <div className='ide-note'>Invoices appear at the end of each billing cycle.</div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {payments.slice(0, 6).map((p) => {
                const invoice = invoiceDisplay(p);
                return (
                  <div key={String(p.id || invoice.number)} className='ide-invoice-row ob-row'>
                    <div className='ob-row-main'>
                      <div className='ide-invoice-title ob-row-title'>
                        <span className='ide-invoice-number'>{invoice.number}</span>
                        <span className='ide-invoice-sep'> — </span>
                        <span>{invoice.amount}</span>
                      </div>
                      <div className='ob-row-meta'>
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
                    <div className='ob-status'>
                      <IdeBadge kind={invoice.paid ? 'ok' : 'warn'}>
                        {invoice.paid ? 'Paid' : 'Pending'}
                      </IdeBadge>
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {/* Payment method (display-only — managed by the gateway, like Signal) */}
          <SectionTitle>Payment method</SectionTitle>
          <InfoCard>
            Cards are held by your payment gateway
            {billing?.gateway ? ` (${billing.gateway})` : ''}, not by ExecuteSpec.{' '}
            {canManageBilling
              ? '“Change plan” opens the gateway’s hosted checkout.'
              : 'Ask an org admin or billing admin to start or update a subscription.'}
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
  const user = useAuthStore((s) => s.user);
  const tier = useAuthStore(selectNormalizedTier);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const canManageBilling =
    isSuperAdmin || user?.orgRole === 'ORG_ADMIN' || user?.orgRole === 'BILLING_ADMIN';
  // TIER-2 deep link (/plans?highlight=TEAM) — the named card gets the accent
  // treatment so the upsell lands on a visibly emphasized tier.
  const [searchParams] = useSearchParams();
  const highlightTier = (searchParams.get('highlight') || '').toUpperCase();
  const [planRows, setPlanRows] = useState<BillingPlan[] | null>(null);
  const [plansFailed, setPlansFailed] = useState(false);
  const [err, setErr] = useState('');
  const [busyTier, setBusyTier] = useState('');

  // RR-357: pricing is retryable, because the failure mode is now "we cannot sell" rather
  // than "sell from a stale constant".
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setPlanRows(null);
    setPlansFailed(false);
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
  }, [reloadKey]);

  const loading = planRows === null && !plansFailed;
  // RR-357: fail closed for commerce. A failed fetch OR an empty 200 (indistinguishable from a
  // misconfigured plan table) means we do not know the prices — so show a retryable
  // pricing-unavailable state rather than a stale hardcoded ladder customers could buy from.
  //
  // This is deliberately keyed on the raw API rows, NOT on the derived cards: planCardsFrom
  // synthesizes the FREE and ENTERPRISE bookends unconditionally, so an empty response still
  // produces two cards and a `plans.length === 0` test would never fire.
  const pricingUnavailable = plansFailed || (planRows !== null && planRows.length === 0);

  const plans: PlanCard[] = useMemo(() => {
    if (planRows === null || planRows.length === 0) return [];
    return planCardsFrom(planRows);
  }, [planRows]);

  // ACCENT DISCIPLINE — exactly one filled commit action on this surface. The
  // deep-linked tier wins (that is what the customer was sent here for), else
  // the recommended tier. The current plan and ENTERPRISE (a mailto, not a
  // checkout) never take the accent, and neither does anything a viewer without
  // billing rights cannot actually buy.
  const accentTier = useMemo(() => {
    if (!canManageBilling) return null;
    const buyable = (p: PlanCard) =>
      p.tier !== tier && p.tier !== 'ENTERPRISE' && p.tier !== 'FREE';
    return (
      plans.find((p) => p.tier === highlightTier && buyable(p))?.tier ??
      plans.find((p) => p.recommended && buyable(p))?.tier ??
      null
    );
  }, [plans, highlightTier, tier, canManageBilling]);

  // Subscribe → gateway hosted checkout. Flow unchanged by TIER-1 (same
  // endpoint + redirect handling): ENTERPRISE → sales mailto, FREE → no-op,
  // else POST /api/billing/subscribe and redirect to the returned checkout
  // URL. The button always buys the headline (monthly) cadence now that the
  // global cycle toggle is gone.
  async function subscribe(t: string) {
    if (!canManageBilling) return;
    // RR-357: never start a checkout while the displayed price is unknown.
    if (pricingUnavailable) return;
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
      <h1 className='ide-h1'>Plans</h1>
      <div className='ide-sub'>Currently on {tier}</div>

      {err && (
        <div className='ide-note bad' style={{marginBottom: 12}}>
          {err}
        </div>
      )}

      {loading ? (
        <IdeLoader label='Loading plans…' />
      ) : pricingUnavailable ? (
        /* RR-357 — commerce fails closed: no invented prices, no purchase path. */
        <div className='ide-card' style={{maxWidth: 520}}>
          <div style={{fontWeight: 700, color: 'var(--ide-fg-bright)', marginBottom: 6}}>
            Pricing is temporarily unavailable
          </div>
          <div
            style={{fontSize: 'var(--ide-t-body)', color: 'var(--ide-fg-muted)', marginBottom: 14}}
          >
            We could not load the current plans, so we will not show prices we cannot confirm. Your
            existing subscription and access are unaffected. Please try again in a moment.
          </div>
          <button className='ide-btn' onClick={() => setReloadKey((k) => k + 1)}>
            Retry
          </button>
        </div>
      ) : (
        <div className='ob-plan-grid'>
          {plans.map((p) => {
            // No invented feature claims: a tier with no curated bullet list
            // renders none rather than a placeholder row.
            const features = PLAN_FEATURES[p.tier] ?? [];
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
                className='ide-card ob-plan-card'
                key={p.tier}
                /* One visible edge per box: the state is carried by the border
                   colour plus the flag pill — never a second stroked ring. */
                style={{
                  borderColor: isCurrent
                    ? 'var(--ide-green)'
                    : isHighlight || p.recommended
                      ? 'var(--ide-accent)'
                      : undefined,
                }}
              >
                {(p.recommended && !isCurrent) || isCurrent ? (
                  <div className={`ob-plan-flag`}>{isCurrent ? 'CURRENT' : 'RECOMMENDED'}</div>
                ) : null}
                <div className='ob-plan-head'>
                  <div className='ob-plan-tier'>{p.tier}</div>
                  <div>
                    <span className='ob-plan-price'>{planMoney(headlineAmount, p.currency)}</span>
                    {headlineUnit && <span className='ob-plan-unit'>{headlineUnit}</span>}
                  </div>
                </div>
                {/* Per-card yearly line — only when the API has a yearly row
                    for this tier (TEAM is monthly-only per V165). */}
                {showYearlyLine && (
                  <div className='ob-plan-note' style={{textAlign: 'right'}}>
                    or {planMoney(p.yearly, p.currency)}/yr
                    {p.yearlySavePct != null && p.yearlySavePct > 0
                      ? ` · save ${p.yearlySavePct}%`
                      : ''}
                  </div>
                )}
                {PLAN_SUBCOPY[p.tier] && <div className='ob-plan-note'>{PLAN_SUBCOPY[p.tier]}</div>}
                {p.recommended && (
                  <div className='ob-plan-note ob-plan-note-accent'>
                    Most popular — bring your own keys
                  </div>
                )}
                {features.length > 0 ? (
                  <ul className='ob-plan-features'>
                    {features.map((f) => (
                      <li key={f} className={f.startsWith('○') ? 'ob-muted' : undefined}>
                        {f}
                      </li>
                    ))}
                  </ul>
                ) : (
                  /* No curated bullet list for this tier. Point at the public
                     pricing page rather than inventing entitlement claims — and
                     keep the flex slack so the CTA still lands on the row's
                     shared baseline. */
                  <div className='ob-plan-features ob-plan-note'>
                    <Link to='/pricing'>See what this plan includes</Link>
                  </div>
                )}
                <button
                  /* ACCENT DISCIPLINE: exactly one filled commit action on this
                     screen. Every other tier's CTA is a ghost, so the page does
                     not present five equally-loud "buy" buttons. */
                  className={`ide-btn ob-plan-cta${p.tier === accentTier ? '' : ' ghost'}`}
                  onClick={() => subscribe(p.tier)}
                  disabled={!canManageBilling || isCurrent || busyTier === p.tier}
                >
                  {!canManageBilling
                    ? 'Admin only'
                    : busyTier === p.tier
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
