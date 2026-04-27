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
  // Tier-coherence: PRO is single-developer (no seats), so the team
  // surface must show a Pro→Team upsell — not the Free→Pro one. TEAM
  // and ENTERPRISE are BYOK (unlimited via own keys) and that's the
  // value-prop the Pro user needs to see, not "unlimited managed runs"
  // (which Pro already has).
  const isProTier = tier === 'PRO';
  const isAnonymous = !user;

  // Anonymous visitors get the conversion-optimized preview surface — they
  // can see what Team / Enterprise actually buys (seats, members, audit, SSO)
  // BEFORE having to commit to signup. Procurement-evaluator personas (PgMs
  // looping in their eng manager) cannot move forward without this.
  if (isAnonymous) {
    return <AnonymousTeamPreview />;
  }

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
        <FreeTierUpsellCard onUpgrade={() => navigate('/v3/pricing')} />
      )}

      {/* ── Pro tier — different upsell (BYOK + collaboration framing) ── */}
      {!loading && isProTier && (
        <ProTierUpsellCard onUpgrade={() => navigate('/v3/pricing')} />
      )}

      {/* ── Team / Enterprise — full team surface ──────────────────────── */}
      {!loading && !isFreeTier && !isProTier && (
        <>
          <SeatsCard org={org} tier={tier} memberCount={members?.length ?? 0} />
          <MembersCard members={members ?? []} />
          <InviteCard />
          {tier === 'ENTERPRISE' && <SsoPanel />}
        </>
      )}
    </div>
  );
}

// ─── Anonymous preview ──────────────────────────────────────────────────────
// Conversion-fix #A1 — the procurement-evaluator persona (Priya in the
// assessment) needs to see what Team/Enterprise buys BEFORE signup. The old
// page rendered a single "Pro+ feature · Upgrade" card that was a wall;
// this surface shows the four binding decisions her engineering manager
// will ask about: seats, members + RBAC, audit log, SSO config.

function AnonymousTeamPreview() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, paddingBottom: 32 }}>
      <div>
        <h1 style={{ fontSize: 22, fontWeight: 600, color: 'var(--v3-text)', margin: 0 }}>
          Team
        </h1>
        <p style={{ fontSize: 12.5, color: 'var(--v3-text-muted)', margin: '6px 0 0', lineHeight: 1.55 }}>
          What the Team and Enterprise tiers buy you. Nothing here is gated —
          look first, sign up later.
        </p>
      </div>

      {/* Top hero / split CTA */}
      <section
        style={{
          background: 'linear-gradient(135deg, rgba(91,141,239,0.08), rgba(91,141,239,0.02))',
          border: '1px solid rgba(91,141,239,0.32)',
          borderRadius: 10,
          padding: 18,
        }}
      >
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
            gap: 14,
            alignItems: 'center',
          }}
        >
          <div>
            <h2 style={{ margin: 0, fontSize: 17, fontWeight: 600, color: 'var(--v3-text)' }}>
              Bring your engineering team in.
            </h2>
            <p style={{ margin: '6px 0 0', fontSize: 12.5, color: 'var(--v3-text-muted)', lineHeight: 1.55 }}>
              Per-seat collaboration, shared run pool, audit log, RBAC, SSO.
              Free 14-day trial — no card. Procurement docs at <Link to="/v3/trust" style={{ color: 'var(--v3-brand)', textDecoration: 'none' }}>/v3/trust</Link>.
            </p>
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
            <a
              href="mailto:enterprise@executespec.ai?subject=Demo%20request%20—%20Team%20tier"
              style={{
                padding: '8px 14px',
                fontSize: 12.5,
                fontWeight: 600,
                background: 'var(--v3-brand)',
                color: '#0B0D10',
                border: 'none',
                borderRadius: 6,
                textDecoration: 'none',
                fontFamily: 'inherit',
              }}
            >
              Request a demo →
            </a>
            <Link
              to="/v3/auth"
              style={{
                padding: '8px 14px',
                fontSize: 12.5,
                fontWeight: 600,
                background: 'transparent',
                color: 'var(--v3-text)',
                border: '1px solid var(--v3-border-strong)',
                borderRadius: 6,
                textDecoration: 'none',
                fontFamily: 'inherit',
              }}
            >
              Start free trial →
            </Link>
          </div>
        </div>
      </section>

      <PreviewSeatsCard />
      <PreviewMembersCard />
      <PreviewAuditCard />
      <PreviewSsoCard />

      {/* Bottom procurement-doc strip */}
      <section
        style={{
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border)',
          borderRadius: 10,
          padding: 14,
          fontSize: 12,
          color: 'var(--v3-text-muted)',
        }}
      >
        Procurement teams: SOC 2 (audit in progress), SAML/OIDC, EU sub-processor list,
        DPA, audit-log retention 5 years — all at <Link to="/v3/trust" style={{ color: 'var(--v3-brand)', textDecoration: 'none' }}>/v3/trust</Link>.
        Pricing at <Link to="/v3/pricing" style={{ color: 'var(--v3-brand)', textDecoration: 'none' }}>/v3/pricing</Link>.
      </section>
    </div>
  );
}

function PreviewCard({
  badge,
  title,
  blurb,
  children,
}: {
  badge: string;
  title: string;
  blurb: string;
  children: React.ReactNode;
}) {
  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <Pill tone="brand" size="sm">{badge}</Pill>
        <h2 style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>{title}</h2>
      </div>
      <p style={{ margin: '0 0 12px', fontSize: 12, color: 'var(--v3-text-muted)', lineHeight: 1.55 }}>
        {blurb}
      </p>
      <div
        style={{
          background: 'var(--v3-bg)',
          border: '1px dashed var(--v3-border)',
          borderRadius: 8,
          padding: 12,
        }}
      >
        {children}
      </div>
    </section>
  );
}

function PreviewSeatsCard() {
  return (
    <PreviewCard
      badge="Team"
      title="Seats and shared run pool"
      blurb="Self-serve seat picker with month-over-month flex. Runs are pooled across the team — one heavy refactor doesn't burn through everyone's budget."
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <div style={{ fontSize: 22, fontWeight: 600, color: 'var(--v3-text)' }}>12 seats</div>
          <div style={{ fontSize: 11.5, color: 'var(--v3-text-subtle)' }}>$29 BYOK / mo · unlimited runs · pooled</div>
        </div>
        <div style={{ display: 'flex', gap: 6 }}>
          <Pill tone="neutral" size="sm">2 · 5 · 10 · 12 · 25 · 50 · 100</Pill>
        </div>
      </div>
    </PreviewCard>
  );
}

function PreviewMembersCard() {
  const sample = [
    { name: 'You', email: 'you@yourco.com', role: 'ORG_ADMIN' },
    { name: 'Marcus T.', email: 'marcus@yourco.com', role: 'DEVELOPER' },
    { name: 'Priya S.', email: 'priya@yourco.com', role: 'PROJECT_ADMIN' },
    { name: 'Finance', email: 'finance@yourco.com', role: 'FINANCE' },
  ];
  return (
    <PreviewCard
      badge="RBAC"
      title="Members and role-based access"
      blurb="Five roles out of the box. Project-scoped access. Finance role sees invoices but not runs. SUPER_ADMIN reserved for emergency override."
    >
      <ul style={{ margin: 0, padding: 0, listStyle: 'none' }}>
        {sample.map((m, i) => (
          <li
            key={m.email}
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              padding: '6px 0',
              borderBottom: i < sample.length - 1 ? '1px dashed var(--v3-border)' : 'none',
              fontSize: 12,
            }}
          >
            <div>
              <span style={{ color: 'var(--v3-text)', fontWeight: 500 }}>{m.name}</span>{' '}
              <span style={{ color: 'var(--v3-text-subtle)', fontSize: 11 }}>{m.email}</span>
            </div>
            <Pill tone={m.role === 'ORG_ADMIN' ? 'brand' : 'neutral'} size="sm">
              {m.role.toLowerCase()}
            </Pill>
          </li>
        ))}
      </ul>
    </PreviewCard>
  );
}

function PreviewAuditCard() {
  const sample = [
    { actor: 'Marcus T.', event: 'submitted run · checkout-flow refactor', when: '2m ago' },
    { actor: 'Priya S.', event: 'approved plan · OAuth migration', when: '14m ago' },
    { actor: 'Marcus T.', event: 'merged PR · #2143 to main', when: '1h ago' },
    { actor: 'Finance', event: 'exported invoice · INV-2026-04', when: 'yesterday' },
  ];
  return (
    <PreviewCard
      badge="Compliance"
      title="Audit log — 5-year retention"
      blurb="Every billing entity, plan approval, run, merge, export. Retained five years. Filter by actor, project, or event type. CSV export for compliance."
    >
      <ul style={{ margin: 0, padding: 0, listStyle: 'none' }}>
        {sample.map((row, i) => (
          <li
            key={i}
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              padding: '5px 0',
              borderBottom: i < sample.length - 1 ? '1px dashed var(--v3-border)' : 'none',
              fontSize: 11.5,
            }}
          >
            <div>
              <span style={{ color: 'var(--v3-text)', fontWeight: 500 }}>{row.actor}</span>{' '}
              <span style={{ color: 'var(--v3-text-muted)' }}>{row.event}</span>
            </div>
            <span style={{ color: 'var(--v3-text-subtle)', fontSize: 11 }}>{row.when}</span>
          </li>
        ))}
      </ul>
    </PreviewCard>
  );
}

function PreviewSsoCard() {
  return (
    <PreviewCard
      badge="Enterprise"
      title="Single sign-on (SAML / OIDC)"
      blurb="Okta, Auth0, Azure AD, Google Workspace, or any standards-compliant IdP. Domain-pinned email enforcement. MFA inherited from your IdP."
    >
      <div style={{ fontSize: 11.5, color: 'var(--v3-text-muted)', display: 'grid', gap: 4 }}>
        <div><span style={{ color: 'var(--v3-text-subtle)' }}>Provider:</span> <span style={{ color: 'var(--v3-text)' }}>Okta</span></div>
        <div><span style={{ color: 'var(--v3-text-subtle)' }}>Domain pinned:</span> <span style={{ color: 'var(--v3-text)' }}>yourco.com</span></div>
        <div><span style={{ color: 'var(--v3-text-subtle)' }}>Enforcement:</span> <span style={{ color: 'var(--v3-text)' }}>Required for new sign-ins</span></div>
        <div><span style={{ color: 'var(--v3-text-subtle)' }}>Status:</span> <Pill tone="ok" size="sm">Active</Pill></div>
      </div>
    </PreviewCard>
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
        <Pill tone="info">Paid tiers</Pill>
        <h2 style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>
          Upgrade to bring teammates in.
        </h2>
      </div>
      <p style={{ margin: 0, fontSize: 12.5, color: 'var(--v3-text-muted)', lineHeight: 1.55 }}>
        Free is a one-developer plan. Pro ($20/mo) gives you unlimited managed
        runs solo. Team ($39/seat) adds per-seat collaboration with shared
        spec templates, audit log, SSO — and runs go through your own
        Anthropic / OpenAI / Google keys (BYOK), so there's no per-run charge
        from us beyond the seat fee.
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

// ─── Pro → Team upsell ─────────────────────────────────────────────────────
// Different framing than Free → Pro: Pro already has unlimited managed
// runs, so the value-prop is collaboration + BYOK economics, not raw run
// volume. Per the audit fix: Pro on /v3/team must not see the same Free
// upsell card.

function ProTierUpsellCard({ onUpgrade }: { onUpgrade: () => void }) {
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
        <Pill tone="info">Team tier</Pill>
        <h2 style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>
          Bring your team in. Bring your own keys.
        </h2>
      </div>
      <p style={{ margin: 0, fontSize: 12.5, color: 'var(--v3-text-muted)', lineHeight: 1.55 }}>
        Pro is a one-developer plan. Team ($39/seat, min 2) adds:
      </p>
      <ul style={{ margin: '8px 0 0 18px', padding: 0, fontSize: 12.5, color: 'var(--v3-text-muted)', lineHeight: 1.6 }}>
        <li><strong style={{ color: 'var(--v3-text)' }}>BYOK runs</strong> — route through your own Anthropic / OpenAI / Google keys, no per-run charge from us</li>
        <li><strong style={{ color: 'var(--v3-text)' }}>Per-seat collaboration</strong> — shared run history, shared spec templates</li>
        <li><strong style={{ color: 'var(--v3-text)' }}>Org SSO</strong> (SAML / OIDC) and <strong style={{ color: 'var(--v3-text)' }}>audit log export</strong></li>
        <li><strong style={{ color: 'var(--v3-text)' }}>Activity feed</strong> across every project in the org</li>
      </ul>
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
          See Team pricing →
        </button>
      </div>
    </section>
  );
}

// ─── Seats card ─────────────────────────────────────────────────────────────

function SeatsCard({ org, tier, memberCount }: { org: OrgDto | null; tier: string; memberCount: number }) {
  const seatCount = org?.seatCount ?? 0;
  // Tier-coherence: ENTERPRISE is always unlimited regardless of any
  // org.seatCount value the backend persisted (some legacy orgs were
  // seeded with seat_count=1 which is meaningless for Enterprise).
  // TEAM honours the per-seat purchase (min 2, max 20).
  const isUnlimited = tier === 'ENTERPRISE' || seatCount < 0;
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
