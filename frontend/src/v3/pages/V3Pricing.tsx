import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import PricingGrid from '../components/PricingGrid';
import TcoEstimator from '../components/TcoEstimator';
import { trackV3, V3_EVENTS } from '../lib/events';
import type { TierKey } from '../lib/pricing';

/**
 * R-150 — public pricing page.
 *
 * Greenfield §4.14: hero · 3-card grid · Enterprise footer line · FAQ.
 * Same pricing source as /v3/billing (lib/pricing.ts) so the in-app
 * upsell and the public marketing prices stay in lockstep.
 *
 * Conversion event: v3_pricing_cta_clicked. Each card's CTA fires it
 * with the tier as a property so the funnel can A/B Free vs Pro vs
 * Team conversion separately.
 */
export default function V3Pricing() {
  const navigate = useNavigate();

  function onPickTier(tier: TierKey) {
    trackV3(V3_EVENTS.PRICING_CTA_CLICKED, { tier });
    if (tier === 'FREE') {
      navigate('/v3/landing');
      return;
    }
    if (tier === 'ENTERPRISE') {
      navigate('/v3/trust');
      return;
    }
    // Pro / Team: route to billing (auto-redirects to v2 /plans
    // checkout when authed, /v3/auth when not)
    trackV3(V3_EVENTS.TIER_UPGRADED, { fromTier: 'public', toTier: tier });
    navigate('/v3/billing');
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24, paddingBottom: 48 }}>
      {/* ── Hero ───────────────────────────────────────────────────────── */}
      <section
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: 12,
          padding: '32px 0 16px',
          textAlign: 'center',
        }}
      >
        <h1
          style={{
            fontSize: 28,
            fontWeight: 600,
            color: 'var(--v3-text)',
            margin: 0,
            lineHeight: 1.2,
          }}
        >
          Simple, transparent pricing.
        </h1>
        <p
          style={{
            margin: 0,
            fontSize: 14,
            color: 'var(--v3-text-muted)',
            maxWidth: 540,
            lineHeight: 1.55,
          }}
        >
          Your runs are real PRs — pay for what works. Cancel any time.
          Free tier is forever.
        </p>
      </section>

      {/* ── 3-card grid ────────────────────────────────────────────────── */}
      <PricingGrid onPickTier={onPickTier} />

      {/* ── R-317 (Wave 7) — TCO mini-calculator (F2 P3 — Dana) ──────── */}
      <TcoEstimator />

      {/* ── FAQ ────────────────────────────────────────────────────────── */}
      <section
        style={{
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border)',
          borderRadius: 10,
          padding: 16,
        }}
      >
        <h2 style={{ margin: 0, fontSize: 16, fontWeight: 600, color: 'var(--v3-text)' }}>
          Frequently asked questions
        </h2>
        <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 4 }}>
          {FAQ.map((q, i) => (
            <FaqItem key={i} q={q.q} a={q.a} />
          ))}
        </div>
      </section>

      {/* ── Footer ─────────────────────────────────────────────────────── */}
      <footer
        style={{
          textAlign: 'center',
          padding: '16px 0',
          fontSize: 11,
          color: 'var(--v3-text-subtle)',
        }}
      >
        Already have an account?{' '}
        <Link to="/login" style={{ color: 'var(--v3-brand)', textDecoration: 'none' }}>
          Sign in
        </Link>{' '}
        ·{' '}
        <Link to="/v3/landing" style={{ color: 'var(--v3-text-subtle)', textDecoration: 'none' }}>
          Try without signing up
        </Link>{' '}
        ·{' '}
        <Link to="/v3/trust" style={{ color: 'var(--v3-text-subtle)', textDecoration: 'none' }}>
          Trust & security
        </Link>
      </footer>
    </div>
  );
}

// ─── FAQ ────────────────────────────────────────────────────────────────────

const FAQ: { q: string; a: string }[] = [
  {
    q: 'What counts as a "run"?',
    a: 'One submit-to-result cycle. ExecuteSpec reads your spec, plans the change, writes the code, runs the build and tests, and hands you a draft pull request. The whole loop counts as one run, regardless of how many files were touched.',
  },
  {
    q: 'Can I bring my own API keys?',
    a: 'Yes — Pro and Team plans support BYOK (Bring Your Own Keys) for Anthropic, OpenAI, and Google. You provide the API key; we route through your quota and bill you for the seats only. Saves money at scale.',
  },
  {
    q: 'What happens to my code?',
    a: 'Workspaces are destroyed 24 hours after last access. We never train models on your code. Repository content is sent to the LLM provider you choose; we keep only the artifacts your run produced. Full data-flow on /v3/trust.',
  },
  {
    q: 'How does the free-tier referral work?',
    a: 'You start with 5 lifetime runs. Invite a friend who signs up and ships their first run — you both get +5 runs each. Cap at 25 lifetime runs (5 base + 4 invites × 5).',
  },
  {
    q: 'What\'s the cancellation policy?',
    a: 'Cancel any time. You keep your current period\'s access; no proration. Your projects, runs, and PRs remain in your account; you can re-subscribe or stay on the free tier.',
  },
  {
    q: 'Do you offer SOC 2 / DPA / a custom MSA?',
    a: 'Enterprise customers get SOC 2 Type II evidence, a signed DPA, custom MSA, dedicated infrastructure region, and SAML SSO. Talk to sales.',
  },
];

function FaqItem({ q, a }: { q: string; a: string }) {
  const [open, setOpen] = useState(false);
  return (
    <details
      open={open}
      onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open)}
      style={{
        borderBottom: '1px solid var(--v3-border)',
        padding: '8px 0',
      }}
    >
      <summary
        style={{
          listStyle: 'none',
          cursor: 'pointer',
          fontSize: 13,
          fontWeight: 500,
          color: 'var(--v3-text)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <span>{q}</span>
        <span style={{ color: 'var(--v3-text-subtle)', fontSize: 11 }}>
          {open ? '▴' : '▾'}
        </span>
      </summary>
      <p
        style={{
          marginTop: 6,
          marginBottom: 4,
          fontSize: 12,
          color: 'var(--v3-text-muted)',
          lineHeight: 1.6,
        }}
      >
        {a}
      </p>
    </details>
  );
}
