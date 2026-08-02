import {useEffect, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {authRefreshSession} from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import {safeInternalRedirect} from './authRedirect';

/**
 * OAuth2 callback page — establishes the session after the backend redirect.
 *
 * RR-130 / RR-1334: the redirect no longer carries `?token=` / `?refresh=`. Bearer and seven-day
 * refresh credentials in a query string leak into browser history, referrers, and reverse-proxy /
 * access logs. The backend now sets the HttpOnly `refresh_token` cookie on the redirect response
 * (exactly as password login does) and this page trades it for an access token through the normal
 * rotation endpoint. That also gives OAuth sessions a real refresh cookie, which they previously
 * lacked entirely — they died at access-token expiry with nothing to renew from.
 */
export default function AuthCallbackPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const {loadMe} = useAuthStore();
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;

    // Defensively strip anything a stale/older backend may still have put in the URL, and any
    // refresh_token a prior buggy version of this page wrote to localStorage. The cookie is the
    // only authoritative refresh credential.
    localStorage.removeItem('refresh_token');

    // [F-16] (audit Sprint 1 PR-2, 2026-04-30) — preserve the marketing-
    // site spec stash across OAuth round-trip. The marketing site can
    // pre-fill `?spec=<truncated>` on the OAuth init link or stash the
    // full spec into `sessionStorage['v3-anon-spec']` (read by
    // HomeRedirectPage / EmptyProjectPage); both paths must survive the
    // bounce through Google/GitHub OAuth without being clobbered by the
    // bare `/compose` redirect.
    //
    // Strategy: forward any `?spec=` query param from the callback URL
    // through to /compose. sessionStorage is browser-local and already
    // survives the OAuth bounce; query params on the marketing-site
    // OAuth-init URL flow through to the backend's OAuth init endpoint
    // and back here as part of the state param (per OAuthInitController).
    // `?redirect=` carries an internal return path across the OAuth round-trip — used by the
    // CLI / VS Code `/cli-login` device-authorization flow (the backend OAuthSuccessHandler appends
    // it from the short-lived es_oauth_redirect cookie). Path-only + same-origin nav, so safe.
    const redirectParam = searchParams.get('redirect');
    const safeRedirect = safeInternalRedirect(redirectParam);
    const stashedSpec = searchParams.get('spec');
    const fallbackUrl = stashedSpec
      ? `/compose?spec=${encodeURIComponent(stashedSpec)}`
      : '/compose';
    const target = safeRedirect ?? fallbackUrl;

    authRefreshSession()
      .then(async (resp) => {
        if (cancelled) return;
        localStorage.setItem('access_token', resp.accessToken);
        useAuthStore.setState({accessToken: resp.accessToken, isAuthenticated: true});
        try {
          await loadMe();
        } catch {
          // Permissions/profile can be re-fetched by the app; the session itself is established.
        }
        if (!cancelled) navigate(target, {replace: true});
      })
      .catch(() => {
        if (cancelled) return;
        setError('Authentication failed. Please sign in again.');
        setTimeout(() => navigate('/login', {replace: true}), 2000);
      });

    return () => {
      cancelled = true;
    };
  }, [searchParams, navigate, loadMe]);

  if (error) {
    return (
      <div
        style={{
          display: 'grid',
          placeItems: 'center',
          minHeight: '100dvh',
          background: 'var(--signal-bg)',
          color: 'var(--signal-coral-text)',
          padding: 24,
          textAlign: 'center',
        }}
      >
        <p style={{fontSize: 14, margin: 0}}>{error}</p>
      </div>
    );
  }

  return (
    <div
      style={{
        display: 'grid',
        placeItems: 'center',
        minHeight: '100dvh',
        background: 'var(--signal-bg)',
      }}
    >
      <div style={{textAlign: 'center'}}>
        <div
          style={{
            width: 32,
            height: 32,
            borderRadius: '50%',
            border: '3px solid var(--signal-hairline)',
            borderTopColor: 'var(--signal-coral)',
            margin: '0 auto 16px',
            animation: 'spin 0.9s linear infinite',
          }}
        />
        <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0}}>
          Completing sign in...
        </p>
        <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
      </div>
    </div>
  );
}
