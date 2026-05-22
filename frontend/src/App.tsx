import {useEffect} from 'react';
import {Routes, Route, Navigate, useLocation} from 'react-router-dom';
import ProtectedRoute from '@/signal/components/auth/ProtectedRoute';

// Chrome A/B switch (2026-05-22 redesign). uiMode picks the production Signal
// shell or the new VS Code-style IDE workspace; the IDE renders its OWN core
// surfaces (not wrapped Signal pages), with non-core routes transitionally
// reusing the Signal pages until they're rebuilt.
import SignalShell from '@/signal/components/Shell';
import IdeWorkspace from '@/ide/IdeWorkspace';
import {IdeWelcome, IdeCompose, IdeLive} from '@/ide/IdeSurfaces';
import IdeRunResult from '@/ide/IdeRunResult';
import IdeProject from '@/ide/IdeProject';
import IdeProjects from '@/ide/IdeProjects';
import IdePlan from '@/ide/IdePlan';
import {IdeRuns, IdeAccount} from '@/ide/IdeMore';
import {useUiStore} from '@/stores/useUiStore';
import * as api from '@/lib/api';

// Auth + onboarding
import {LoginPage, SignupPage, VerifyPage, WelcomePage} from '@/signal/pages/AuthPages';
import AuthCallbackPage from '@/signal/pages/AuthCallbackPage';

// Run loop — each in its own file with default export
import HomePage from '@/signal/pages/HomePage';
import AllRunsPage from '@/signal/pages/AllRunsPage';
import ComposePage from '@/signal/pages/ComposePage';
import PlanPage from '@/signal/pages/PlanPage';
import LivePage from '@/signal/pages/LivePage';
import ResultPage from '@/signal/pages/ResultPage';

// Projects
import ProjectsPage from '@/signal/pages/ProjectsPage';
import ProjectPulsePage from '@/signal/pages/project/ProjectPulsePage';
import ProjectReposPage from '@/signal/pages/project/ProjectReposPage';
import ProjectRunsPage from '@/signal/pages/project/ProjectRunsPage';
import ProjectCodePage from '@/signal/pages/project/ProjectCodePage';
import ProjectChatPage from '@/signal/pages/project/ProjectChatPage';
import ProjectActivityPage from '@/signal/pages/project/ProjectActivityPage';
import ProjectMembersPage from '@/signal/pages/project/ProjectMembersPage';
import ProjectArchitecturePage from '@/signal/pages/project/ProjectArchitecturePage';
import ProjectSettingsPage from '@/signal/pages/project/ProjectSettingsPage';
import ProjectComposePage from '@/signal/pages/project/ProjectComposePage';

// Account + billing
import AccountPage from '@/signal/pages/account/AccountPage';
import BillingPage from '@/signal/pages/account/BillingPage';
import PlansPage from '@/signal/pages/account/PlansPage';
import {
  TopupPage,
  IntegrationsPage,
  ApiKeysPage,
  OrgMembersPage,
} from '@/signal/pages/account/MiscAccountPages';

// Cross-cutting + admin
import {
  NotificationsPage,
  SearchPage,
  MorePage,
  AdminPage,
  SharedPage,
} from '@/signal/pages/MiscPages';

// Extras (Library, Migrations, etc.)
import {
  InsightsPage,
  LibraryPage,
  LibraryDetailPage,
  MigrationsPage,
  MigrationDetailPage,
  TrustPage,
  EmptyProjectPage,
  PublicPricingPage,
  ForgotPasswordPage,
  ResetPasswordPage,
  AcceptInvitePage,
  AuditLogPage,
  DeploymentsPage,
  SpecTemplatesPage,
} from '@/signal/pages/ExtraPages';
import {TermsPage, PrivacyPage, DpaPage, OrgSettingsPage} from '@/signal/pages/LegalPages';

// avoid an unused-import error for TopupPage (route uses a redirect)
void TopupPage;

/**
 * Legacy URL redirects so old bookmarks + marketing links keep working.
 * Every removed v2/v3/v4 path collapses to its Signal equivalent.
 */
function LegacyRedirect() {
  const location = useLocation();
  let path = location.pathname;
  // /signal/* prefix dropped — the app IS Signal now
  if (path.startsWith('/signal/')) path = path.replace(/^\/signal/, '');
  // v3-era prefixes
  path = path.replace(/^\/v3/, '');
  path = path.replace(/^\/p\/empty\b/, '/projects/empty');
  path = path.replace(/^\/p\/([^/]+)\/r\//, '/projects/$1/runs/');
  path = path.replace(/^\/p\//, '/projects/');
  path = path.replace(/^\/projects\/([^/]+)\/executions(\/.*)?$/, '/projects/$1');
  // v2 routes folded into Signal equivalents
  if (path === '/dashboard') path = '/insights';
  if (path === '/legacy/billing') path = '/billing';
  if (path === '/exec' || path === '/exec-demo') path = '/insights';
  if (path === '/accounting' || path === '/invoices') path = '/billing';
  if (path === '/team') path = '/org-members';
  if (path === '/org/projects') path = '/projects';
  if (path === '/governance/audit') path = '/audit';
  if (path === '/runs/new') path = '/compose';
  if (path === '/runs/new/sandbox') path = '/welcome';
  if (
    path === '/admin/analytics' ||
    path === '/admin/pricing' ||
    path === '/admin/feature-flags' ||
    path === '/admin/tiers' ||
    path === '/admin/model-rates'
  )
    path = '/admin';
  if (path === '/claim') path = '/welcome';
  if (path === '/landing' || path === '') path = '/home';
  return <Navigate to={path + location.search + location.hash} replace />;
}

/**
 * Protected route children. Core surfaces (home / compose / live / result) use
 * the real IDE pages when `ide` is true; everything else uses the Signal pages
 * (transitional — they render inside whichever shell is active until rebuilt).
 */
function protectedChildren(ide: boolean) {
  return (
    <>
      {/* Default + run loop */}
      <Route path='/' element={<Navigate to='/home' replace />} />
      <Route path='/home' element={ide ? <IdeWelcome /> : <HomePage />} />
      <Route path='/runs' element={ide ? <IdeRuns /> : <AllRunsPage />} />
      <Route path='/compose' element={ide ? <IdeCompose /> : <ComposePage />} />
      <Route path='/plan' element={ide ? <IdePlan /> : <PlanPage />} />
      <Route path='/plan/:runId' element={ide ? <IdePlan /> : <PlanPage />} />
      <Route path='/live' element={<LivePage />} />
      <Route path='/live/:runId' element={ide ? <IdeLive /> : <LivePage />} />
      <Route path='/result' element={<ResultPage />} />
      <Route path='/result/:runId' element={ide ? <IdeRunResult /> : <ResultPage />} />
      <Route path='/runs/:runShort' element={ide ? <IdeRunResult /> : <ResultPage />} />

      {/* Projects */}
      <Route path='/projects' element={ide ? <IdeProjects /> : <ProjectsPage />} />
      <Route path='/projects/empty' element={<EmptyProjectPage />} />
      <Route path='/projects/:projectId' element={ide ? <IdeProject /> : <ProjectComposePage />} />
      <Route path='/projects/:projectId/pulse' element={<ProjectPulsePage />} />
      <Route path='/projects/:projectId/repos' element={<ProjectReposPage />} />
      <Route path='/projects/:projectId/runs' element={<ProjectRunsPage />} />
      <Route
        path='/projects/:projectId/runs/:runShort'
        element={ide ? <IdeRunResult /> : <ResultPage />}
      />
      <Route path='/projects/:projectId/code' element={<ProjectCodePage />} />
      <Route path='/projects/:projectId/chat' element={<ProjectChatPage />} />
      <Route path='/projects/:projectId/activity' element={<ProjectActivityPage />} />
      <Route path='/projects/:projectId/members' element={<ProjectMembersPage />} />
      <Route path='/projects/:projectId/architecture' element={<ProjectArchitecturePage />} />
      <Route path='/projects/:projectId/settings' element={<ProjectSettingsPage />} />

      {/* Account + billing */}
      <Route path='/account' element={ide ? <IdeAccount /> : <AccountPage />} />
      <Route path='/settings' element={ide ? <IdeAccount /> : <AccountPage />} />
      <Route path='/settings/manage' element={ide ? <IdeAccount /> : <AccountPage />} />
      <Route path='/billing' element={<BillingPage />} />
      <Route path='/plans' element={<PlansPage />} />
      <Route path='/topup' element={<Navigate to='/billing' replace />} />
      <Route path='/integrations' element={<IntegrationsPage />} />
      <Route path='/api-keys' element={<ApiKeysPage />} />
      <Route path='/org-members' element={<OrgMembersPage />} />
      <Route path='/org/settings' element={<OrgSettingsPage />} />

      {/* Discover */}
      <Route path='/insights' element={<InsightsPage />} />
      <Route path='/library' element={<LibraryPage />} />
      <Route path='/library/:slug' element={<LibraryDetailPage />} />
      <Route path='/migrations' element={<MigrationsPage />} />
      <Route path='/migrations/:slug' element={<MigrationDetailPage />} />
      <Route path='/templates' element={<SpecTemplatesPage />} />
      <Route path='/audit' element={<AuditLogPage />} />
      <Route path='/deployments' element={<DeploymentsPage />} />

      {/* System */}
      <Route path='/notifications' element={<NotificationsPage />} />
      <Route path='/search' element={<SearchPage />} />
      <Route path='/more' element={<MorePage />} />
      <Route path='/admin' element={<AdminPage />} />
      <Route path='/admin/*' element={<AdminPage />} />
    </>
  );
}

export default function App() {
  const uiMode = useUiStore((s) => s.uiMode);

  // Rollout default: if the user hasn't explicitly chosen a chrome, let the
  // public flag `ui.ide.default` decide (applied via setState, not persisted,
  // so the flag stays in control until the user toggles).
  useEffect(() => {
    if (localStorage.getItem('es:uiMode')) return;
    api
      .getPublicFeatureFlags()
      .then((flags) => {
        if (flags['ui.ide.default']) {
          useUiStore.setState({uiMode: 'ide'});
          document.documentElement.dataset.ui = 'ide';
        }
      })
      .catch(() => {});
  }, []);

  const ide = uiMode === 'ide';

  return (
    <Routes>
      {/* ───── Public auth ───── */}
      <Route path='/login' element={<LoginPage />} />
      <Route path='/register' element={<SignupPage />} />
      <Route path='/signup' element={<SignupPage />} />
      <Route path='/verify' element={<VerifyPage />} />
      <Route path='/verify-email' element={<VerifyPage />} />
      <Route path='/forgot-password' element={<ForgotPasswordPage />} />
      <Route path='/reset-password' element={<ResetPasswordPage />} />
      <Route path='/accept-invite' element={<AcceptInvitePage />} />
      <Route path='/auth/callback' element={<AuthCallbackPage />} />

      {/* ───── Public marketing / legal ───── */}
      <Route path='/welcome' element={<WelcomePage />} />
      <Route path='/pricing' element={<PublicPricingPage />} />
      <Route path='/trust' element={<TrustPage />} />
      <Route path='/terms' element={<TermsPage />} />
      <Route path='/privacy' element={<PrivacyPage />} />
      <Route path='/dpa' element={<DpaPage />} />
      <Route path='/shared/:token' element={<SharedPage />} />
      <Route path='/reports/shared/:token' element={<SharedPage />} />
      <Route path='/projects/snapshot/:token' element={<SharedPage />} />

      {/* ───── Protected app (Signal shell or IDE workspace) ───── */}
      <Route element={<ProtectedRoute />}>
        <Route element={ide ? <IdeWorkspace /> : <SignalShell />}>{protectedChildren(ide)}</Route>
      </Route>

      {/* Legacy URL catchers — collapse to canonical equivalents */}
      <Route path='/v3/*' element={<LegacyRedirect />} />
      <Route path='/v4/*' element={<LegacyRedirect />} />
      <Route path='/signal/*' element={<LegacyRedirect />} />
      <Route path='/p/*' element={<LegacyRedirect />} />
      <Route path='/dashboard' element={<LegacyRedirect />} />
      <Route path='/legacy/billing' element={<LegacyRedirect />} />
      <Route path='/exec' element={<LegacyRedirect />} />
      <Route path='/exec-demo' element={<LegacyRedirect />} />
      <Route path='/accounting' element={<LegacyRedirect />} />
      <Route path='/invoices' element={<LegacyRedirect />} />
      <Route path='/team' element={<LegacyRedirect />} />
      <Route path='/team/:userId' element={<LegacyRedirect />} />
      <Route path='/org/*' element={<LegacyRedirect />} />
      <Route path='/governance/audit' element={<LegacyRedirect />} />
      <Route path='/runs/new' element={<LegacyRedirect />} />
      <Route path='/runs/new/sandbox' element={<LegacyRedirect />} />
      <Route path='/claim' element={<LegacyRedirect />} />
      <Route path='/landing' element={<LegacyRedirect />} />

      {/* Catch-all */}
      <Route path='*' element={<Navigate to='/home' replace />} />
    </Routes>
  );
}
