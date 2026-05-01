import {Outlet, useLocation} from 'react-router-dom';
import {useEffect} from 'react';
import V3TopBar from './V3TopBar';
import CmdK from './CmdK';
import {FreeCapToast, FreeCapBlockingModal} from './FreeCapNudge';
import UpgradeNudge from './UpgradeNudge';
import {useV3Ui} from '../stores/useV3Ui';
import ErrorBoundary from '@/components/shared/ErrorBoundary';
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

export default function V3Shell({withTopBar = true}: {withTopBar?: boolean}) {
  const location = useLocation();
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
    <div className='v3-root'>
      {withTopBar && <V3TopBar />}
      {/* TourBanner removed 2026-04-28 (concern 2 — "What's new" surface
          paused; revisit later). The "/v3/changelog" page it linked to
          was deleted in the same batch. To revive: re-import + restore
          this mount, AND restore frontend/src/v3/components/TourBanner.tsx
          + frontend/src/v3/pages/V3Changelog.tsx + the App.tsx route. */}
      {/* R-312 (Wave 7) — Pro→Team upgrade nudge above main, gated on
          PRO tier + ≥5 lifetime completed runs + not-dismissed. The
          component is render-cheap (returns null in 99% of cases). */}
      <UpgradeNudge />
      {/* [A-01] (audit Sprint 4, 2026-05-01) — id="main" jump target
          for the App-level "Skip to main content" link. WCAG 2.4.1
          Bypass Blocks (Level A). tabIndex=-1 lets the link's target
          receive programmatic focus without making <main> a tab stop. */}
      <main id='main' tabIndex={-1} className='v3-main'>
        {/* Task 2 (2026-05-01 fix list): page-level error boundary so a
         *  single broken page can't blank the entire v3 surface. The
         *  `key` on pathname remounts the boundary on route change so
         *  navigating away from a failed page recovers cleanly. */}
        <ErrorBoundary key={location.pathname} region='v3-shell'>
          <Outlet />
        </ErrorBoundary>
      </main>
      <CmdK />
      <FreeCapToast />
      <CapModalMount />
    </div>
  );
}
