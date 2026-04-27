import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  PRICING_CARDS,
  ENTERPRISE_FOOTER,
  ANNUAL_DISCOUNT_PCT,
  annualPriceUsd,
  formatPrice,
  detectDefaultCurrency,
  type TierKey,
} from '../lib/pricing';
import Pill from './Pill';

/**
 * R-141 + R-150 — shared 3-tier pricing card grid.
 *
 * Used by /v3/billing (logged-in upgrade ladder) and /v3/pricing (public
 * marketing version). Differences are passed in as props — the cards
 * themselves render once.
 *
 * The ENTERPRISE row sits below the grid as a footer line per
 * greenfield §7.1: "no card on the grid; one line under it."
 */
interface PricingGridProps {
  /** When set, badges the matching tier "Current" + disables CTA. */
  currentTier?: TierKey | null;
  /** When provided, replaces the default CTA's onClick; receives the
   *  tier the user picked. The pricing grid itself doesn't fire any
   *  network calls. */
  onPickTier?: (tier: TierKey) => void;
  /** Allow override of the default geo-detected currency (Settings
   *  may force INR or USD). */
  initialCurrency?: 'USD' | 'INR';
  /** When false, hide the annual/monthly toggle (e.g. inside billing
   *  card we only show monthly). */
  showPeriodToggle?: boolean;
}

export default function PricingGrid({
  currentTier,
  onPickTier,
  initialCurrency,
  showPeriodToggle = true,
}: PricingGridProps) {
  const [currency, setCurrency] = useState<'USD' | 'INR'>(
    initialCurrency ?? detectDefaultCurrency()
  );
  const [annual, setAnnual] = useState(false);

  return (
    <div>
      {showPeriodToggle && (
        <div
          style={{
            display: 'flex',
            justifyContent: 'center',
            gap: 8,
            marginBottom: 16,
            flexWrap: 'wrap',
          }}
        >
          <ToggleButton
            options={[
              { value: 'USD', label: 'USD' },
              { value: 'INR', label: 'INR' },
            ]}
            value={currency}
            onChange={(v) => setCurrency(v as 'USD' | 'INR')}
          />
          <ToggleButton
            options={[
              { value: 'monthly', label: 'Monthly' },
              { value: 'annual', label: `Annual · save ${ANNUAL_DISCOUNT_PCT}%` },
            ]}
            value={annual ? 'annual' : 'monthly'}
            onChange={(v) => setAnnual(v === 'annual')}
          />
        </div>
      )}

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
          gap: 12,
          alignItems: 'stretch',
        }}
      >
        {PRICING_CARDS.map((card) => (
          <Card
            key={card.tier}
            card={card}
            currency={currency}
            annual={annual}
            isCurrent={currentTier === card.tier}
            onPick={() => onPickTier?.(card.tier)}
          />
        ))}
      </div>

      {/* Enterprise footer line */}
      <p
        style={{
          marginTop: 18,
          textAlign: 'center',
          color: 'var(--v3-text-muted)',
          fontSize: 12,
        }}
      >
        {ENTERPRISE_FOOTER.headline}{' '}
        <Link
          to={ENTERPRISE_FOOTER.ctaHref}
          style={{ color: 'var(--v3-brand)', textDecoration: 'none' }}
        >
          {ENTERPRISE_FOOTER.ctaLabel}
        </Link>
      </p>
    </div>
  );
}

// ─── Card ────────────────────────────────────────────────────────────────────

function Card({
  card,
  currency,
  annual,
  isCurrent,
  onPick,
}: {
  card: (typeof PRICING_CARDS)[number];
  currency: 'USD' | 'INR';
  annual: boolean;
  isCurrent: boolean;
  onPick: () => void;
}) {
  const baseAmount = currency === 'USD' ? card.priceUsd : card.priceInr;
  const displayAmount =
    annual && baseAmount && card.tier !== 'FREE'
      ? annualPriceUsd(baseAmount)
      : baseAmount;
  const price = formatPrice(displayAmount, currency);
  const unit = annual && card.tier !== 'FREE' ? '/ year' : card.priceUnit;

  return (
    <div
      style={{
        position: 'relative',
        padding: 16,
        background: 'var(--v3-surface)',
        border:
          card.badge === 'Most popular'
            ? '1px solid var(--v3-brand)'
            : '1px solid var(--v3-border)',
        borderRadius: 10,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {card.badge && (
        <div
          style={{
            position: 'absolute',
            top: -10,
            left: '50%',
            transform: 'translateX(-50%)',
            padding: '2px 8px',
            background: 'var(--v3-brand)',
            color: '#0B0D10',
            fontSize: 10,
            fontWeight: 700,
            letterSpacing: 0.4,
            borderRadius: 4,
            textTransform: 'uppercase',
          }}
        >
          {card.badge}
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <h3
          style={{
            margin: 0,
            fontSize: 15,
            fontWeight: 600,
            color: 'var(--v3-text)',
          }}
        >
          {card.name}
        </h3>
        {isCurrent && (
          <Pill tone="ok" size="sm">
            Current
          </Pill>
        )}
      </div>
      <p
        style={{
          margin: '4px 0 12px',
          fontSize: 11.5,
          color: 'var(--v3-text-muted)',
          minHeight: 28,
        }}
      >
        {card.subhead}
      </p>

      <div style={{ marginBottom: 12 }}>
        <span
          style={{
            fontSize: 26,
            fontWeight: 600,
            color: 'var(--v3-text)',
          }}
        >
          {price}
        </span>
        {unit && (
          <span style={{ marginLeft: 4, fontSize: 12, color: 'var(--v3-text-muted)' }}>
            {unit}
          </span>
        )}
      </div>

      <ul
        style={{
          margin: 0,
          padding: '0 0 12px 0',
          listStyle: 'none',
          flex: 1,
        }}
      >
        {card.bullets.map((b, i) => (
          <li
            key={i}
            style={{
              padding: '3px 0',
              color: 'var(--v3-text-muted)',
              fontSize: 11.5,
              display: 'flex',
              gap: 6,
              alignItems: 'baseline',
            }}
          >
            <span style={{ color: 'var(--v3-success)', flexShrink: 0 }}>✓</span>
            <span>{b}</span>
          </li>
        ))}
      </ul>

      <button
        type="button"
        onClick={onPick}
        disabled={isCurrent}
        style={{
          padding: '8px 12px',
          fontSize: 12.5,
          fontWeight: 600,
          background: isCurrent
            ? 'var(--v3-surface-elevated)'
            : card.badge === 'Most popular'
            ? 'var(--v3-brand)'
            : 'transparent',
          color: isCurrent
            ? 'var(--v3-text-subtle)'
            : card.badge === 'Most popular'
            ? '#0B0D10'
            : 'var(--v3-text)',
          border: card.badge === 'Most popular'
            ? 'none'
            : '1px solid var(--v3-border-strong)',
          borderRadius: 6,
          cursor: isCurrent ? 'not-allowed' : 'pointer',
          fontFamily: 'inherit',
        }}
      >
        {isCurrent ? 'Current plan' : card.ctaLabel}
      </button>
    </div>
  );
}

// ─── Toggle ─────────────────────────────────────────────────────────────────

function ToggleButton({
  options,
  value,
  onChange,
}: {
  options: { value: string; label: string }[];
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <div
      role="group"
      style={{
        display: 'inline-flex',
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 6,
        padding: 2,
      }}
    >
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          style={{
            padding: '4px 12px',
            fontSize: 11.5,
            fontWeight: 600,
            background: value === o.value ? 'var(--v3-surface-elevated)' : 'transparent',
            color: value === o.value ? 'var(--v3-text)' : 'var(--v3-text-muted)',
            border: 'none',
            borderRadius: 4,
            cursor: 'pointer',
            fontFamily: 'inherit',
          }}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
