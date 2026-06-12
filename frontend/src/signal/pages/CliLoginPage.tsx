import {useEffect, useMemo, useState, type CSSProperties} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {useAuthStore} from '@/stores/useAuthStore';
import {createPat} from '@/lib/api';

/**
 * gcloud-style device-authorization page for the `spec` CLI + VS Code extension.
 *
 * The client opens the browser here with `?port=<loopback>&state=<csrf>` (CLI) or
 * `?mode=vscode&state=…` (extension). Once the user is signed in and explicitly clicks Authorize,
 * we mint a Personal Access Token (`POST /auth/me/tokens`) and hand it back to the local client by
 * redirecting to the loopback callback (`http://127.0.0.1:<port>/cb`) or the `vscode://` URI —
 * never to any other host. The Authorize click is the human consent gate (no drive-by minting), and
 * `state` echoes back so the client can reject a forged callback.
 */
export default function CliLoginPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const isAuthed = useAuthStore((s) => s.isAuthenticated);

  const state = params.get('state') ?? '';
  const mode = params.get('mode') ?? 'cli';
  const isVscode = mode === 'vscode';
  const portRaw = params.get('port');
  const port = portRaw && /^\d{1,5}$/.test(portRaw) ? Number(portRaw) : null;

  const [status, setStatus] = useState<'idle' | 'working' | 'done' | 'error'>('idle');
  const [message, setMessage] = useState('');

  // Not signed in → bounce to /login, preserving our FULL url (incl. ?port&state) via ?redirect.
  useEffect(() => {
    if (!isAuthed) {
      const back = `/cli-login?${params.toString()}`;
      navigate(`/login?redirect=${encodeURIComponent(back)}`, {replace: true});
    }
  }, [isAuthed, params, navigate]);

  const valid = useMemo(() => {
    if (!state) return false;
    return isVscode ? true : port !== null && port >= 1 && port <= 65535;
  }, [state, isVscode, port]);

  async function authorize() {
    if (!valid) {
      setStatus('error');
      setMessage(
        'This sign-in link is malformed or expired — re-run the command in your terminal.',
      );
      return;
    }
    setStatus('working');
    try {
      const name = `${isVscode ? 'VS Code' : 'CLI'} · ${new Date().toISOString().slice(0, 10)}`;
      const {token} = await createPat(name);
      // Hand the token to the local client over loopback (127.0.0.1 only) or the vscode:// handler.
      const target = isVscode
        ? `vscode://executespec.executespec-vscode/auth?token=${encodeURIComponent(token)}&state=${encodeURIComponent(state)}`
        : `http://127.0.0.1:${port}/cb?token=${encodeURIComponent(token)}&state=${encodeURIComponent(state)}`;
      setStatus('done');
      window.location.href = target;
    } catch (e) {
      setStatus('error');
      setMessage(e instanceof Error ? e.message : 'Could not create a token.');
    }
  }

  if (!isAuthed) return null; // redirecting to /login

  return (
    <div style={wrap}>
      <div style={card}>
        <h2 style={{margin: '0 0 .35rem', fontSize: 20}}>
          Authorize the ExecuteSpec {isVscode ? 'VS Code extension' : 'CLI'}
        </h2>
        <p style={{color: 'var(--signal-text-dim)', margin: '0 0 1.25rem', fontSize: 14}}>
          {isVscode
            ? 'Grant the VS Code extension on this machine access to your account.'
            : `Grant the CLI on this machine (loopback port ${port ?? '—'}) access to your account.`}
        </p>

        {status === 'error' && <p style={errStyle}>{message}</p>}

        {status === 'done' ? (
          <p style={{color: '#16a34a', fontSize: 14, margin: 0}}>
            ✓ Authorized — return to your {isVscode ? 'editor' : 'terminal'}. You can close this
            tab.
          </p>
        ) : (
          <div style={{display: 'flex', gap: 12, justifyContent: 'center'}}>
            <button
              style={primaryBtn}
              disabled={!valid || status === 'working'}
              onClick={authorize}
            >
              {status === 'working' ? 'Authorizing…' : 'Authorize'}
            </button>
            <button
              style={ghostBtn}
              disabled={status === 'working'}
              onClick={() => navigate('/home', {replace: true})}
            >
              Cancel
            </button>
          </div>
        )}

        <p style={{color: 'var(--signal-text-dim)', margin: '1.25rem 0 0', fontSize: 12}}>
          A revocable Personal Access Token is created and sent only to this device. Manage tokens
          in Settings.
        </p>
      </div>
    </div>
  );
}

const wrap: CSSProperties = {
  display: 'grid',
  placeItems: 'center',
  minHeight: '100dvh',
  background: 'var(--signal-bg)',
  padding: 24,
};
const card: CSSProperties = {
  maxWidth: 460,
  textAlign: 'center',
  background: 'var(--signal-surface, #ffffff)',
  border: '1px solid var(--signal-hairline)',
  borderRadius: 12,
  padding: 32,
};
const errStyle: CSSProperties = {color: '#dc2626', fontSize: 14, marginBottom: 12};
const primaryBtn: CSSProperties = {
  background: 'var(--signal-coral, #5b8def)',
  color: '#ffffff',
  border: 'none',
  borderRadius: 8,
  padding: '10px 20px',
  fontSize: 14,
  cursor: 'pointer',
  fontFamily: 'inherit',
};
const ghostBtn: CSSProperties = {
  background: 'transparent',
  color: 'var(--signal-text)',
  border: '1px solid var(--signal-hairline)',
  borderRadius: 8,
  padding: '10px 20px',
  fontSize: 14,
  cursor: 'pointer',
  fontFamily: 'inherit',
};
