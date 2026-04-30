import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * OAuth2 callback page — receives JWT tokens from the backend redirect
 * and stores them before navigating to the app.
 */
export default function AuthCallbackPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { loadMe } = useAuthStore();
  const [error, setError] = useState('');

  useEffect(() => {
    const token = searchParams.get('token');
    const refresh = searchParams.get('refresh');

    if (!token) {
      setError('Authentication failed. No token received.');
      setTimeout(() => navigate('/login', { replace: true }), 2000);
      return;
    }

    // Store tokens
    localStorage.setItem('access_token', token);
    if (refresh) {
      localStorage.setItem('refresh_token', refresh);
    }

    // Update auth store and navigate
    useAuthStore.setState({
      accessToken: token,
      isAuthenticated: true,
    });

    // Load user profile, then redirect.  R-342: post-auth lands on
    // /home (the V3HomeRedirect dispatcher) so the user ends up on
    // their first project's chat thread, not the v2 RunsPage.
    loadMe().then(() => {
      navigate('/home', { replace: true });
    }).catch(() => {
      navigate('/home', { replace: true });
    });
  }, [searchParams, navigate, loadMe]);

  if (error) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[#0f1117]">
        <p className="text-accent-red">{error}</p>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#0f1117]">
      <div className="text-center">
        <div className="mx-auto mb-4 h-8 w-8 animate-spin rounded-full border-2 border-purple-500 border-t-transparent" />
        <p className="text-muted">Completing sign in...</p>
      </div>
    </div>
  );
}
