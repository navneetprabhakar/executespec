import { Outlet } from 'react-router-dom';
import { useEffect } from 'react';
import V3TopBar from './V3TopBar';
import CmdK from './CmdK';
import { FreeCapToast, FreeCapBlockingModal } from './FreeCapNudge';
import TourBanner from './TourBanner';
import UpgradeNudge from './UpgradeNudge';
import { useV3Ui } from '../stores/useV3Ui';
import '../v3.css';

/**
 * R-101 — v3 router shell.
 *
 * Wraps every /v3/* route. Mounts the top-bar (project switcher placeholder
 * for now; populated in R-102/R-124) and renders children in a centered
 * max-width container. **No sidebar.** No breadcrumb. Just top-bar + body.
 *
 * R-307 (Wave 6) — Theme lock REMOVED.
 *
 * Previous behaviour: V3Shell force-set `data-theme="dark"` on `<html>` on
 * mount + restored the prior theme on unmount. That was a deliberate Wave-1
 * lock because v3.css didn't ship a light-mode override block — forcing
 * dark was the only way to guarantee legibility.
 *
 * Wave 6 R-302 added a `[data-theme='light'] .v3-root` block in v3.css
 * with paper-* primitive aliases for surface/border/text. With the
 * override in place the lock becomes counterproductive: it overrides the
 * user's theme choice from the v2 ThemeToggle.
 *
 * The `v3-active` class is still set on `<html>` so v2 stylesheets can
 * conditionally scope their own rules away from v3 routes if needed
 * (no current consumer; reserved for future use).
 */
function CapModalMount() {
  const open = useV3Ui((s) => s.capModalOpen);
  const close = useV3Ui((s) => s.closeCapModal);
  return <FreeCapBlockingModal open={open} onClose={close} />;
}

export default function V3Shell({ withTopBar = true }: { withTopBar?: boolean }) {
  useEffect(() => {
    const root = document.documentElement;
    // R-307 (Wave 6): just mark v3-active. Theme attribute stays under
    // the user's control via the v2 ThemeToggle (useUiStore).
    root.classList.add('v3-active');
    return () => {
      root.classList.remove('v3-active');
    };
  }, []);

  return (
    <div className="v3-root">
      {withTopBar && <V3TopBar />}
      <TourBanner />
      {/* R-312 (Wave 7) — Pro→Team upgrade nudge above main, gated on
          PRO tier + ≥5 lifetime completed runs + not-dismissed. The
          component is render-cheap (returns null in 99% of cases). */}
      <UpgradeNudge />
      <main className="v3-main">
        <Outlet />
      </main>
      <CmdK />
      <FreeCapToast />
      <CapModalMount />
    </div>
  );
}
