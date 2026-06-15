import {useEffect, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {useAuthStore} from '@/stores/useAuthStore';

/**
 * OAuth2 callback page — receives JWT tokens from the backend redirect
 * and stores them before navigating to the app.
 *
 * Logic copied verbatim from dev branch (frontend/src/pages/auth/AuthCallbackPage.tsx).
 * The styling uses Signal tokens so it blends with the rest of the app.
 */
export default function AuthCallbackPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const {loadMe} = useAuthStore();
  const [error, setError] = useState('');

  useEffect(() => {
    const token = searchParams.get('token');
    const refresh = searchParams.get('refresh');

    if (!token) {
      setError('Authentication failed. No token received.');
      setTimeout(() => navigate('/login', {replace: true}), 2000);
      return;
    }

    // Store access token only. The refresh token lives in an HttpOnly
    // cookie set by the backend on /api/auth/login + /api/auth/refresh
    // — writing it to localStorage too is an XSS exfiltration surface
    // (any injected script would slurp it and bypass the HttpOnly
    // protection). [J-21] (coverage Sprint 3, 2026-05-01) strip the
    // localStorage write; the OAuth callback flow's cookie is set via
    // backend redirect Set-Cookie header before this page renders.
    //
    // Defensively also REMOVE any pre-existing refresh_token entry
    // from prior buggy versions of this page — same security guarantee.
    localStorage.setItem('access_token', token);
    localStorage.removeItem('refresh_token');
    void refresh; // intentionally unused — cookie is authoritative

    // Update auth store and navigate
    useAuthStore.setState({
      accessToken: token,
      isAuthenticated: true,
    });

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
    const safeRedirect = redirectParam && redirectParam.startsWith('/') ? redirectParam : undefined;
    const stashedSpec = searchParams.get('spec');
    const fallbackUrl = stashedSpec
      ? `/compose?spec=${encodeURIComponent(stashedSpec)}`
      : '/compose';
    const target = safeRedirect ?? fallbackUrl;
    loadMe()
      .then(() => {
        navigate(target, {replace: true});
      })
      .catch(() => {
        navigate(target, {replace: true});
      });
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
