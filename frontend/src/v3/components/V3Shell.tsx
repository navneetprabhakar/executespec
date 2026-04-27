import { Outlet } from 'react-router-dom';
import { useEffect } from 'react';
import V3TopBar from './V3TopBar';
import CmdK from './CmdK';
import { FreeCapToast, FreeCapBlockingModal } from './FreeCapNudge';
import { useV3Ui } from '../stores/useV3Ui';
import '../v3.css';

/**
 * R-101 — v3 router shell.
 *
 * Wraps every /v3/* route. Mounts the top-bar (project switcher placeholder
 * for now; populated in R-102/R-124) and renders children in a centered
 * max-width container. **No sidebar.** No breadcrumb. Just top-bar + body.
 *
 * Theme lock: v3 force-sets `data-theme="dark"` on the v3 root node so the
 * v3 surface always renders against the off-black palette. v2's theme
 * toggle on /settings keeps working for v2 surfaces (we scope by selector
 * in v3.css). The lock is set on mount + reset on unmount so navigating
 * back to a v2 route restores the user's preferred theme.
 */
function CapModalMount() {
  const open = useV3Ui((s) => s.capModalOpen);
  const close = useV3Ui((s) => s.closeCapModal);
  return <FreeCapBlockingModal open={open} onClose={close} />;
}

export default function V3Shell({ withTopBar = true }: { withTopBar?: boolean }) {
  useEffect(() => {
    const root = document.documentElement;
    const prevTheme = root.getAttribute('data-theme');
    root.setAttribute('data-theme', 'dark');
    root.classList.add('v3-active');
    return () => {
      root.classList.remove('v3-active');
      if (prevTheme) root.setAttribute('data-theme', prevTheme);
      else root.removeAttribute('data-theme');
    };
  }, []);

  return (
    <div className="v3-root">
      {withTopBar && <V3TopBar />}
      <main className="v3-main">
        <Outlet />
      </main>
      <CmdK />
      <FreeCapToast />
      <CapModalMount />
    </div>
  );
}
