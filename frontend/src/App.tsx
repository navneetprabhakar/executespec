import { useEffect } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from '@/components/layout/AppLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';

// Main app pages
import RunsPage from '@/pages/RunsPage';
// R-201 (Wave 4) — DashboardPage import removed; /dashboard now hard-redirects to /v3/insights.
// import DashboardPage from '@/pages/DashboardPage';
import ExecSummaryPage from '@/pages/ExecSummaryPage';
import PricingPage from '@/pages/PricingPage';
// Tier-coherence: PlansPage + BillingPage no longer mounted — v2 routes
// `/plans` + `/billing` redirect to /v3/pricing + /v3/billing.
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
// R-201 (Wave 4) — ProjectsListPage import removed; /projects now hard-redirects to /v3/home.
// import ProjectsListPage from '@/pages/project/ProjectsListPage';
import ExecutionViewPage from '@/pages/execution/ExecutionViewPage';

// Admin pages
import AdminPage from '@/pages/admin/AdminPage';
import FeatureFlagsPage from '@/pages/admin/FeatureFlagsPage';
// R-201 (Wave 4) — admin pages replaced with V3Placeholder until v3 admin
// surface ships. Imports kept commented for fast restore once R-25x lands.
// import OrgAdminDashboard from '@/pages/admin/OrgAdminDashboard';
import TeamMemberDashboard from '@/pages/admin/TeamMemberDashboard';
// import PricingManager from '@/pages/admin/PricingManager';
// import TierLifecyclePage from '@/pages/admin/TierLifecyclePage';

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
import V3Landing from '@/v3/pages/V3Landing';
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
import V2OptInGate from '@/v3/components/V2OptInGate';
// Selling-point #6 — public anonymized /exec snapshot for the
// PgM/CFO conversion path. Public, no auth, share-link-able.
import V3ExecDemo from '@/v3/pages/V3ExecDemo';
import V3EmptyProject from '@/v3/pages/V3EmptyProject';
// R-201 — Wave 4. "Coming to v3" placeholder for v2 admin routes whose
// v3 equivalent hasn't shipped yet (per cohabitation invariant in
// docs/strict-ux-thread-handoff.md §8 — surface-not-allowed renders
// an evaluate-then-commit panel, not 404).
import V3Placeholder from '@/v3/components/V3Placeholder';

import { useAuthStore } from '@/stores/useAuthStore';
import { useKeyboardScroll } from '@/hooks/useKeyboardScroll';
import { initAnalytics, identify, page, reset as resetAnalytics } from '@/lib/analytics';

export default function App() {
  const { loadMe, user } = useAuthStore();

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
    <Routes>
      {/* ── Public auth routes ── */}
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route path="/accept-invite" element={<AcceptInvitePage />} />
      <Route path="/auth/callback" element={<AuthCallbackPage />} />

      {/* ── Public shared pages ── */}
      <Route path="/reports/shared/:shareToken" element={<SharedReportPage />} />

      {/* ── Public legal pages ── */}
      <Route path="/terms" element={<TermsPage />} />
      <Route path="/privacy" element={<PrivacyPage />} />
      <Route path="/dpa" element={<DpaPage />} />

      {/* ── v3 greenfield surfaces (R-101+) ──
          Mounted outside AppLayout so v3 owns its own chrome (no v2 sidebar,
          no v2 header). Auth gating per-screen (some v3 routes are public:
          /v3/landing, /v3/pricing, /v3/trust, /v3/auth). */}
      <Route path="/v3" element={<V3Shell />}>
        <Route index element={<Navigate to="home" replace />} />
        <Route path="home" element={<V3HomeRedirect />} />
        <Route path="landing" element={<V3Landing />} />
        <Route path="auth" element={<V3Auth />} />
        {/* R-310 (Wave 7) — explicit empty-state route. MUST come before
            the catch-all `p/:slug` so V3ProjectHome doesn't fire a 404 GET
            on /api/projects/empty (the F1 e2e walk's biggest "is this
            broken?" trigger). See docs/plans/wave-7-issue-list.md R-310. */}
        <Route path="p/empty" element={<V3EmptyProject />} />
        <Route path="p/:slug" element={<V3ProjectHome />} />
        <Route path="p/:slug/r/:runShort" element={<V3RunDetail />} />
        <Route path="settings" element={<V3Settings />} />
        <Route path="billing" element={<V3Billing />} />
        <Route path="pricing" element={<V3Pricing />} />
        <Route path="integrations" element={<V3Integrations />} />
        <Route path="team" element={<V3Team />} />
        <Route path="trust" element={<V3Trust />} />
        <Route path="insights" element={<V3Insights />} />
        {/* R-340 — Migrations product gallery + detail. */}
        <Route path="migrations" element={<V3Migrations />} />
        <Route path="migrations/:slug" element={<V3MigrationDetail />} />
        {/* R-330 — Readymade Solutions library gallery + detail. */}
        <Route path="library" element={<V3Library />} />
        <Route path="library/:slug" element={<V3LibraryDetail />} />
        {/* /v3/changelog route removed 2026-04-28 (concern 3 — page
            deleted; revisit later). Falls through to the SPA's catch-all
            redirect at "/*" → "/", which now lands anon visitors on
            /v3/landing. */}
        {/* Selling-point #6 — public exec snapshot. No auth required. */}
        <Route path="exec-demo" element={<V3ExecDemo />} />
      </Route>

      {/* ── Protected app routes ── */}
      <Route element={<ProtectedRoute />}>
        {/* Wave 2.2 — welcome/onboarding (chrome-free, before AppLayout). */}
        <Route path="welcome" element={<WelcomePage />} />

        {/* Wave 2.2 — sandbox → run-submit prefill landing (inside AppLayout for nav consistency) */}
        <Route element={<AppLayout />}>
          {/* R-201 (Wave 4) — /runs/new used to chain through /projects which
              then routed to v2 ProjectsListPage. Replaced with a hard-redirect
              to /v3/landing — that's the single composer-first entry point
              per the v3 redesign (greenfield doc §4.1 + §4.4). The chained
              redirect was the "ghost route" symptom in the fresh-eyes audit. */}
          <Route path="runs/new" element={<Navigate to="/v3/landing" replace />} />
          <Route path="runs/new/sandbox" element={<RunsNewLanding />} />
          {/* Phase 1.1 #9: /legacy/billing — soft landing for grandfathered
              PRO subscribers whose tier-state-changed email links here.
              Renders /plans with a query flag so the page can show a
              "your PRO plan still works" banner (non-blocking). */}
          {/* Tier-coherence: /plans is now a redirect; route legacy email
              links straight to /v3/billing where grandfathered subscribers
              can verify their plan + manage payment. */}
          <Route path="legacy/billing" element={<Navigate to="/v3/billing" replace />} />
          {/* R-180 — v2 → v3 opt-in redirect gate. When localStorage
              flag `v3-opt-in` === '1', these routes Navigate to the
              v3 equivalent. Otherwise the v2 element renders unchanged.
              See frontend/src/v3/lib/optIn.ts for the full mapping. */}
          <Route index element={<V2OptInGate><RunsPage /></V2OptInGate>} />
          {/* R-201 (Wave 4) — /dashboard hard-redirects to /v3/insights (the
              v3 dashboard equivalent). Kills the silent fall-through behaviour
              the fresh-eyes audit identified; matches the V2OptInGate
              redirect target in lib/optIn.ts so opt-in + non-opt-in users
              now share one destination. */}
          <Route path="dashboard" element={<Navigate to="/v3/insights" replace />} />
          {/* Tier-coherence: /pricing was an admin tool (per-1M-token model
              rates). Moved to /admin/model-rates. /plans + /billing both
              redirect to /v3/pricing + /v3/billing — single source of truth
              for tier display. */}
          <Route path="pricing" element={<Navigate to="/v3/pricing" replace />} />
          <Route path="plans" element={<Navigate to="/v3/pricing" replace />} />
          <Route path="billing" element={<Navigate to="/v3/billing" replace />} />
          <Route path="admin/model-rates" element={<PricingPage />} />
          <Route path="integrations" element={<V2OptInGate><IntegrationsPage /></V2OptInGate>} />
          <Route path="invoices" element={<InvoicesPage />} />
          <Route path="accounting" element={<AccountingPage />} />
          <Route path="audit" element={<AuditPage />} />
          {/* M-013 (Wave 2) — canonical "governance" alias for the unified
              audit + activity page. Per parked decision #4, V22 (admin
              audit) and V133 (project activity) live in separate tables
              but render at one URL. */}
          <Route path="governance/audit" element={<AuditPage />} />
          <Route path="deployments" element={<DeploymentsPage />} />
          <Route path="settings" element={<V2OptInGate><SettingsPage /></V2OptInGate>} />
          {/* v2.5.14 Phase 3.1 — executive summary (business-owner persona). */}
          <Route path="exec" element={<V2OptInGate><ExecSummaryPage /></V2OptInGate>} />

          {/* Org pages */}
          <Route path="org/settings" element={<OrgSettingsPage />} />
          <Route path="org/projects" element={<OrgProjectsPage />} />
          {/* R-201 (Wave 4) — /projects hard-redirects to /v3/home (the v3
              project surface; V3HomeRedirect picks the user's first project
              or the empty-state composer). Matches lib/optIn.ts target. */}
          <Route path="projects" element={<Navigate to="/v3/home" replace />} />
          {/* v2.5.12 — fallback so /projects/:id (without /dashboard) resolves.
              The API side lists projects at /api/projects/{id}/dashboard; users
              were hitting /projects/{id} and seeing a blank page because the
              route fell through. */}
          <Route path="projects/:projectId" element={<Navigate to="dashboard" replace />} />
          <Route path="projects/:projectId/dashboard" element={<ProjectDashboardPage />} />
          {/* M-034 (Wave 2) — deep-linkable URL for the project activity
              feed. Opens the dashboard with ?drawer=activity pre-set so
              the existing MoreDrawer ActivityTab full-page mount picks
              up. Keeps the v2.6.4 IA cleanup intact (no new top tab). */}
          <Route path="projects/:projectId/activity" element={<Navigate to="../dashboard?drawer=activity" replace relative="path" />} />
          <Route path="projects/:projectId/executions/new" element={<ExecutionViewPage />} />
          <Route path="projects/:projectId/executions/:executionId" element={<ExecutionViewPage />} />

          {/* Admin */}
          <Route path="admin" element={<AdminPage />} />
          {/* R-201 (Wave 4) — admin/analytics, admin/tiers, admin/pricing
              had v2 components but the fresh-eyes audit observed silent
              fall-through to / (the runs page) for users hitting them
              directly. Until a v3 admin surface ships, render a "coming
              to v3" panel with an Open-in-v2 escape hatch — the v2
              component is still reachable per the cohabitation invariant
              (handoff §8). */}
          <Route
            path="admin/analytics"
            element={<V3Placeholder pageName="Org analytics" v2Fallback="/admin" v2RouteLabel="admin console" futureV3Route="/v3/insights" />}
          />
          {/* M-022 (Wave 3) — per-team-member dashboard for ORG_ADMIN. */}
          <Route path="team/:userId" element={<TeamMemberDashboard />} />
          <Route
            path="admin/pricing"
            element={<V3Placeholder pageName="Pricing manager" v2Fallback="/admin" v2RouteLabel="admin console" futureV3Route="/v3/pricing" />}
          />
          <Route path="admin/feature-flags" element={<FeatureFlagsPage />} />
          <Route
            path="admin/tiers"
            element={<V3Placeholder pageName="Tier lifecycle" v2Fallback="/admin" v2RouteLabel="admin console" futureV3Route="/v3/billing" />}
          />
          <Route path="admin/*" element={<AdminPage />} />

          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}
