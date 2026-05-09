import {useEffect, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {useAuthStore} from '@/stores/useAuthStore';

/**
 * OAuth2 callback page — receives JWT tokens from the backend redirect
 * and stores them before navigating to the app.
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
    // bare `/home` redirect.
    //
    // Strategy: forward any `?spec=` query param from the callback URL
    // through to /home. sessionStorage is browser-local and already
    // survives the OAuth bounce; query params on the marketing-site
    // OAuth-init URL flow through to the backend's OAuth init endpoint
    // and back here as part of the state param (per OAuthInitController).
    const stashedSpec = searchParams.get('spec');
    const homeUrl = stashedSpec ? `/home?spec=${encodeURIComponent(stashedSpec)}` : '/home';
    loadMe()
      .then(() => {
        navigate(homeUrl, {replace: true});
      })
      .catch(() => {
        navigate(homeUrl, {replace: true});
      });
  }, [searchParams, navigate, loadMe]);

  if (error) {
    return (
      <div className='flex min-h-screen items-center justify-center bg-[var(--gray-100)]'>
        <p className='text-accent-red'>{error}</p>
      </div>
    );
  }

  return (
    <div className='flex min-h-screen items-center justify-center bg-[var(--gray-100)]'>
      <div className='text-center'>
        <div className='mx-auto mb-4 h-8 w-8 animate-spin rounded-full border-2 border-accent-blue border-t-transparent' />
        <p className='text-muted'>Completing sign in...</p>
      </div>
    </div>
  );
}
