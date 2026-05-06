import {useEffect} from 'react';
import {Routes, Route, Navigate, useLocation} from 'react-router-dom';
// Single-surface retirement (2026-05-06) — AppLayout is gone. Every
// protected route now mounts under v3 <Shell>; admin/finance pages
// reach via the avatar-menu dropdown.
import ProtectedRoute from '@/components/auth/ProtectedRoute';

/**
 * R-342 — single redirect handler for every legacy `/v3/*` URL.
 *
 * Before R-342 the v3-shell surfaces lived at `/v3/{home,settings,
 * library,migrations,p/:slug,...}`.  Unification dropped the `/v3/`
 * prefix; this component maps any `/v3/X` request to its new
 * unified URL and Navigate-replaces.  Existing bookmarks, email
 * deep-links, marketing copy + shared run URLs keep working.
 *
 * Path remapping rules (most-specific first):
 *   /projects/empty           → /projects/empty
 *   /projects/:slug/runs/:run    → /projects/:slug/runs/:run
 *   /projects/:slug           → /projects/:slug
 *   /claim              → /claim
 *   /           → /
 *   /home              → /home
 *   /v3/X                 → /X
 */
function LegacyUrlRedirect() {
  const location = useLocation();
  let path = location.pathname.replace(/^\/v3/, '');
  // /projects/empty → /projects/empty (must come before the catch-all
  // /projects/ rule since `empty` is a literal segment, not a slug).
  path = path.replace(/^\/p\/empty\b/, '/projects/empty');
  // /projects/:slug/runs/:runShort → /projects/:slug/runs/:runShort
  path = path.replace(/^\/p\/([^/]+)\/r\//, '/projects/$1/runs/');
  // /projects/:slug → /projects/:slug (catch-all for the project root).
  path = path.replace(/^\/p\//, '/projects/');
  // /claim → /claim (the post-signup spec-claim screen renamed).
  if (path === '/auth') path = '/claim';
  // / folded into root (the marketing site is the landing).
  if (path === '/landing') path = '/';
  // Empty path → /home so the dispatcher fires.
  if (path === '' || path === '/') path = '/home';
  return <Navigate to={path + location.search + location.hash} replace />;
}

// Main app pages
// Single-surface retirement (2026-05-06) — RunsPage deleted. The flat
// run list across all projects belonged to the v2 sidebar surface;
// users now navigate runs through their project home or the per-
// project Launches list inside ProjectDashboardPage. /runs redirects
// to /home (HomeRedirectPage dispatches to first project).
import ExecSummaryPage from '@/pages/ExecSummaryPage';
import PricingPage from '@/pages/PricingPage';
// Tier-coherence: PlansPage + BillingPage no longer mounted — v2 routes
// `/plans` + `/billing` redirect to /pricing + /billing.
import IntegrationsPage from '@/pages/IntegrationsPage';
import SettingsPage from '@/pages/SettingsPage';

// Auth pages (public)
import LoginPage from '@/pages/auth/LoginPage';
import RegisterPage from '@/pages/auth/RegisterPage';
import ForgotPasswordPage from '@/pages/auth/ForgotPasswordPage';
import ResetPasswordPage from '@/pages/auth/ResetPasswordPage';
import VerifyEmailPage from '@/pages/auth/VerifyEmailPage';
import AcceptInvitePage from '@/pages/auth/AcceptInvitePage';
import AuthCallbackPage from '@/pages/auth/AuthCallbackPage';

// Org pages
import OrgSettingsPage from '@/pages/org/OrgSettingsPage';
import OrgProjectsPage from '@/pages/org/OrgProjectsPage';

// Project pages (pilot v3.0)
import ProjectDashboardPage from '@/pages/project/ProjectDashboardPage';
// R-201 (Wave 4) — ProjectsListPage import removed; /projects now hard-redirects to /home.
// import ProjectsListPage from '@/pages/project/ProjectsListPage';
import ExecutionViewPage from '@/pages/execution/ExecutionViewPage';

// Admin pages
import AdminPage from '@/pages/admin/AdminPage';
import FeatureFlagsPage from '@/pages/admin/FeatureFlagsPage';
// [F-14] (audit Sprint 1 PR-1) — Placeholder pattern retired.
// Admin sub-pages now mount their actual components directly under v3 URLs;
// the AppLayout sidebar wrapper from ProtectedRoute keeps the chrome
// consistent with /admin and /admin/feature-flags which always rendered live.
import OrgAdminDashboard from '@/pages/admin/OrgAdminDashboard';
import TeamMemberDashboard from '@/pages/admin/TeamMemberDashboard';
import PricingManager from '@/pages/admin/PricingManager';
import TierLifecyclePage from '@/pages/admin/TierLifecyclePage';

// Audit page
import AuditPage from '@/pages/AuditPage';

// Finance pages
import InvoicesPage from '@/pages/InvoicesPage';
import AccountingPage from '@/pages/AccountingPage';

// Deploy page
import DeploymentsPage from '@/pages/DeploymentsPage';

// Public pages
import SharedReportPage from '@/pages/SharedReportPage';
import SharedProjectPage from '@/pages/SharedProjectPage';
import TermsPage from '@/pages/TermsPage';
import PrivacyPage from '@/pages/PrivacyPage';
import DpaPage from '@/pages/DpaPage';

// Wave 2.2 — onboarding
import WelcomePage from '@/pages/onboarding/WelcomePage';
import RunsNewLanding from '@/pages/onboarding/RunsNewLanding';

// v3 (R-101+) — greenfield UX redesign, mounted under /v3/* without
// touching v2 routes. Cohabitation: per-screen ship, per-persona dogfood.
import Shell from '@/v3/components/Shell';
import HomeRedirectPage from '@/v3/pages/HomeRedirectPage';
// R-341 (2026-04-29): LandingPage route converted to a Navigate redirect
// to root (the static marketing site at executespec.ai is now the
// canonical public landing surface).  The component file stays in the
// codebase so direct deep-links + e2e tests keep their reference, but
// it no longer mounts as a user-facing page.
// import LandingPage from '@/v3/pages/LandingPage';
import AuthPage from '@/v3/pages/AuthPage';
import ProjectHomePage from '@/v3/pages/ProjectHomePage';
import RunDetailPage from '@/v3/pages/RunDetailPage';
import BillingPage from '@/v3/pages/BillingPage';
import TeamPage from '@/v3/pages/TeamPage';
import TrustPage from '@/v3/pages/TrustPage';
import InsightsPage from '@/v3/pages/InsightsPage';
// R-340 — Migrations product W5 frontend (gallery + detail).
import MigrationsPage from '@/v3/pages/MigrationsPage';
import MigrationDetailPage from '@/v3/pages/MigrationDetailPage';
// R-330 — Readymade Solutions library W4 frontend (gallery + detail).
import LibraryPage from '@/v3/pages/LibraryPage';
import LibraryDetailPage from '@/v3/pages/LibraryDetailPage';
// R-342 (2026-04-29): OptInGate removed — unification of v2/v3 URLs
// supersedes the opt-in dance.  The component file is kept as a stub so
// the import path doesn't break for any external test harnesses, but
// no route uses it anymore.
// import OptInGate from '@/v3/components/OptInGate';
// Selling-point #6 — public anonymized /exec snapshot for the
// PgM/CFO conversion path. Public, no auth, share-link-able.
import ExecDemoPage from '@/v3/pages/ExecDemoPage';
import EmptyProjectPage from '@/v3/pages/EmptyProjectPage';
// R-201 — Wave 4. "Coming to v3" placeholder for v2 admin routes whose
// v3 equivalent hasn't shipped yet (per cohabitation invariant in
// docs/strict-ux-thread-handoff.md §8 — surface-not-allowed renders
// an evaluate-then-commit panel, not 404).
// [F-14] Placeholder import removed; component file kept as a stub
// in case external test harnesses still reference it (parallel to the
// OptInGate pattern from R-342).

import {useAuthStore} from '@/stores/useAuthStore';
import {useKeyboardScroll} from '@/hooks/useKeyboardScroll';
import {initAnalytics, identify, page, reset as resetAnalytics} from '@/lib/analytics';

export default function App() {
  const {loadMe, user} = useAuthStore();

  // iOS Safari: scroll focused input into view when soft keyboard opens
  useKeyboardScroll();

  useEffect(() => {
    // Wave 2.1 — initialize PostHog once. Safe no-op when VITE_POSTHOG_API_KEY
    // is unset.
    initAnalytics();
    // Try to restore session from access_token or refresh cookie on mount
    loadMe();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Wave 2.1 — identify once the user record resolves; reset on logout.
  useEffect(() => {
    if (user?.id) {
      identify(user.id, {
        tier: user.tier,
        orgId: user.orgId ?? null,
        orgRole: user.orgRole ?? null,
        viewMode: user.viewMode ?? null,
      });
    } else {
      resetAnalytics();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.id, user?.tier, user?.orgId]);

  // Wave 2.1 — lightweight pageview tracking. Routes are controlled by
  // react-router; re-trigger on path changes via location.pathname.
  useEffect(() => {
    page(window.location.pathname);
    const handler = () => page(window.location.pathname);
    window.addEventListener('popstate', handler);
    return () => window.removeEventListener('popstate', handler);
  }, []);

  return (
    <>
      {/* [A-01] (audit Sprint 4, 2026-05-01) — Skip to main content link.
          WCAG 2.4.1 (Bypass Blocks, Level A). Renders as the first
          focusable element on every page; visually hidden until focused
          (Tab from the URL bar) so it doesn't disturb the visual design.
          Both Shell <main> and AppLayout <main> use #main as the
          jump target. */}
      <a
        href='#main'
        className='skip-link'
        style={{
          position: 'absolute',
          top: 8,
          left: 8,
          zIndex: 100,
          padding: '8px 16px',
          background: 'var(--accent-blue, #5B8DEF)',
          color: '#fff',
          borderRadius: 6,
          fontWeight: 600,
          fontSize: 14,
          textDecoration: 'none',
          // Visually hide until focused — standard "skip-link" pattern.
          transform: 'translateY(-200%)',
          transition: 'transform 0.15s ease',
        }}
        onFocus={(e) => {
          e.currentTarget.style.transform = 'translateY(0)';
        }}
        onBlur={(e) => {
          e.currentTarget.style.transform = 'translateY(-200%)';
        }}
      >
        Skip to main content
      </a>
      <Routes>
        {/* ── Public auth routes ── */}
        <Route path='/login' element={<LoginPage />} />
        <Route path='/register' element={<RegisterPage />} />
        <Route path='/forgot-password' element={<ForgotPasswordPage />} />
        <Route path='/reset-password' element={<ResetPasswordPage />} />
        <Route path='/verify-email' element={<VerifyEmailPage />} />
        <Route path='/accept-invite' element={<AcceptInvitePage />} />
        <Route path='/auth/callback' element={<AuthCallbackPage />} />

        {/* ── Public shared pages ── */}
        <Route path='/reports/shared/:shareToken' element={<SharedReportPage />} />
        {/* X-13 (UX audit, 2026-05-03) — read-only project snapshot share.
            Frontend-only stub; the backend endpoint
            GET /api/projects/snapshot/{token} is not yet implemented. The
            page renders a "coming soon" notice until it lands, so the URL
            pattern is reserved + bookmarkable from day one. See
            docs/audits/enterprise-ux-2026-05-03.md (X-13). */}
        <Route path='/projects/snapshot/:token' element={<SharedProjectPage />} />

        {/* ── Public legal pages ── */}
        <Route path='/terms' element={<TermsPage />} />
        <Route path='/privacy' element={<PrivacyPage />} />
        <Route path='/dpa' element={<DpaPage />} />

        {/* ── Greenfield surfaces — clean URLs without the /v3/ prefix ──
          R-342 (2026-04-29): unified the URL surface.  The Shell-wrapped
          routes (top-bar layout) used to live under `/v3/*`; now they
          live at root paths.  Where v2 had a competing route on the same
          URL (settings, billing, integrations, pricing), the v3
          implementation wins — v2 versions of those pages are dropped
          from the protected block below.  v2-only admin surfaces (audit,
          accounting, deployments, invoices, exec, admin/*) keep their
          AppLayout sidebar and stay reachable.

          Every `/v3/*` URL still routes via the `<LegacyUrlRedirect>`
          catch-all below so existing bookmarks, marketing copy, email
          deep-links, and shared run URLs keep working. */}
        <Route element={<Shell />}>
          {/* R-310 (Wave 7) — explicit empty-state route MUST come before
            the catch-all `projects/:slug` so ProjectHomePage doesn't fire
            a 404 GET on /api/projects/empty. */}
          <Route path='/projects/empty' element={<EmptyProjectPage />} />
          <Route path='/projects/:slug' element={<ProjectHomePage />} />
          <Route path='/projects/:slug/runs/:runShort' element={<RunDetailPage />} />
          <Route path='/settings' element={<SettingsPage />} />
          {/* R-141 (deferred Wave-1 follow-up, shipped 2026-05-02) — full
              key-management surface (GitHub PAT, AI provider keys, Bitbucket,
              JIRA, Confluence). SettingsPage is a read-only summary; this is
              the actual form page. Deep-linkable via `#github`, `#api-keys`,
              `#bitbucket`, `#jira`, `#confluence`. */}
          <Route path='/settings/manage' element={<SettingsPage />} />
          <Route path='/billing' element={<BillingPage />} />
          <Route path='/pricing' element={<PricingPage />} />
          <Route path='/integrations' element={<IntegrationsPage />} />
          <Route path='/team' element={<TeamPage />} />
          <Route path='/trust' element={<TrustPage />} />
          <Route path='/insights' element={<InsightsPage />} />
          {/* R-340 — Migrations product gallery + detail. */}
          <Route path='/migrations' element={<MigrationsPage />} />
          <Route path='/migrations/:slug' element={<MigrationDetailPage />} />
          {/* R-330 — Readymade Solutions library gallery + detail. */}
          <Route path='/library' element={<LibraryPage />} />
          <Route path='/library/:slug' element={<LibraryDetailPage />} />
          {/* Selling-point #6 — public exec snapshot. No auth required. */}
          <Route path='/exec-demo' element={<ExecDemoPage />} />
          {/* HomeRedirectPage — the silent dispatcher.  /home for users
            who deep-link to it; the bare `/` is handled by ProtectedRoute
            (anon → /login) + the protected index Route below (authed →
            HomeRedirectPage dispatches to /projects/{first} or /projects/empty). */}
          <Route path='/home' element={<HomeRedirectPage />} />
          {/* Post-signup spec-claim screen (was /claim). */}
          <Route path='/claim' element={<AuthPage />} />
        </Route>

        {/* Legacy /v3/* paths — redirect to the unified URLs.  Single
          catch-all keeps existing bookmarks + marketing copy working. */}
        <Route path='/v3/*' element={<LegacyUrlRedirect />} />

        {/* ── Protected app routes ──
         * Single-surface retirement (2026-05-06) — every protected
         * route now mounts under <Shell> (the v3 top-bar chrome).
         * AppLayout is gone; the sidebar/Header/v2-CommandPalette
         * are deleted. The avatar dropdown surfaces every admin /
         * finance page that previously lived under the sidebar
         * (Insights, Audit log, Invoices, Accounting, Executive
         * summary, Admin home, Feature flags, Tier lifecycle). */}
        <Route element={<ProtectedRoute />}>
          {/* Welcome/onboarding stays chrome-free (no Shell wrapper)
              so the first-paint hero owns the full viewport. */}
          <Route path='welcome' element={<WelcomePage />} />

          <Route element={<Shell />}>
            <Route path='runs/new' element={<Navigate to='/home' replace />} />
            <Route path='runs/new/sandbox' element={<RunsNewLanding />} />
            {/* /legacy/billing — soft landing for grandfathered PRO
                subscribers whose tier-state-changed email links here. */}
            <Route path='legacy/billing' element={<Navigate to='/billing' replace />} />
            <Route index element={<Navigate to='/home' replace />} />
            {/* /runs is now a redirect — the flat-list-of-all-runs
                view (RunsPage) was deleted with the v2 chrome. Users
                navigate runs through their project home or the per-
                project Launches list inside ProjectDashboardPage. */}
            <Route path='runs' element={<Navigate to='/home' replace />} />
            <Route path='dashboard' element={<Navigate to='/insights' replace />} />
            <Route path='plans' element={<Navigate to='/pricing' replace />} />
            <Route path='admin/model-rates' element={<PricingPage />} />

            {/* Admin / finance surfaces — same pages as before, now
                rendered under v3 chrome instead of the deleted
                AppLayout sidebar. */}
            <Route path='invoices' element={<InvoicesPage />} />
            <Route path='accounting' element={<AccountingPage />} />
            <Route path='audit' element={<AuditPage />} />
            <Route path='governance/audit' element={<Navigate to='/audit' replace />} />
            <Route path='deployments' element={<DeploymentsPage />} />
            <Route path='exec' element={<ExecSummaryPage />} />

            {/* Org pages */}
            <Route path='org/settings' element={<OrgSettingsPage />} />
            <Route path='org/projects' element={<OrgProjectsPage />} />
            <Route path='projects' element={<Navigate to='/home' replace />} />
            {/* `/projects/:id/dashboard` — dense KPI / multi-tab view.
                Same dataset as the chat-thread `/projects/:slug` page;
                we keep both for now (different jobs) and may collapse
                them after GA once usage tells us which one users
                actually live in. */}
            <Route path='projects/:projectId/dashboard' element={<ProjectDashboardPage />} />
            <Route
              path='projects/:projectId/activity'
              element={<Navigate to='../dashboard?drawer=activity' replace relative='path' />}
            />
            <Route path='projects/:projectId/executions/new' element={<ExecutionViewPage />} />
            <Route
              path='projects/:projectId/executions/:executionId'
              element={<ExecutionViewPage />}
            />

            {/* Admin */}
            <Route path='admin' element={<AdminPage />} />
            <Route path='admin/analytics' element={<OrgAdminDashboard />} />
            <Route path='team/:userId' element={<TeamMemberDashboard />} />
            <Route path='admin/pricing' element={<PricingManager />} />
            <Route path='admin/feature-flags' element={<FeatureFlagsPage />} />
            <Route path='admin/tiers' element={<TierLifecyclePage />} />
            <Route path='admin/*' element={<AdminPage />} />

            <Route path='*' element={<Navigate to='/' replace />} />
          </Route>
        </Route>
      </Routes>
    </>
  );
}
