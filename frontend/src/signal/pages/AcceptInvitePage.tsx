import {useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Card, Button, KvList} from '@/signal/components/Primitives';
import AuthLayout from '@/signal/components/AuthLayout';
import {useAuthStore} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

const MERGE_RAIL_MARK = '/assets/brand/merge-rail/executespec-merge-rail-mark-light.svg';

function FormError({msg}: {msg: string}) {
  if (!msg) return null;
  return (
    <div
      style={{
        background: 'var(--signal-coral-soft)',
        color: 'var(--signal-coral-text)',
        border: '1px solid var(--signal-coral)',
        borderRadius: 'var(--ide-r-card)',
        padding: '10px 14px',
        marginBottom: 12,
        fontSize: 13,
      }}
    >
      {msg}
    </div>
  );
}

export function AcceptInvitePage({kind = 'project'}: {kind?: 'org' | 'project'}) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token') || '';
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const noun = kind === 'org' ? 'organization' : 'project';
  // RR-1327 — acceptance requires an authenticated principal. A signed-out
  // recipient arriving from the invite email is sent through login/signup with
  // the token-bearing path preserved via `?redirect=`, then returns to accept.
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  async function accept() {
    if (!token) {
      setErr('No invite token in the URL. Use the link from your invite email.');
      return;
    }
    if (!isAuthenticated) {
      const back = `${window.location.pathname}${window.location.search}`;
      navigate(`/login?redirect=${encodeURIComponent(back)}`, {replace: true});
      return;
    }
    setErr('');
    setBusy(true);
    try {
      if (kind === 'org') {
        await api.acceptInvite(token);
        navigate('/setup', {replace: true});
      } else {
        const r = await api.acceptProjectInvite(token);
        navigate(r?.projectId ? `/projects/${r.projectId}` : '/setup', {replace: true});
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not accept this invite.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout>
      <div className='signal-splash'>
        <div className='brand'>
          <img className='logo' src={MERGE_RAIL_MARK} alt='' />
          <div style={{fontSize: 16, fontWeight: 600}}>ExecuteSpec</div>
        </div>
        <h1>Accept invite.</h1>
        <p className='lede'>
          Open this from your email invite link. We will add you to the {noun} and take you there.
        </p>
        <FormError msg={err} />
        <Card style={{marginBottom: 18}}>
          <KvList
            pairs={[
              {
                k: 'Invite token',
                v: token ? (
                  token.slice(0, 12) + '…'
                ) : (
                  <span style={{color: 'var(--signal-coral-text)'}}>(missing)</span>
                ),
              },
              {
                k: 'Status',
                v: !token ? (
                  <span style={{color: 'var(--signal-coral-text)'}}>
                    Open the link in your email
                  </span>
                ) : isAuthenticated ? (
                  'Ready to accept'
                ) : (
                  'Sign in to accept'
                ),
              },
            ]}
          />
        </Card>
        <Button kind='mint' block onClick={accept} disabled={busy || !token}>
          {busy
            ? 'Accepting…'
            : !isAuthenticated
              ? 'Sign in to accept'
              : kind === 'org'
                ? 'Accept & continue'
                : 'Accept & open project'}
        </Button>
        <Button kind='ghost' block to='/compose' style={{color: 'var(--signal-coral-text)'}}>
          Decline invite
        </Button>
      </div>
    </AuthLayout>
  );
}
