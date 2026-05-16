/**
 * Settings — iOS-style grouped list.
 *
 * Sections (top → bottom, by frequency of use):
 *   - Account       (name, email, password, MFA)
 *   - Workspace     (tier badge + usage glance + manage billing)
 *   - Integrations  (GitHub PAT, Bitbucket app-password, Slack)
 *   - Notifications (per-event toggles — link to v3 panel)
 *   - Org           (members, invites — admin only)
 *   - Trust         (legal, GDPR export)
 *   - Switch UI     (back to classic)
 *   - Danger zone   (sign out)
 *
 * Each row navigates to its v3 counterpart for the actual edit flow.
 * The Settings page in v3 is one long form; here we surface a clean
 * index that the user can tap-through. Future iteration: build inline
 * editors for the top-frequency items (theme, name).
 */
import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {
  CreditCard,
  Github,
  Bell,
  Users,
  ShieldCheck,
  LogOut,
  Moon,
  Sun,
  ArrowLeftRight,
  Key,
  Mail,
} from 'lucide-react';
import {useAuthStore, selectIsOrgAdmin} from '@/stores/useAuthStore';
import {useUiStore} from '@/stores/useUiStore';
import LargeTitle from '../components/LargeTitle';
import ListGroup from '../components/ListGroup';

export default function SettingsPage() {
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const navigate = useNavigate();
  const theme = useUiStore((s) => s.theme);
  const toggleTheme = useUiStore((s) => s.toggleTheme);
  const [billing, setBilling] = useState<{
    tier?: string;
    runsUsed?: number;
    runsLimit?: number;
  } | null>(null);

  useEffect(() => {
    // Best-effort fetch — silent on failure (FREE users without billing
    // record return 404).
    fetch('/api/billing/status', {
      headers: {Authorization: `Bearer ${localStorage.getItem('access_token')}`},
    })
      .then((r) => (r.ok ? r.json() : null))
      .then((b) => setBilling(b))
      .catch(() => undefined);
  }, []);

  const tierLabel =
    user?.tier === 'PRO'
      ? 'Pro'
      : user?.tier === 'TEAM'
        ? 'Team'
        : user?.tier === 'ENTERPRISE'
          ? 'Enterprise'
          : 'Free';

  const handleSignOut = async () => {
    try {
      await logout();
    } finally {
      navigate('/login');
    }
  };

  return (
    <>
      <LargeTitle title='You' subtitle={user?.email ?? 'Account & preferences'} />

      {/* Identity card */}
      <section
        className='v4-card v4-reveal'
        style={{
          padding: '16px',
          marginBottom: 20,
          display: 'flex',
          alignItems: 'center',
          gap: 14,
        }}
      >
        <span
          aria-hidden='true'
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 48,
            height: 48,
            borderRadius: 9999,
            background: 'var(--v4-brand-soft)',
            color: 'var(--v4-brand)',
            fontWeight: 700,
            fontSize: 18,
          }}
        >
          {(user?.email ?? 'U').slice(0, 1).toUpperCase()}
        </span>
        <div style={{flex: 1, minWidth: 0}}>
          <div
            style={{
              fontSize: 'var(--v4-text-base)',
              fontWeight: 600,
              color: 'var(--v4-text)',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            {user?.email ?? 'Signed in'}
          </div>
          <div
            style={{
              fontSize: 'var(--v4-text-xs)',
              color: 'var(--v4-text-subtle)',
              marginTop: 2,
            }}
          >
            {tierLabel} plan
            {billing?.runsLimit != null
              ? ` · ${billing.runsUsed ?? 0}/${billing.runsLimit} runs this period`
              : ''}
          </div>
        </div>
      </section>

      <ListGroup label='Account'>
        <ListGroup.Row
          icon={<Mail size={14} />}
          iconBg='#5B89E0'
          title='Email'
          detail={user?.email ?? 'Unverified'}
          trailing={user?.emailVerified ? 'verified' : 'unverified'}
        />
        <ListGroup.Row
          icon={<Key size={14} />}
          iconBg='#9D6EE5'
          title='Password & sign-in'
          detail='Change password, MFA'
          chevron
          onClick={() => navigate('/v4/settings/account')}
        />
      </ListGroup>

      <ListGroup label='Workspace'>
        <ListGroup.Row
          icon={<CreditCard size={14} />}
          iconBg='#5B8DEF'
          title='Billing & plan'
          detail={`${tierLabel} · usage + invoices`}
          chevron
          onClick={() => navigate('/v4/settings/billing')}
        />
        <ListGroup.Row
          icon={theme === 'light' ? <Moon size={14} /> : <Sun size={14} />}
          iconBg='#22C5A4'
          title={theme === 'light' ? 'Switch to dark' : 'Switch to light'}
          detail={`Currently ${theme ?? 'system'}`}
          onClick={() => toggleTheme()}
        />
      </ListGroup>

      <ListGroup label='Integrations'>
        <ListGroup.Row
          icon={<Github size={14} />}
          iconBg='#22272E'
          title='GitHub'
          detail={user?.hasGithubPat ? 'PAT configured' : 'Not connected'}
          chevron
          onClick={() => navigate('/v4/settings/integrations')}
        />
        <ListGroup.Row
          icon={<Github size={14} />}
          iconBg='#0052CC'
          title='Bitbucket'
          detail={user?.hasBitbucketAppPassword ? 'App password set' : 'Not connected'}
          chevron
          onClick={() => navigate('/v4/settings/integrations')}
        />
        <ListGroup.Row
          icon={<Bell size={14} />}
          iconBg='#F59E0B'
          title='Notifications'
          detail='In-app, email, Slack'
          chevron
          onClick={() => navigate('/v4/settings/account')}
        />
      </ListGroup>

      {isOrgAdmin && (
        <ListGroup label='Organization'>
          <ListGroup.Row
            icon={<Users size={14} />}
            iconBg='#7AD1C8'
            title='Team & invites'
            chevron
            onClick={() => navigate('/v4/settings/team')}
          />
          <ListGroup.Row
            icon={<ShieldCheck size={14} />}
            iconBg='#16A34A'
            title='Org settings'
            detail='Standards, SSO, default branch'
            chevron
            onClick={() => navigate('/v4/settings/account')}
          />
        </ListGroup>
      )}

      <ListGroup label='Compliance'>
        <ListGroup.Row
          title='Trust pack'
          detail='Security · privacy · DPA'
          chevron
          href='/v4/settings/trust'
        />
        <ListGroup.Row
          title='Export my data'
          detail='GDPR data export ZIP'
          chevron
          href='/api/auth/me/data-export'
        />
      </ListGroup>

      <ListGroup
        label='Switch UI'
        footer='You can return any time — both UIs share the same account and data.'
      >
        <ListGroup.Row
          icon={<ArrowLeftRight size={14} />}
          iconBg='var(--v4-brand)'
          title='Back to classic UI'
          detail='The current production interface'
          chevron
          onClick={() => navigate('/home')}
        />
      </ListGroup>

      <ListGroup>
        <ListGroup.Row
          icon={<LogOut size={14} />}
          iconBg='var(--v4-fail)'
          title='Sign out'
          destructive
          onClick={handleSignOut}
        />
      </ListGroup>
    </>
  );
}
