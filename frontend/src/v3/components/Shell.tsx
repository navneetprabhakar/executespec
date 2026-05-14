import {Outlet, useLocation} from 'react-router-dom';
import {useEffect} from 'react';
import TopBar from './TopBar';
import BottomNav from './BottomNav';
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
 * Previous behaviour: Shell force-set `data-theme="dark"` on `<html>` on
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

/**
 * 2026-05-07 — centralised route-aware layout for `<main className="v3-main">`.
 *
 * Pre-fix every v3 page either lived inside the 960px default (cards
 * floating in a sea of empty space on 1440-wide monitors) or wired its
 * own `useEffect` to add `v3-main--wide` / `v3-main--fill` classes
 * (RunDetailPage, ProjectDashboardPage). That latter pattern duplicated
 * three lines of DOM-mutation code in every page that wanted breathing
 * room and made the policy invisible to anyone reading Shell.tsx.
 *
 * This function returns the modifier suffix per route so the policy
 * lives in one place. Three buckets:
 *   - **wide+fill** — inspection / workbench surfaces that benefit from
 *     filling the full viewport (RunDetail, project shell, library
 *     detail, migration detail).
 *   - **wide only** — list pages with naturally-scrolling content
 *     (Runs, Library, Migrations, Insights, Team, Trust, Billing,
 *     ExecDemo).
 *   - **default** — narrow centered surfaces (LandingPage / Composer,
 *     AuthPage, EmptyProjectPage). 960px feels intentional here.
 */
function classifyRoute(pathname: string): {wide: boolean; fill: boolean} {
  // Inspection / workbench — wide + fill
  if (/^\/runs\/[^/]+$/.test(pathname)) return {wide: true, fill: true};
  if (/^\/projects\/[^/]+\/runs\/[^/]+/.test(pathname)) return {wide: true, fill: true};
  // /projects/empty is a centered welcome → narrow. Every other project
  // shell path (incl. /projects/:id, /projects/:id/dashboard, /projects/:id/runs)
  // gets the workbench treatment.
  if (/^\/projects\/(?!empty)[^/]+/.test(pathname)) return {wide: true, fill: true};
  if (/^\/library\/[^/]+/.test(pathname)) return {wide: true, fill: true};
  if (/^\/migrations\/[^/]+/.test(pathname)) return {wide: true, fill: true};
  // List + analytics — wide, scroll naturally
  if (pathname === '/runs') return {wide: true, fill: false};
  if (pathname === '/library') return {wide: true, fill: false};
  if (pathname === '/migrations') return {wide: true, fill: false};
  if (pathname === '/insights') return {wide: true, fill: false};
  if (pathname === '/team') return {wide: true, fill: false};
  if (pathname === '/trust') return {wide: true, fill: false};
  if (pathname === '/billing') return {wide: true, fill: false};
  if (pathname === '/exec') return {wide: true, fill: false};
  // 2026-05-14 (responsive UX parity audit) — operational + admin
  // surfaces were rendering in the 960px-narrow centered column on
  // 1280px+ desktops, leaving ~1000px of viewport empty AND forcing
  // their `min-w-[600px]` data tables to horizontal-scroll within a
  // ~280px usable area (Status column truncated to "PEND…" / "ACTIV…").
  // Promote them to wide+fill so the dense console reads at desktop
  // widths and the inner tables get room to lay out without clipping.
  if (pathname === '/admin' || pathname.startsWith('/admin/')) return {wide: true, fill: true};
  if (pathname === '/audit') return {wide: true, fill: true};
  if (pathname === '/accounting') return {wide: true, fill: true};
  if (pathname === '/invoices') return {wide: true, fill: true};
  if (pathname === '/deployments') return {wide: true, fill: true};
  if (pathname === '/settings' || pathname.startsWith('/settings/'))
    return {wide: true, fill: false};
  if (pathname === '/integrations') return {wide: true, fill: false};
  if (pathname === '/pricing') return {wide: true, fill: false};
  if (pathname.startsWith('/org/')) return {wide: true, fill: true};
  // Default — narrow centered (Auth, EmptyProject, Landing/Composer).
  return {wide: false, fill: false};
}

export default function Shell({withTopBar = true}: {withTopBar?: boolean}) {
  const location = useLocation();
  const layout = classifyRoute(location.pathname);
  useEffect(() => {
    const root = document.documentElement;
    // R-307 (Wave 6): just mark v3-active. Theme attribute stays under
    // the user's control via the v2 ThemeToggle (useUiStore).
    root.classList.add('v3-active');
    return () => {
      root.classList.remove('v3-active');
    };
  }, []);

  const mainClass = [
    'v3-main',
    layout.wide ? 'v3-main--wide' : '',
    layout.fill ? 'v3-main--fill' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className='v3-root'>
      {withTopBar && <TopBar />}
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
      <main id='main' tabIndex={-1} className={mainClass}>
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
      <BottomNav />
    </div>
  );
}
