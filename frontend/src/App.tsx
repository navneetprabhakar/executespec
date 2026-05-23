import {Routes, Route, Navigate, useLocation} from 'react-router-dom';
import ProtectedRoute from '@/signal/components/auth/ProtectedRoute';

// 2026-05-22 — the VS Code-style IDE workspace is the ONLY chrome (the Signal
// chrome was retired as a user-facing option, A/B toggle removed). The IDE
// renders its OWN core surfaces; a handful of non-core routes (billing, plans,
// org-settings, legal, auth, etc.) transitionally reuse the standalone Signal
// pages until they're rebuilt IDE-native.
import IdeWorkspace from '@/ide/IdeWorkspace';
import {IdeWelcome, IdeCompose, IdeLive} from '@/ide/IdeSurfaces';
import IdeRunResult from '@/ide/IdeRunResult';
import IdeProject from '@/ide/IdeProject';
import IdeProjects from '@/ide/IdeProjects';
import {IdeProjectPulse, IdeProjectRuns, IdeProjectRepos} from '@/ide/IdeProjectTabs';
import {
  IdeProjectChat,
  IdeProjectCode,
  IdeProjectArchitecture,
  IdeProjectMembers,
  IdeProjectActivity,
  IdeProjectSettings,
} from '@/ide/IdeProjectMore';
import IdePlan from '@/ide/IdePlan';
import {IdeRuns, IdeAccount, IdeDeployments} from '@/ide/IdeMore';
import {IdeIntegrations, IdeAdmin} from '@/ide/IdeSettings';
import IdeNotifications, {IdeSearch, IdeAudit, IdeEmptyProject, IdeMoreMenu} from '@/ide/IdeSystem';
import {
  IdeInsights,
  IdeLibrary,
  IdeLibraryDetail,
  IdeMigrations,
  IdeMigrationDetail,
  IdeSpecTemplates,
} from '@/ide/IdeDiscovery';

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
import {AdminPage, SharedPage} from '@/signal/pages/MiscPages';

// Extras (Library, Migrations, etc.)
import {
  TrustPage,
  PublicPricingPage,
  ForgotPasswordPage,
  ResetPasswordPage,
  AcceptInvitePage,
  DeploymentsPage,
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
      <Route path='/projects/empty' element={<IdeEmptyProject />} />
      <Route path='/projects/:projectId' element={ide ? <IdeProject /> : <ProjectComposePage />} />
      <Route
        path='/projects/:projectId/pulse'
        element={ide ? <IdeProjectPulse /> : <ProjectPulsePage />}
      />
      <Route
        path='/projects/:projectId/repos'
        element={ide ? <IdeProjectRepos /> : <ProjectReposPage />}
      />
      <Route
        path='/projects/:projectId/runs'
        element={ide ? <IdeProjectRuns /> : <ProjectRunsPage />}
      />
      <Route
        path='/projects/:projectId/runs/:runShort'
        element={ide ? <IdeRunResult /> : <ResultPage />}
      />
      <Route
        path='/projects/:projectId/code'
        element={ide ? <IdeProjectCode /> : <ProjectCodePage />}
      />
      <Route
        path='/projects/:projectId/chat'
        element={ide ? <IdeProjectChat /> : <ProjectChatPage />}
      />
      <Route
        path='/projects/:projectId/activity'
        element={ide ? <IdeProjectActivity /> : <ProjectActivityPage />}
      />
      <Route
        path='/projects/:projectId/members'
        element={ide ? <IdeProjectMembers /> : <ProjectMembersPage />}
      />
      <Route
        path='/projects/:projectId/architecture'
        element={ide ? <IdeProjectArchitecture /> : <ProjectArchitecturePage />}
      />
      <Route
        path='/projects/:projectId/settings'
        element={ide ? <IdeProjectSettings /> : <ProjectSettingsPage />}
      />

      {/* Account + billing */}
      <Route path='/account' element={ide ? <IdeAccount /> : <AccountPage />} />
      <Route path='/settings' element={ide ? <IdeAccount /> : <AccountPage />} />
      <Route path='/settings/manage' element={ide ? <IdeAccount /> : <AccountPage />} />
      <Route path='/billing' element={<BillingPage />} />
      <Route path='/plans' element={<PlansPage />} />
      <Route path='/topup' element={<Navigate to='/billing' replace />} />
      <Route path='/integrations' element={ide ? <IdeIntegrations /> : <IntegrationsPage />} />
      <Route path='/api-keys' element={<ApiKeysPage />} />
      <Route path='/org-members' element={<OrgMembersPage />} />
      <Route path='/org/settings' element={<OrgSettingsPage />} />

      {/* Discover */}
      <Route path='/insights' element={<IdeInsights />} />
      <Route path='/library' element={<IdeLibrary />} />
      <Route path='/library/:slug' element={<IdeLibraryDetail />} />
      <Route path='/migrations' element={<IdeMigrations />} />
      <Route path='/migrations/:slug' element={<IdeMigrationDetail />} />
      <Route path='/templates' element={<IdeSpecTemplates />} />
      <Route path='/audit' element={<IdeAudit />} />
      <Route path='/deployments' element={ide ? <IdeDeployments /> : <DeploymentsPage />} />

      {/* System */}
      <Route path='/notifications' element={<IdeNotifications />} />
      <Route path='/search' element={<IdeSearch />} />
      <Route path='/more' element={<IdeMoreMenu />} />
      <Route path='/admin' element={ide ? <IdeAdmin /> : <AdminPage />} />
      <Route path='/admin/*' element={<AdminPage />} />
    </>
  );
}

export default function App() {
  // 2026-05-22 — Signal chrome retired; the IDE workspace is the only chrome.
  // `ide` stays a constant so the per-route helpers below keep their shape
  // until the remaining Signal-page fallbacks are ported IDE-native.
  const ide = true;

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

      {/* ───── Protected app (IDE workspace — the only chrome) ───── */}
      <Route element={<ProtectedRoute />}>
        <Route element={<IdeWorkspace />}>{protectedChildren(ide)}</Route>
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
