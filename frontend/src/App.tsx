import {useEffect} from 'react';
import {Routes, Route, Navigate, useLocation} from 'react-router-dom';
import AppLayout from '@/components/layout/AppLayout';
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
function V3LegacyRedirect() {
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
import RunsPage from '@/pages/RunsPage';
// R-201 (Wave 4) — DashboardPage import removed; /dashboard now hard-redirects to /insights.
// import DashboardPage from '@/pages/DashboardPage';
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
// [F-14] (audit Sprint 1 PR-1) — V3Placeholder pattern retired.
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
import TermsPage from '@/pages/TermsPage';
import PrivacyPage from '@/pages/PrivacyPage';
import DpaPage from '@/pages/DpaPage';

// Wave 2.2 — onboarding
import WelcomePage from '@/pages/onboarding/WelcomePage';
import RunsNewLanding from '@/pages/onboarding/RunsNewLanding';

// v3 (R-101+) — greenfield UX redesign, mounted under /v3/* without
// touching v2 routes. Cohabitation: per-screen ship, per-persona dogfood.
import V3Shell from '@/v3/components/V3Shell';
import V3HomeRedirect from '@/v3/pages/V3HomeRedirect';
// R-341 (2026-04-29): V3Landing route converted to a Navigate redirect
// to root (the static marketing site at executespec.ai is now the
// canonical public landing surface).  The component file stays in the
// codebase so direct deep-links + e2e tests keep their reference, but
// it no longer mounts as a user-facing page.
// import V3Landing from '@/v3/pages/V3Landing';
import V3Auth from '@/v3/pages/V3Auth';
import V3ProjectHome from '@/v3/pages/V3ProjectHome';
import V3RunDetail from '@/v3/pages/V3RunDetail';
import V3Settings from '@/v3/pages/V3Settings';
import V3Billing from '@/v3/pages/V3Billing';
import V3Pricing from '@/v3/pages/V3Pricing';
import V3Integrations from '@/v3/pages/V3Integrations';
import V3Team from '@/v3/pages/V3Team';
import V3Trust from '@/v3/pages/V3Trust';
import V3Insights from '@/v3/pages/V3Insights';
// R-340 — Migrations product W5 frontend (gallery + detail).
import V3Migrations from '@/v3/pages/V3Migrations';
import V3MigrationDetail from '@/v3/pages/V3MigrationDetail';
// R-330 — Readymade Solutions library W4 frontend (gallery + detail).
import V3Library from '@/v3/pages/V3Library';
import V3LibraryDetail from '@/v3/pages/V3LibraryDetail';
// R-342 (2026-04-29): V2OptInGate removed — unification of v2/v3 URLs
// supersedes the opt-in dance.  The component file is kept as a stub so
// the import path doesn't break for any external test harnesses, but
// no route uses it anymore.
// import V2OptInGate from '@/v3/components/V2OptInGate';
// Selling-point #6 — public anonymized /exec snapshot for the
// PgM/CFO conversion path. Public, no auth, share-link-able.
import V3ExecDemo from '@/v3/pages/V3ExecDemo';
import V3EmptyProject from '@/v3/pages/V3EmptyProject';
// R-201 — Wave 4. "Coming to v3" placeholder for v2 admin routes whose
// v3 equivalent hasn't shipped yet (per cohabitation invariant in
// docs/strict-ux-thread-handoff.md §8 — surface-not-allowed renders
// an evaluate-then-commit panel, not 404).
// [F-14] V3Placeholder import removed; component file kept as a stub
// in case external test harnesses still reference it (parallel to the
// V2OptInGate pattern from R-342).

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
          Both V3Shell <main> and AppLayout <main> use #main as the
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

        {/* ── Public legal pages ── */}
        <Route path='/terms' element={<TermsPage />} />
        <Route path='/privacy' element={<PrivacyPage />} />
        <Route path='/dpa' element={<DpaPage />} />

        {/* ── Greenfield surfaces — clean URLs without the /v3/ prefix ──
          R-342 (2026-04-29): unified the URL surface.  The V3Shell-wrapped
          routes (top-bar layout) used to live under `/v3/*`; now they
          live at root paths.  Where v2 had a competing route on the same
          URL (settings, billing, integrations, pricing), the v3
          implementation wins — v2 versions of those pages are dropped
          from the protected block below.  v2-only admin surfaces (audit,
          accounting, deployments, invoices, exec, admin/*) keep their
          AppLayout sidebar and stay reachable.

          Every `/v3/*` URL still routes via the `<V3LegacyRedirect>`
          catch-all below so existing bookmarks, marketing copy, email
          deep-links, and shared run URLs keep working. */}
        <Route element={<V3Shell />}>
          {/* R-310 (Wave 7) — explicit empty-state route MUST come before
            the catch-all `projects/:slug` so V3ProjectHome doesn't fire
            a 404 GET on /api/projects/empty. */}
          <Route path='/projects/empty' element={<V3EmptyProject />} />
          <Route path='/projects/:slug' element={<V3ProjectHome />} />
          <Route path='/projects/:slug/runs/:runShort' element={<V3RunDetail />} />
          <Route path='/settings' element={<V3Settings />} />
          {/* R-141 (deferred Wave-1 follow-up, shipped 2026-05-02) — full
              key-management surface (GitHub PAT, AI provider keys, Bitbucket,
              JIRA, Confluence). V3Settings is a read-only summary; this is
              the actual form page. Deep-linkable via `#github`, `#api-keys`,
              `#bitbucket`, `#jira`, `#confluence`. */}
          <Route path='/settings/manage' element={<SettingsPage />} />
          <Route path='/billing' element={<V3Billing />} />
          <Route path='/pricing' element={<V3Pricing />} />
          <Route path='/integrations' element={<V3Integrations />} />
          <Route path='/team' element={<V3Team />} />
          <Route path='/trust' element={<V3Trust />} />
          <Route path='/insights' element={<V3Insights />} />
          {/* R-340 — Migrations product gallery + detail. */}
          <Route path='/migrations' element={<V3Migrations />} />
          <Route path='/migrations/:slug' element={<V3MigrationDetail />} />
          {/* R-330 — Readymade Solutions library gallery + detail. */}
          <Route path='/library' element={<V3Library />} />
          <Route path='/library/:slug' element={<V3LibraryDetail />} />
          {/* Selling-point #6 — public exec snapshot. No auth required. */}
          <Route path='/exec-demo' element={<V3ExecDemo />} />
          {/* V3HomeRedirect — the silent dispatcher.  /home for users
            who deep-link to it; the bare `/` is handled by ProtectedRoute
            (anon → /login) + the protected index Route below (authed →
            V3HomeRedirect dispatches to /projects/{first} or /projects/empty). */}
          <Route path='/home' element={<V3HomeRedirect />} />
          {/* Post-signup spec-claim screen (was /claim). */}
          <Route path='/claim' element={<V3Auth />} />
        </Route>

        {/* Legacy /v3/* paths — redirect to the unified URLs.  Single
          catch-all keeps existing bookmarks + marketing copy working. */}
        <Route path='/v3/*' element={<V3LegacyRedirect />} />

        {/* ── Protected app routes ── */}
        <Route element={<ProtectedRoute />}>
          {/* Wave 2.2 — welcome/onboarding (chrome-free, before AppLayout). */}
          <Route path='welcome' element={<WelcomePage />} />

          {/* Wave 2.2 — sandbox → run-submit prefill landing (inside AppLayout for nav consistency) */}
          <Route element={<AppLayout />}>
            {/* R-201 (Wave 4) — /runs/new used to chain through /projects which
              then routed to v2 ProjectsListPage.
              R-341 (2026-04-29): the / redirect target was
              retired (V3Landing is now a redirect to root since the
              marketing site is the canonical landing).  This route is
              auth-gated (inside the ProtectedRoute block) so an authed
              user who hits /runs/new has just typed it into the URL bar
              with intent — send them to the v3 home which dispatches
              to their first project.  Anon hits would have already been
              caught by ProtectedRoute and bounced to /login. */}
            {/* R-342 (2026-04-29): unified URLs — /runs/new now sends users
              to the home dispatcher (which lands on the user's first
              project + composer). */}
            <Route path='runs/new' element={<Navigate to='/home' replace />} />
            <Route path='runs/new/sandbox' element={<RunsNewLanding />} />
            {/* Phase 1.1 #9: /legacy/billing — soft landing for grandfathered
              PRO subscribers whose tier-state-changed email links here. */}
            <Route path='legacy/billing' element={<Navigate to='/billing' replace />} />
            {/* R-342 — v2 RunsPage stays at /runs (the legacy admin/dev
              run-list view).  The bare `/` is now ALSO the home
              dispatcher (V3HomeRedirect) so authed users with projects
              land on their first project, not the v2 runs list. */}
            <Route index element={<Navigate to='/home' replace />} />
            <Route path='runs' element={<RunsPage />} />
            {/* /dashboard → /insights (the v3 dashboard equivalent; same
              URL the AppLayout sidebar links to today). */}
            <Route path='dashboard' element={<Navigate to='/insights' replace />} />
            {/* Legacy redirects — preserve old bookmarks. */}
            <Route path='plans' element={<Navigate to='/pricing' replace />} />
            <Route path='admin/model-rates' element={<PricingPage />} />
            {/* v2-only admin / finance surfaces (no v3 equivalents — keep
              the AppLayout sidebar shell for these routes). */}
            <Route path='invoices' element={<InvoicesPage />} />
            <Route path='accounting' element={<AccountingPage />} />
            <Route path='audit' element={<AuditPage />} />
            {/* [F-26] (audit Sprint 1 PR-1) — collapse the duplicate.
              `/audit` is canonical; `/governance/audit` redirects.
              Was: both URLs rendered the same component (two surfaces,
              one component, drift hazard). Now: one URL, one redirect. */}
            <Route path='governance/audit' element={<Navigate to='/audit' replace />} />
            <Route path='deployments' element={<DeploymentsPage />} />
            {/* v2.5.14 Phase 3.1 — executive summary kept on the v2 sidebar
              shell.  /insights is the v3 equivalent; /exec stays as the
              admin-flavoured cost-breakdown view. */}
            <Route path='exec' element={<ExecSummaryPage />} />

            {/* Org pages */}
            <Route path='org/settings' element={<OrgSettingsPage />} />
            <Route path='org/projects' element={<OrgProjectsPage />} />
            {/* `/projects` redirects to /home so the user lands on the
              dispatcher (first project) instead of an empty list page.
              For project-management UI (members, repos), /org/projects
              stays as the explicit URL. */}
            <Route path='projects' element={<Navigate to='/home' replace />} />
            {/* `/projects/:id/dashboard` — v2 multi-tab project dashboard.
              Kept at this URL so existing bookmarks survive; the unified
              `/projects/:slug` URL now serves V3ProjectHome (top-bar
              chrome).  Long-term: port the dashboard's panels to the
              v3 project surface and retire this route. */}
            <Route path='projects/:projectId/dashboard' element={<ProjectDashboardPage />} />
            {/* M-034 (Wave 2) — deep-linkable URL for the project activity
              feed. Opens the dashboard with ?drawer=activity pre-set so
              the existing MoreDrawer ActivityTab full-page mount picks
              up. Keeps the v2.6.4 IA cleanup intact (no new top tab). */}
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
            {/* [F-14] (audit Sprint 1 PR-1) — V3Placeholder retired.
              admin/analytics, admin/tiers, admin/pricing now mount their
              actual v2 components directly. The placeholder pattern was
              shipping a credibility tax (SUPER_ADMIN clicks "Tier Lifecycle"
              → sees a "coming to v3" card with an Open-in-v2 escape hatch
              → has to click again). Direct mount restores parity with
              admin/feature-flags which always rendered the live page. */}
            <Route path='admin/analytics' element={<OrgAdminDashboard />} />
            {/* M-022 (Wave 3) — per-team-member dashboard for ORG_ADMIN. */}
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
