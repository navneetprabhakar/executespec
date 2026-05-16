/**
 * v4 shell — the chrome wrapper for every /v4/* route.
 *
 * Wraps every route in:
 *   - <TopBar/>      sticky glass header
 *   - <main/>        the page content (max-width centered)
 *   - <BottomNav/>   phone-only persistent tab bar
 *   - <AgentInput/>  always-reachable agent composer
 *
 * Mounts ErrorBoundary at the page level so a broken route can't blank
 * the entire v4 surface. Adds `v4-root` to the body so the scoped
 * tokens cascade. v4 is the next generation after the v3 chrome — the
 * A/B alternate the user is evaluating.
 */
import {useEffect} from 'react';
import {Outlet, useLocation} from 'react-router-dom';
import ErrorBoundary from '@/components/shared/ErrorBoundary';
import TopBar from './components/TopBar';
import BottomNav from './components/BottomNav';
import AgentInput from './components/AgentInput';
import {useAuthStore} from '@/stores/useAuthStore';
import './v4.css';

interface Props {
  /** When false, suppresses the AgentInput (used by auth pages). */
  withAgentInput?: boolean;
}

export default function V4Shell({withAgentInput = true}: Props) {
  const location = useLocation();
  const user = useAuthStore((s) => s.user);

  useEffect(() => {
    document.documentElement.classList.add('v4-active');
    return () => {
      document.documentElement.classList.remove('v4-active');
    };
  }, []);

  // Hide the agent input on auth pages / read-only public surfaces.
  const HIDE_INPUT_PREFIXES = [
    '/v4/login',
    '/v4/register',
    '/v4/forgot-password',
    '/v4/reset-password',
    '/v4/verify-email',
  ];
  const inputHidden =
    !withAgentInput || !user || HIDE_INPUT_PREFIXES.some((p) => location.pathname.startsWith(p));

  return (
    <div className='v4-root v4-shell'>
      <TopBar />
      <main id='main' tabIndex={-1} className='v4-main'>
        <ErrorBoundary key={location.pathname} region='v4-shell'>
          <Outlet />
        </ErrorBoundary>
      </main>
      {!inputHidden && <AgentInput />}
      <BottomNav />
    </div>
  );
}
