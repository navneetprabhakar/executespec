import { Link } from 'react-router-dom';
import { ShieldAlert, ArrowLeft } from 'lucide-react';

/**
 * R-311 (Wave 7) — explicit access-denied frame.
 *
 * **Why this exists.** F1 e2e walk found that an Enterprise user with
 * `DEVELOPER` role who clicks "Audit log" gets a question-mark page
 * because the API 403s on `/api/org/me/audit-logs`. F1 ranked this as
 * F-002 (MEDIUM) and F2 (EM Mike) elevated it to High because it's a
 * Team→Enterprise persona-blocker — Mike's procurement story crumbles
 * if the audit page reads as "broken" instead of "you need admin
 * access".
 *
 * **What this fix is.** A reusable component any tier-/role-gated
 * surface can render on 403. It explains *why* access is denied,
 * names the role required, and offers a clear escape hatch back to
 * the dashboard. Distinct from {@link ../ui/LockedFeature.tsx} —
 * that's tier-gated upgrade-prompts; this one is role-gated and the
 * fix is "ask your admin", not "buy a higher plan".
 *
 * Acceptance per docs/plans/wave-7-issue-list.md R-311:
 *   (a) Auditpage on 403 renders this component
 *   (b) Copy names the required role and the escape hatch
 *   (c) No more "?"-headed page on /audit for non-admin enterprise
 */
interface AccessDeniedProps {
  /** Human-readable name of the surface they tried to reach. */
  feature: string;
  /** The role they need (e.g. "ORG_ADMIN", "SUPER_ADMIN"). */
  requiredRole: string;
  /** Optional override of the contact line. */
  contactHint?: string;
  /** Where the back-link should point. Defaults to `/`. */
  backTo?: string;
  /** Label for the back link. Defaults to "Back to dashboard". */
  backLabel?: string;
}

const ROLE_LABELS: Record<string, string> = {
  ORG_ADMIN: 'org admin',
  SUPER_ADMIN: 'super admin',
  BILLING_ADMIN: 'billing admin',
};

export default function AccessDenied({
  feature,
  requiredRole,
  contactHint,
  backTo = '/',
  backLabel = 'Back to dashboard',
}: AccessDeniedProps) {
  const friendlyRole = ROLE_LABELS[requiredRole] ?? requiredRole.toLowerCase().replace(/_/g, ' ');
  const contactLine =
    contactHint ?? `Ask your org ${friendlyRole === 'super admin' ? 'owner' : friendlyRole} to grant you access, or switch to an account that has it.`;

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '60vh',
        padding: '48px 24px',
        textAlign: 'center',
      }}
      role="region"
      aria-label="Access denied"
    >
      <div
        style={{
          width: 56,
          height: 56,
          borderRadius: '50%',
          background: 'rgba(245,158,11,0.12)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: 16,
        }}
      >
        <ShieldAlert
          className="h-7 w-7"
          style={{ color: 'var(--status-warning, #F59E0B)' }}
          aria-hidden="true"
        />
      </div>
      <h1
        style={{
          fontSize: 22,
          fontWeight: 600,
          margin: 0,
          color: 'var(--text-primary, var(--v3-text))',
          lineHeight: 1.25,
        }}
      >
        {feature} requires {friendlyRole === 'super admin' ? '' : 'an '}{friendlyRole} access
      </h1>
      <p
        style={{
          marginTop: 10,
          maxWidth: 460,
          fontSize: 13.5,
          lineHeight: 1.55,
          color: 'var(--text-muted, var(--v3-text-muted))',
        }}
      >
        {contactLine}
      </p>
      <Link
        to={backTo}
        style={{
          marginTop: 22,
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          padding: '8px 14px',
          background: 'var(--v3-brand, #5B8DEF)',
          color: 'var(--v3-text-on-brand, #0B0D10)',
          fontSize: 13,
          fontWeight: 600,
          borderRadius: 6,
          textDecoration: 'none',
        }}
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        {backLabel}
      </Link>
    </div>
  );
}
