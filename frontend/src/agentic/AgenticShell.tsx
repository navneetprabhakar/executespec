/**
 * Agentic v2 shell — the chrome wrapper for every /a/* route.
 *
 * Wraps every route in:
 *   - <TopBar/>      sticky glass header
 *   - <main/>        the page content (max-width centered)
 *   - <BottomNav/>   phone-only persistent tab bar
 *   - <AgentInput/>  always-reachable agent composer
 *
 * Mounts ErrorBoundary at the page level so a broken route can't blank
 * the entire v2 surface. Adds `agentic-root` to <html> so the scoped
 * tokens cascade.
 */
import {useEffect} from 'react';
import {Outlet, useLocation} from 'react-router-dom';
import ErrorBoundary from '@/components/shared/ErrorBoundary';
import TopBar from './components/TopBar';
import BottomNav from './components/BottomNav';
import AgentInput from './components/AgentInput';
import {useAuthStore} from '@/stores/useAuthStore';
import './agentic.css';

interface Props {
  /** When false, suppresses the AgentInput (used by auth pages). */
  withAgentInput?: boolean;
}

export default function AgenticShell({withAgentInput = true}: Props) {
  const location = useLocation();
  const user = useAuthStore((s) => s.user);

  useEffect(() => {
    document.documentElement.classList.add('agentic-active');
    return () => {
      document.documentElement.classList.remove('agentic-active');
    };
  }, []);

  // Hide the agent input on auth pages / read-only public surfaces.
  const HIDE_INPUT_PREFIXES = [
    '/a/login',
    '/a/register',
    '/a/forgot-password',
    '/a/reset-password',
    '/a/verify-email',
  ];
  const inputHidden =
    !withAgentInput ||
    !user ||
    HIDE_INPUT_PREFIXES.some((p) => location.pathname.startsWith(p));

  return (
    <div className='agentic-root agentic-shell'>
      <TopBar />
      <main id='main' tabIndex={-1} className='agentic-main'>
        <ErrorBoundary key={location.pathname} region='agentic-shell'>
          <Outlet />
        </ErrorBoundary>
      </main>
      {!inputHidden && <AgentInput />}
      <BottomNav />
    </div>
  );
}
