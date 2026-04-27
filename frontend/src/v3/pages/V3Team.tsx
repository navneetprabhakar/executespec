import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  getMyOrg,
  getOrgMembers,
  inviteMember,
  type OrgDto,
  type OrgMemberDto,
} from '@/lib/api';
import { useAuthStore } from '@/stores/useAuthStore';
import Pill from '../components/Pill';

/**
 * R-143 — /v3/team page.
 *
 * Greenfield §4.10: members list + seat picker + invite-by-email +
 * SSO panel for Enterprise. Reuses every existing v2 endpoint
 * unchanged — getMyOrg, getOrgMembers, inviteMember.
 *
 * Free users see an upgrade-to-Pro CTA instead of a member table.
 *
 * SSO panel only renders when org tier is ENTERPRISE. The schema
 * (org.sso_provider/sso_enforced/sso_metadata_url/sso_domain) shipped
 * in V127 a wave ago; the configuration form lands as a Wave 3
 * follow-up that needs Okta/Auth0 round-trip QA. Today we surface
 * the placeholder + link to v2's existing /admin path.
 */
export default function V3Team() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const [org, setOrg] = useState<OrgDto | null>(null);
  const [members, setMembers] = useState<OrgMemberDto[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      getMyOrg().catch(() => null),
      getOrgMembers().catch(() => [] as OrgMemberDto[]),
    ]).then(([o, m]) => {
      if (cancelled) return;
      setOrg(o);
      setMembers(m);
      setLoading(false);
    }).catch((e: Error) => {
      if (cancelled) return;
      setError(e?.message ?? 'Failed to load team');
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const tier = (org?.tier ?? user?.tier ?? 'FREE').toUpperCase();
  const isFreeTier = tier === 'FREE';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, paddingBottom: 32 }}>
      <div>
        <h1 style={{ fontSize: 22, fontWeight: 600, color: 'var(--v3-text)', margin: 0 }}>
          Team
        </h1>
        <p style={{ fontSize: 12, color: 'var(--v3-text-muted)', margin: '4px 0 0' }}>
          Members · seats · invitations · SSO
        </p>
      </div>

      {loading && (
        <div className="v3-placeholder">
          <p>Loading team…</p>
        </div>
      )}

      {error && (
        <div
          role="alert"
          style={{
            padding: 12,
            background: 'rgba(239,68,68,0.12)',
            border: '1px solid rgba(239,68,68,0.36)',
            color: '#FCA5A5',
            borderRadius: 8,
            fontSize: 12,
          }}
        >
          {error}
        </div>
      )}

      {/* ── Free tier — dedicated upsell card ─────────────────────────── */}
      {!loading && isFreeTier && (
        <FreeTierUpsellCard onUpgrade={() => navigate('/v3/billing')} />
      )}

      {/* ── Pro+ — full team surface ──────────────────────────────────── */}
      {!loading && !isFreeTier && (
        <>
          <SeatsCard org={org} memberCount={members?.length ?? 0} />
          <MembersCard members={members ?? []} />
          <InviteCard />
          {tier === 'ENTERPRISE' && <SsoPanel />}
        </>
      )}
    </div>
  );
}

// ─── Free-tier card ─────────────────────────────────────────────────────────

function FreeTierUpsellCard({ onUpgrade }: { onUpgrade: () => void }) {
  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <Pill tone="info">Pro+ feature</Pill>
        <h2 style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>
          Upgrade to bring teammates in.
        </h2>
      </div>
      <p style={{ margin: 0, fontSize: 12.5, color: 'var(--v3-text-muted)', lineHeight: 1.55 }}>
        Free is a one-developer plan. Pro adds unlimited runs and Team adds
        per-seat collaboration with a shared run pool, audit log, and SSO.
      </p>
      <div style={{ display: 'flex', gap: 6, marginTop: 12, flexWrap: 'wrap' }}>
        <button
          type="button"
          onClick={onUpgrade}
          style={{
            padding: '6px 14px',
            fontSize: 12.5,
            fontWeight: 600,
            background: 'var(--v3-brand)',
            color: '#0B0D10',
            border: 'none',
            borderRadius: 6,
            cursor: 'pointer',
            fontFamily: 'inherit',
          }}
        >
          See plans →
        </button>
      </div>
    </section>
  );
}

// ─── Seats card ─────────────────────────────────────────────────────────────

function SeatsCard({ org, memberCount }: { org: OrgDto | null; memberCount: number }) {
  const seatCount = org?.seatCount ?? 0;
  const isUnlimited = seatCount < 0;
  const used = Math.min(memberCount, seatCount > 0 ? seatCount : memberCount);
  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>
            {org?.name ?? 'Your team'}
          </h2>
          <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--v3-text-muted)' }}>
            {isUnlimited
              ? `${memberCount} member${memberCount === 1 ? '' : 's'}`
              : `${used} of ${seatCount} seat${seatCount === 1 ? '' : 's'} used`}
          </p>
        </div>
        <Link
          to="/v3/billing"
          style={{
            padding: '5px 12px',
            fontSize: 12,
            fontWeight: 500,
            background: 'transparent',
            color: 'var(--v3-text)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            textDecoration: 'none',
            fontFamily: 'inherit',
          }}
        >
          Manage seats →
        </Link>
      </div>
    </section>
  );
}

// ─── Members card ───────────────────────────────────────────────────────────

function MembersCard({ members }: { members: OrgMemberDto[] }) {
  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <h2 style={{ margin: '0 0 10px', fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>
        Members
      </h2>
      {members.length === 0 ? (
        <p style={{ margin: 0, fontSize: 12, color: 'var(--v3-text-subtle)' }}>
          No teammates yet — invite someone below.
        </p>
      ) : (
        <ul style={{ margin: 0, padding: 0, listStyle: 'none' }}>
          {members.map((m) => (
            <li
              key={m.userId}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                padding: '6px 0',
                borderBottom: '1px dashed var(--v3-border)',
                fontSize: 12,
              }}
            >
              <div>
                <span style={{ color: 'var(--v3-text)', fontWeight: 500 }}>
                  {m.firstName} {m.lastName}
                </span>{' '}
                <span style={{ color: 'var(--v3-text-subtle)', fontSize: 11 }}>
                  {m.email}
                </span>
              </div>
              <Pill tone={m.orgRole === 'ORG_ADMIN' ? 'brand' : 'neutral'} size="sm">
                {m.orgRole.toLowerCase()}
              </Pill>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ─── Invite card ────────────────────────────────────────────────────────────

function InviteCard() {
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!email.trim()) return;
    setSubmitting(true);
    setErr(null);
    try {
      await inviteMember({ email: email.trim(), orgRole: 'DEVELOPER' });
      setSent(true);
      setEmail('');
    } catch (e) {
      setErr((e as Error)?.message ?? 'Invite failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <h2 style={{ margin: '0 0 10px', fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>
        Invite a teammate
      </h2>
      <form onSubmit={onSubmit} style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="teammate@example.com"
          disabled={submitting}
          style={{
            flex: 1,
            minWidth: 200,
            padding: '7px 10px',
            background: 'var(--v3-bg)',
            color: 'var(--v3-text)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            fontSize: 12.5,
            outline: 'none',
            fontFamily: 'inherit',
          }}
        />
        <button
          type="submit"
          disabled={!email.trim() || submitting}
          style={{
            padding: '7px 14px',
            fontSize: 12.5,
            fontWeight: 600,
            background: email.trim() && !submitting ? 'var(--v3-brand)' : 'var(--v3-surface-elevated)',
            color: email.trim() && !submitting ? '#0B0D10' : 'var(--v3-text-subtle)',
            border: 'none',
            borderRadius: 6,
            cursor: email.trim() && !submitting ? 'pointer' : 'not-allowed',
            fontFamily: 'inherit',
          }}
        >
          {submitting ? 'Sending…' : 'Send invite'}
        </button>
      </form>
      {sent && (
        <p style={{ margin: '8px 0 0', fontSize: 11.5, color: 'var(--v3-success)' }}>
          ✓ Invite sent. They'll get an email to join.
        </p>
      )}
      {err && (
        <p style={{ margin: '8px 0 0', fontSize: 11.5, color: 'var(--v3-error)' }}>
          {err}
        </p>
      )}
    </section>
  );
}

// ─── SSO panel (Enterprise only) ───────────────────────────────────────────

function SsoPanel() {
  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border-strong)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <Pill tone="brand">Enterprise</Pill>
        <h2 style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>
          Single sign-on
        </h2>
      </div>
      <p style={{ margin: 0, fontSize: 12, color: 'var(--v3-text-muted)', lineHeight: 1.55 }}>
        Configure SAML / OIDC for Okta, Auth0, Azure AD, Google Workspace,
        or any standards-compliant IdP. Schema is in place; the
        configuration screen lands as a Wave 3 follow-up that needs
        round-trip QA against a live IdP.
      </p>
      <div style={{ marginTop: 10 }}>
        <Link
          to="/admin"
          style={{
            padding: '5px 12px',
            fontSize: 12,
            background: 'transparent',
            color: 'var(--v3-brand)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            textDecoration: 'none',
          }}
        >
          Open admin (v2) →
        </Link>
      </div>
    </section>
  );
}
