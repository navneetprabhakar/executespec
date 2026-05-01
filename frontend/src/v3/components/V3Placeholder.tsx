import { Link } from 'react-router-dom';
import { ArrowRight, Construction } from 'lucide-react';

/**
 * R-201 — Wave-4 placeholder for routes whose v2 surface still works but
 * doesn't yet have a v3 equivalent shipped.
 *
 * Used for the 3 admin routes (`/admin/analytics`, `/admin/tiers`,
 * `/admin/pricing`) where the v2 page exists but ships outside the v3
 * cohabitation pattern. Per the handoff §8 cohabitation invariant
 * (`ProductSurfaceFilter` — surface-not-allowed ⇒ render this kind of
 * "evaluate-then-commit" panel, not 404), this component:
 *
 * 1. Tells the user the feature exists but hasn't moved to v3 yet
 * 2. Offers a one-click escape to the v2 fallback so admin work
 *    isn't blocked
 * 3. Notes the v3 route this will move to once that work lands (so
 *    a bookmarked admin link surfaces a roadmap, not a dead end)
 *
 * Distinct from {@link ../../../components/ui/LockedFeature.tsx} which
 * gates **paid-tier** features behind an upgrade prompt — that one
 * dims+overlays the child element. This one renders a full-page card
 * because admin features aren't tier-gated, they're cohabitation-gated.
 */
interface V3PlaceholderProps {
  /** What feature the user was trying to reach. e.g. "Tier lifecycle". */
  pageName: string;
  /** Where in v2 the same feature still works. Optional; omit to hide the link. */
  v2Fallback?: string;
  /** What v2 route they came from (for the "Open in v2" CTA label). */
  v2RouteLabel?: string;
  /** Where this surface will move to once v3 ships it. Optional. */
  futureV3Route?: string;
}

export default function V3Placeholder({
  pageName,
  v2Fallback,
  v2RouteLabel,
  futureV3Route,
}: V3PlaceholderProps) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        padding: '64px 24px',
        minHeight: '60vh',
      }}
    >
      <div
        style={{
          width: 56,
          height: 56,
          borderRadius: '50%',
          background: 'rgba(91,141,239,0.10)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Construction
          className="h-7 w-7"
          style={{ color: 'var(--v3-brand, #5B8DEF)' }}
        />
      </div>
      <div style={{ textAlign: 'center', maxWidth: 480 }}>
        <h1
          style={{
            fontSize: 22,
            fontWeight: 600,
            color: 'var(--v3-text, var(--text-primary))',
            margin: 0,
            lineHeight: 1.25,
          }}
        >
          {pageName} is coming to v3
        </h1>
        <p
          style={{
            margin: '10px 0 0',
            fontSize: 13,
            color: 'var(--v3-text-muted, var(--text-muted))',
            lineHeight: 1.55,
          }}
        >
          The v2 page still works — we haven't ported this surface yet.
          {futureV3Route ? (
            <>
              {' '}It will move to <code style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 12 }}>{futureV3Route}</code> in a future wave.
            </>
          ) : null}
        </p>
      </div>
      {v2Fallback && (
        <Link
          to={v2Fallback}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            padding: '8px 14px',
            background: 'var(--v3-brand, #5B8DEF)',
            color: 'var(--v3-text-on-brand)',
            fontSize: 13,
            fontWeight: 600,
            borderRadius: 6,
            textDecoration: 'none',
          }}
        >
          {/* Task 13 (2026-05-01 fix list) — drop user-facing "v2" label;
              the route prefix is an implementation detail. */}
          {v2RouteLabel ? `Open ${v2RouteLabel} →` : 'Open the full page →'}
          <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
        </Link>
      )}
    </div>
  );
}
