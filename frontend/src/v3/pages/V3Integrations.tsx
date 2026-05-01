import {useEffect, useState} from 'react';
import {Link} from 'react-router-dom';
import {authMe, type AuthUser} from '@/lib/api';
import Pill from '../components/Pill';

/**
 * R-144 — /integrations grid.
 *
 * Greenfield §4.12: card grid by install state. v3 doesn't ship its
 * own OAuth wiring for every integration today — many of them already
 * have working v2 OAuth flows. The grid exposes them all in one place
 * with state chips, deep-linking to the v2 settings/integration page
 * for connect/manage actions.
 *
 * Defaults applied per D-144 (greenfield §4.12):
 *   Real today: GitHub App + GitHub PAT + Slack + Bitbucket
 *   Schema-only: Jira + Confluence (link to v2 management)
 *   Coming soon: ServiceNow + PagerDuty (no link)
 */

type IntegrationState = 'connected' | 'available' | 'soon';

interface IntegrationCard {
  id: string;
  name: string;
  group: 'Source control' | 'Notifications' | 'Ticketing' | 'Incident response';
  /** When connected, the dropdown summary line; when available, the value-prop. */
  oneLiner: string;
  manageHref: string | null;
  connectHref: string | null;
  /** Pulled from AuthUser flags when applicable. */
  isConnected: (u: AuthUser | null) => boolean;
  isComingSoon?: boolean;
}

const CARDS: IntegrationCard[] = [
  {
    id: 'github-pat',
    name: 'GitHub (PAT)',
    group: 'Source control',
    oneLiner: 'Personal access token — auto-detected when you sign in with GitHub OAuth.',
    manageHref: '/settings',
    connectHref: '/settings',
    isConnected: (u) => Boolean(u?.hasGithubPat),
  },
  {
    id: 'github-app',
    name: 'GitHub App (org-wide)',
    group: 'Source control',
    oneLiner:
      'Install once for your org so every member can attach private repos without sharing PATs.',
    manageHref: '/integrations',
    connectHref: '/integrations',
    isConnected: () => false, // detection lands in Wave 3 backend pass
  },
  {
    id: 'bitbucket',
    name: 'Bitbucket',
    group: 'Source control',
    oneLiner: 'App password + workspace.',
    manageHref: '/settings',
    connectHref: '/settings',
    isConnected: (u) => Boolean(u?.bitbucketUsername && u?.hasBitbucketAppPassword),
  },
  {
    id: 'slack',
    name: 'Slack',
    group: 'Notifications',
    oneLiner: 'Pipeline notifications + run-completion alerts in your team channel.',
    manageHref: '/integrations',
    connectHref: '/integrations',
    isConnected: () => false, // org-level — checked via the org's slackTeamId in Wave 3
  },
  {
    id: 'jira',
    name: 'Jira',
    group: 'Ticketing',
    oneLiner: 'Sync runs to Jira issues and post status comments back.',
    manageHref: '/integrations',
    connectHref: '/integrations',
    isConnected: (u) => Boolean(u?.hasJiraToken),
  },
  {
    id: 'confluence',
    name: 'Confluence',
    group: 'Ticketing',
    oneLiner: 'Read specs from Confluence pages; post run summaries as child pages.',
    manageHref: '/integrations',
    connectHref: '/integrations',
    isConnected: (u) => Boolean(u?.hasConfluenceToken),
  },
  {
    id: 'servicenow',
    name: 'ServiceNow',
    group: 'Incident response',
    oneLiner: 'Trigger runs from incidents; attach output to the ticket.',
    manageHref: null,
    connectHref: null,
    isConnected: () => false,
    isComingSoon: true,
  },
  {
    id: 'pagerduty',
    name: 'PagerDuty',
    group: 'Incident response',
    oneLiner: 'Page on Build failed verdicts; auto-acknowledge on Ready.',
    manageHref: null,
    connectHref: null,
    isConnected: () => false,
    isComingSoon: true,
  },
];

export default function V3Integrations() {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    authMe()
      .then((u) => {
        if (!cancelled) setUser(u);
      })
      .catch(() => {
        if (!cancelled) setUser(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Group cards by section, preserving definition order
  const grouped = CARDS.reduce<Record<string, IntegrationCard[]>>((acc, c) => {
    (acc[c.group] = acc[c.group] || []).push(c);
    return acc;
  }, {});

  return (
    <div style={{display: 'flex', flexDirection: 'column', gap: 16, paddingBottom: 32}}>
      <div>
        <h1 style={{fontSize: 22, fontWeight: 600, color: 'var(--v3-text)', margin: 0}}>
          Integrations
        </h1>
        <p style={{fontSize: 12, color: 'var(--v3-text-muted)', margin: '4px 0 0'}}>
          External tools that can trigger runs, post results, or extend ExecuteSpec.
        </p>
      </div>

      {loading && (
        <div className='v3-placeholder'>
          <p>Loading…</p>
        </div>
      )}

      {!loading &&
        Object.entries(grouped).map(([group, cards]) => (
          <section key={group}>
            <h2
              style={{
                margin: '0 0 8px',
                fontSize: 11,
                fontWeight: 600,
                color: 'var(--v3-text-subtle)',
                letterSpacing: 0.5,
                textTransform: 'uppercase',
              }}
            >
              {group}
            </h2>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
                gap: 10,
              }}
            >
              {cards.map((c) => (
                <Card key={c.id} card={c} state={resolveState(c, user)} />
              ))}
            </div>
          </section>
        ))}

      <p
        style={{
          marginTop: 12,
          fontSize: 11,
          color: 'var(--v3-text-subtle)',
          textAlign: 'center',
        }}
      >
        Setting up SSO instead?{' '}
        <Link to='/team' style={{color: 'var(--v3-brand)'}}>
          Open Team
        </Link>
        .
      </p>
    </div>
  );
}

function resolveState(c: IntegrationCard, u: AuthUser | null): IntegrationState {
  if (c.isComingSoon) return 'soon';
  return c.isConnected(u) ? 'connected' : 'available';
}

function Card({card, state}: {card: IntegrationCard; state: IntegrationState}) {
  const tone = state === 'connected' ? 'ok' : state === 'soon' ? 'neutral' : 'info';
  const stateLabel =
    state === 'connected' ? 'Connected' : state === 'soon' ? 'Coming soon' : 'Connect →';
  const href = state === 'connected' ? card.manageHref : state === 'soon' ? null : card.connectHref;

  const Wrapper: React.ElementType = href ? 'a' : 'div';
  const wrapperProps = href ? {href} : {};

  return (
    <Wrapper
      {...wrapperProps}
      style={{
        display: 'block',
        padding: 14,
        background: 'var(--v3-surface)',
        border:
          state === 'connected' ? '1px solid rgba(16,185,129,0.36)' : '1px solid var(--v3-border)',
        borderRadius: 8,
        textDecoration: 'none',
        color: 'inherit',
        cursor: href ? 'pointer' : 'default',
        opacity: state === 'soon' ? 0.7 : 1,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
          marginBottom: 6,
        }}
      >
        <h3 style={{margin: 0, fontSize: 13, fontWeight: 600, color: 'var(--v3-text)'}}>
          {card.name}
        </h3>
        <Pill tone={tone} size='sm'>
          {state === 'connected' ? '✓ Connected' : state === 'soon' ? 'Soon' : 'Available'}
        </Pill>
      </div>
      <p
        style={{
          margin: '4px 0 8px',
          fontSize: 11.5,
          color: 'var(--v3-text-muted)',
          lineHeight: 1.55,
          minHeight: 36,
        }}
      >
        {card.oneLiner}
      </p>
      <div style={{fontSize: 11, color: 'var(--v3-brand)'}}>{stateLabel}</div>
    </Wrapper>
  );
}
