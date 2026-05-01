import {useMemo, useState} from 'react';
import {Link} from 'react-router-dom';
import {Calculator} from 'lucide-react';
import {PRICING_CARDS} from '../lib/pricing';

/**
 * R-317 (Wave 7) — TCO mini-calculator on /pricing.
 *
 * **Why this exists.** F2 (VP Eng Dana, direct-Enterprise persona)
 * named "no on-page TCO calculator" as her #1 leak — she compares
 * "200 devs × $X/seat × 12 mo" against Devin's flat fee on the
 * pricing page. Without a tool to do that math inline, she bounces.
 * F2 P3-implied + week-4 watch metric.
 *
 * **What this fix is.** A pure-client, two-slider estimator that
 * sits between the Team and Enterprise cards on /pricing:
 *   - Engineers slider (5 → 500, default 25)
 *   - Months slider (6 → 36, default 12)
 *   - Output: monthly + annual TCO at the live Team-tier rate
 *     (read from PRICING_CARDS so a price change in lib/pricing.ts
 *     updates this calculator in lockstep)
 *   - "Compare with Enterprise" CTA → /trust (procurement docs)
 *
 * No API call, no state persisted. Component is render-cheap and
 * doesn't fire any side-effects. The 5-engineer floor matches the
 * Team min-2 + room-to-grow framing without confusing solo evaluators.
 */
export default function TcoEstimator() {
  const [engineers, setEngineers] = useState(25);
  const [months, setMonths] = useState(12);

  // Read TEAM-tier price from the same source-of-truth lib/pricing.ts
  // uses for the cards above. This guarantees the estimator can never
  // disagree with the card it's anchored to.
  const teamCard = PRICING_CARDS.find((c) => c.tier === 'TEAM');
  const teamPriceUsd = teamCard?.priceUsd ?? 39;

  const {monthlyUsd, totalUsd, perDevAnnualUsd} = useMemo(() => {
    const monthly = engineers * teamPriceUsd;
    const total = monthly * months;
    const perDevAnnual = teamPriceUsd * 12;
    return {monthlyUsd: monthly, totalUsd: total, perDevAnnualUsd: perDevAnnual};
  }, [engineers, months, teamPriceUsd]);

  const fmt = (n: number) =>
    n >= 1000 ? `$${n.toLocaleString(undefined, {maximumFractionDigits: 0})}` : `$${n}`;

  return (
    <section
      aria-label='TCO estimator'
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 18,
        marginTop: 4,
      }}
    >
      <header style={{display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12}}>
        <Calculator className='h-4 w-4' style={{color: 'var(--v3-brand)'}} aria-hidden='true' />
        <h3 style={{margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--v3-text)'}}>
          What would Team cost for your org?
        </h3>
      </header>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
          gap: 16,
          alignItems: 'flex-end',
        }}
      >
        <SliderField
          label='Engineers'
          value={engineers}
          min={5}
          max={500}
          step={5}
          onChange={setEngineers}
          format={(v) => `${v.toLocaleString()}`}
        />
        <SliderField
          label='Months'
          value={months}
          min={6}
          max={36}
          step={1}
          onChange={setMonths}
          format={(v) => `${v}`}
        />
      </div>

      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          gap: 12,
          marginTop: 16,
          paddingTop: 12,
          borderTop: '1px solid var(--v3-border)',
        }}
      >
        <div>
          <div style={{fontSize: 22, fontWeight: 600, color: 'var(--v3-text)'}}>
            {fmt(totalUsd)}
            <span
              style={{fontSize: 12, fontWeight: 400, color: 'var(--v3-text-muted)', marginLeft: 6}}
            >
              total · {months} mo
            </span>
          </div>
          <div style={{fontSize: 11.5, color: 'var(--v3-text-subtle)', marginTop: 2}}>
            {fmt(monthlyUsd)} / month · {fmt(perDevAnnualUsd)} per engineer / year
          </div>
        </div>
        <Link
          to='/trust'
          style={{
            padding: '8px 14px',
            background: 'transparent',
            color: 'var(--v3-text)',
            border: '1px solid var(--v3-border-strong)',
            borderRadius: 6,
            fontSize: 12.5,
            fontWeight: 600,
            textDecoration: 'none',
            whiteSpace: 'nowrap',
          }}
        >
          Compare with Enterprise →
        </Link>
      </div>

      <p
        style={{
          margin: '12px 0 0',
          fontSize: 11,
          color: 'var(--v3-text-subtle)',
          lineHeight: 1.5,
        }}
      >
        Team is BYOK — your Anthropic / OpenAI / Google keys, zero per-run markup. Add LLM spend
        (typically $0.05–$0.30 per LOW spec at provider list price) for a fully-loaded number.
      </p>
    </section>
  );
}

function SliderField({
  label,
  value,
  min,
  max,
  step,
  onChange,
  format,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  onChange: (v: number) => void;
  format: (v: number) => string;
}) {
  return (
    <label style={{display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12}}>
      <span style={{display: 'flex', justifyContent: 'space-between', alignItems: 'baseline'}}>
        <span style={{color: 'var(--v3-text-muted)', fontWeight: 500}}>{label}</span>
        <span style={{color: 'var(--v3-text)', fontWeight: 600, fontSize: 13}}>
          {format(value)}
        </span>
      </span>
      <input
        type='range'
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-label={label}
        style={{
          width: '100%',
          accentColor: 'var(--v3-brand)',
          cursor: 'pointer',
        }}
      />
      <span
        style={{
          fontSize: 10,
          color: 'var(--v3-text-subtle)',
          display: 'flex',
          justifyContent: 'space-between',
        }}
      >
        <span>{format(min)}</span>
        <span>{format(max)}</span>
      </span>
    </label>
  );
}
