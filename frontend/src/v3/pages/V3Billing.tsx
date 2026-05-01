import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getV3Entitlements, type V3Entitlements } from '../lib/api';
import PricingGrid from '../components/PricingGrid';
import Pill from './../components/Pill';
import { trackV3, V3_EVENTS } from '../lib/events';
import type { TierKey } from '../lib/pricing';
import { useAuthStore } from '@/stores/useAuthStore';
import {
  canonicalDisplayName,
  canonicalTier,
  formatRunUsage,
  isPaidTier,
} from '../lib/tierDisplay';

/**
 * R-141 — billing & plan, v3.
 *
 * Three sections, top to bottom (greenfield §4.9):
 *   1. Plan card — current tier, lifetime/usage line, primary CTA
 *   2. Usage this month — runs + spend (read from EntitlementsResponse)
 *   3. Pricing grid — Free / Pro / Team + Enterprise footer line
 *
 * Reads from /api/v3/entitlements (R-142). NO 286%-over-budget banner;
 * NO "Free" name on an Enterprise org. The strict-review case is
 * structurally impossible under this contract.
 */
export default function V3Billing() {
  const [ent, setEnt] = useState<V3Entitlements | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Task 4 (2026-05-01 fix list): canonical tier comes from /api/auth/me,
  // not from /api/v3/entitlements which can lag behind for users whose
  // backing org has no real subscription record.
  const meTier = useAuthStore((s) => s.user?.tier ?? null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getV3Entitlements()
      .then((e) => {
        if (!cancelled) {
          setEnt(e);
          setError(null);
        }
      })
      .catch((e) => {
        if (!cancelled) setError((e as Error)?.message ?? 'Failed to load entitlements');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  function onPickTier(tier: TierKey) {
    if (tier === 'ENTERPRISE') {
      window.location.href = '/trust';
      return;
    }
    trackV3(V3_EVENTS.TIER_UPGRADED, { fromTier: ent?.tierKey, toTier: tier });
    // Wave 2 follow-up: real Stripe checkout. Today the click logs the
    // intent + sends the user to v2's /plans page where existing
    // checkout still works.
    window.location.href = `/plans?tier=${tier}&from=v3`;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, paddingBottom: 32 }}>
      <div>
        <h1 style={{ fontSize: 22, fontWeight: 600, color: 'var(--v3-text)', margin: 0 }}>
          Billing & plan
        </h1>
        <p style={{ fontSize: 12, color: 'var(--v3-text-muted)', margin: '4px 0 0' }}>
          What you're on · what you've used · what an upgrade buys you.
        </p>
      </div>

      {error && (
        <div
          role="alert"
          style={{
            padding: 14,
            background: 'rgba(239,68,68,0.12)',
            border: '1px solid rgba(239,68,68,0.36)',
            color: '#FCA5A5',
            borderRadius: 8,
            fontSize: 12,
          }}
        >
          {error} · <Link to="/home" style={{ color: 'var(--v3-brand)' }}>Back home</Link>
        </div>
      )}

      {loading && !ent && (
        <div className="v3-placeholder">
          <p>Loading your plan…</p>
        </div>
      )}

      {ent && (
        <>
          {/* ── Plan card ──────────────────────────────────────────────── */}
          <PlanCard ent={ent} meTier={meTier} />

          {/* ── Usage card ─────────────────────────────────────────────── */}
          <UsageCard ent={ent} />

          {/* ── Pricing grid ───────────────────────────────────────────── */}
          <section
            style={{
              background: 'var(--v3-surface)',
              border: '1px solid var(--v3-border)',
              borderRadius: 10,
              padding: 16,
            }}
          >
            <div style={{ marginBottom: 12, textAlign: 'center' }}>
              <h2
                style={{
                  margin: 0,
                  fontSize: 16,
                  fontWeight: 600,
                  color: 'var(--v3-text)',
                }}
              >
                Plans
              </h2>
              <p
                style={{
                  margin: '4px 0 0',
                  fontSize: 11.5,
                  color: 'var(--v3-text-muted)',
                }}
              >
                Pick what fits — change any time.
              </p>
            </div>
            {/* Task 4: Pricing-grid "Current" badge sources from canonical tier
             * (me.tier) so PRO users see the Pro card highlighted instead of
             * Free when entitlements drift. */}
            <PricingGrid
              currentTier={canonicalTier(meTier, ent.tierKey) as TierKey}
              onPickTier={onPickTier}
            />
          </section>

          {/* ── Receipts + tax (collapsed by default) ──────────────────── */}
          <ReceiptsSection ent={ent} />
        </>
      )}
    </div>
  );
}

// ─── Plan card ──────────────────────────────────────────────────────────────

function PlanCard({ ent, meTier }: { ent: V3Entitlements; meTier: string | null }) {
  // Task 1 + Task 4 (2026-05-01 fix list): canonical tier comes from me.tier;
  // run-usage line is computed defensively so MAX_VALUE leaks from the
  // backend's unlimited sentinel never reach the UI.
  const tier = canonicalTier(meTier, ent.tierKey);
  const displayName = canonicalDisplayName(tier);
  const usage = formatRunUsage(ent, tier);
  const showFreeUpgrade = !isPaidTier(tier);
  // Pill tone: paid tiers always get "brand" (the unlimited badge);
  // FREE gets "neutral".
  const pillTone = isPaidTier(tier) ? 'brand' : 'neutral';

  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
          flexWrap: 'wrap',
        }}
      >
        <div>
          <h2
            style={{
              margin: 0,
              fontSize: 16,
              fontWeight: 600,
              color: 'var(--v3-text)',
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            You're on {displayName}.
            <Pill tone={pillTone} size="sm">
              {displayName}
            </Pill>
          </h2>
          <p style={{ margin: '4px 0 0', fontSize: 12.5, color: 'var(--v3-text-muted)' }}>
            {usage.line}
          </p>
        </div>
        {showFreeUpgrade && (
          <a
            href="#plans-grid"
            style={{
              padding: '6px 14px',
              fontSize: 12.5,
              fontWeight: 600,
              background: 'var(--v3-brand)',
              color: 'var(--v3-text-on-brand)',
              border: 'none',
              borderRadius: 6,
              textDecoration: 'none',
              fontFamily: 'inherit',
            }}
          >
            Upgrade →
          </a>
        )}
      </div>

      {/* Free-tier balance bar — only renders when meaningful (FREE
       * tier with a sane monthly cap; paid tiers and sentinel-leak
       * cases are filtered out by formatRunUsage). The bar tracks
       * the monthly cap (tier_config.monthly_run_limit, V145=10) so
       * the four FREE-user surfaces (top-bar, billing hero,
       * sidebar, pricing card) all agree on one number — see
       * Task 3 of the 2026-05-01 fix list. */}
      {usage.showBalanceBar && ent.runLimitMonthly > 0 && (
        <div style={{ marginTop: 10 }}>
          <div
            style={{
              height: 6,
              background: 'var(--v3-bg)',
              borderRadius: 4,
              overflow: 'hidden',
              border: '1px solid var(--v3-border)',
            }}
          >
            <div
              style={{
                height: '100%',
                width: `${Math.min(
                  100,
                  ((ent.runLimitMonthly - Math.min(ent.currentPeriodRunCount, ent.runLimitMonthly)) /
                    ent.runLimitMonthly) *
                    100
                )}%`,
                background:
                  ent.runLimitMonthly - ent.currentPeriodRunCount <= 1
                    ? 'var(--v3-warning)'
                    : 'var(--v3-brand)',
              }}
            />
          </div>
        </div>
      )}
    </section>
  );
}

// ─── Usage card ─────────────────────────────────────────────────────────────

function UsageCard({ ent }: { ent: V3Entitlements }) {
  // Suppress the "soft budget" banner entirely on unlimited tiers — the
  // strict-review fix.
  const showBudget = !ent.isUnlimited && ent.softCostBudgetUsd != null;
  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <h2
        style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}
      >
        Usage this month
      </h2>
      <div
        style={{
          marginTop: 10,
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
          gap: 12,
          fontSize: 12,
        }}
      >
        <Stat label="Runs" value={String(ent.currentPeriodRunCount)} />
        <Stat
          label="Spend"
          value={
            ent.currentPeriodCostUsd != null
              ? `$${ent.currentPeriodCostUsd.toFixed(2)}`
              : '—'
          }
        />
        {showBudget && (
          <Stat
            label="Soft budget"
            value={`$${ent.softCostBudgetUsd?.toFixed(2)}`}
            hint="advisory"
          />
        )}
      </div>
    </section>
  );
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div>
      <div style={{ color: 'var(--v3-text-muted)', fontSize: 11 }}>{label}</div>
      <div style={{ color: 'var(--v3-text)', fontSize: 18, fontWeight: 600 }}>
        {value}
      </div>
      {hint && (
        <div style={{ color: 'var(--v3-text-subtle)', fontSize: 10 }}>{hint}</div>
      )}
    </div>
  );
}

// ─── Receipts ───────────────────────────────────────────────────────────────

function ReceiptsSection({ ent }: { ent: V3Entitlements }) {
  // GST tab only renders when the org is in India. Geo-gate per
  // strict-review §R-035 — we don't have org.country in the
  // entitlements response yet, so we conservatively hide the GST
  // section behind a single feature flag for now.
  const showGst = false; // TODO(R-141 follow-up): consume org.country once exposed

  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <h2
        style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}
      >
        Receipts and billing profile
      </h2>
      <div
        style={{
          marginTop: 10,
          display: 'flex',
          gap: 8,
          flexWrap: 'wrap',
          fontSize: 12,
        }}
      >
        <Link
          to="/invoices"
          style={{
            padding: '5px 12px',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            color: 'var(--v3-text)',
            textDecoration: 'none',
          }}
        >
          Past invoices →
        </Link>
        {/* Tier-coherence: "Billing profile (v2)" deep-link removed.
            v2 /billing now redirects here. GST + accounting surface kept
            below for India users. */}
        {showGst && (
          <Link
            to="/accounting"
            style={{
              padding: '5px 12px',
              border: '1px solid var(--v3-border)',
              borderRadius: 6,
              color: 'var(--v3-text)',
              textDecoration: 'none',
            }}
          >
            GST (India only) →
          </Link>
        )}
      </div>
      <p
        style={{
          margin: '8px 0 0',
          fontSize: 10.5,
          color: 'var(--v3-text-subtle)',
        }}
      >
        Tier: <strong style={{ color: 'var(--v3-text-muted)' }}>{ent.tierKey}</strong>{' '}
        · run limit:{' '}
        <strong style={{ color: 'var(--v3-text-muted)' }}>
          {ent.runLimitMonthly === -1 ? 'unlimited' : ent.runLimitMonthly}
        </strong>
      </p>
    </section>
  );
}
