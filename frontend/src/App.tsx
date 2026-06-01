import {Routes, Route, Navigate, useLocation} from 'react-router-dom';
import ProtectedRoute from '@/signal/components/auth/ProtectedRoute';

// The VS Code-style IDE workspace is the ONLY chrome. Every protected route is
// IDE-native (2026-05-23 Signal-removal waves finished the port). The only
// surviving Signal pages are the standalone PUBLIC pages (auth / marketing /
// legal / shared) — which have no IDE chrome by design — plus AdminPage for the
// /admin/* deep links.
import IdeWorkspace from '@/ide/IdeWorkspace';
import ConsoleShell from '@/console/ConsoleShell';
import ConsoleHome from '@/console/ConsoleHome';
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
import {IdeApiKeys, IdeOrgMembers, IdeOrgSettings} from '@/ide/IdeOrg';
import {IdeBilling, IdePlans} from '@/ide/IdeBilling';

// Auth + onboarding
import {LoginPage, SignupPage, VerifyPage, WelcomePage} from '@/signal/pages/AuthPages';
import AuthCallbackPage from '@/signal/pages/AuthCallbackPage';
import CliLoginPage from '@/signal/pages/CliLoginPage';

// Admin sub-routes + public share pages are the only Signal pages that survive
// inside the router (no IDE-native surface by design); AdminPage also backs the
// /admin/* deep links.
import {AdminPage, SharedPage} from '@/signal/pages/MiscPages';

// Standalone public pages (auth / marketing / legal) — no IDE chrome.
import {
  TrustPage,
  PublicPricingPage,
  ForgotPasswordPage,
  ResetPasswordPage,
  AcceptInvitePage,
} from '@/signal/pages/ExtraPages';
import {TermsPage, PrivacyPage, DpaPage} from '@/signal/pages/LegalPages';

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
 * Protected route children — all IDE-native (the Signal-page fallbacks were
 * removed in the 2026-05-23 cleanup). The only Signal page reached from here is
 * AdminPage for the /admin/* deep links; bare /live + /result (no run id)
 * redirect to /runs since there's nothing to render without an id.
 */
function protectedChildren() {
  return (
    <>
      {/* Default + run loop */}
      <Route path='/' element={<Navigate to='/home' replace />} />
      <Route
        path='/home'
        element={localStorage.getItem('es:console') === '1' ? <ConsoleHome /> : <IdeWelcome />}
      />
      <Route path='/runs' element={<IdeRuns />} />
      <Route path='/compose' element={<IdeCompose />} />
      <Route path='/plan' element={<IdePlan />} />
      <Route path='/plan/:runId' element={<IdePlan />} />
      <Route path='/live' element={<Navigate to='/runs' replace />} />
      <Route path='/live/:runId' element={<IdeLive />} />
      <Route path='/result' element={<Navigate to='/runs' replace />} />
      <Route path='/result/:runId' element={<IdeRunResult />} />
      <Route path='/runs/:runShort' element={<IdeRunResult />} />

      {/* Projects */}
      <Route path='/projects' element={<IdeProjects />} />
      <Route path='/projects/empty' element={<IdeEmptyProject />} />
      <Route path='/projects/:projectId' element={<IdeProject />} />
      <Route path='/projects/:projectId/pulse' element={<IdeProjectPulse />} />
      <Route path='/projects/:projectId/repos' element={<IdeProjectRepos />} />
      <Route path='/projects/:projectId/runs' element={<IdeProjectRuns />} />
      <Route path='/projects/:projectId/runs/:runShort' element={<IdeRunResult />} />
      <Route path='/projects/:projectId/code' element={<IdeProjectCode />} />
      <Route path='/projects/:projectId/chat' element={<IdeProjectChat />} />
      <Route path='/projects/:projectId/activity' element={<IdeProjectActivity />} />
      <Route path='/projects/:projectId/members' element={<IdeProjectMembers />} />
      <Route path='/projects/:projectId/architecture' element={<IdeProjectArchitecture />} />
      <Route path='/projects/:projectId/settings' element={<IdeProjectSettings />} />

      {/* Account + billing */}
      <Route path='/account' element={<IdeAccount />} />
      <Route path='/settings' element={<IdeAccount />} />
      <Route path='/settings/manage' element={<IdeAccount />} />
      <Route path='/billing' element={<IdeBilling />} />
      <Route path='/plans' element={<IdePlans />} />
      <Route path='/topup' element={<Navigate to='/billing' replace />} />
      <Route path='/integrations' element={<IdeIntegrations />} />
      <Route path='/api-keys' element={<IdeApiKeys />} />
      <Route path='/org-members' element={<IdeOrgMembers />} />
      <Route path='/org/settings' element={<IdeOrgSettings />} />

      {/* Discover */}
      <Route path='/insights' element={<IdeInsights />} />
      <Route path='/library' element={<IdeLibrary />} />
      <Route path='/library/:slug' element={<IdeLibraryDetail />} />
      <Route path='/migrations' element={<IdeMigrations />} />
      <Route path='/migrations/:slug' element={<IdeMigrationDetail />} />
      <Route path='/templates' element={<IdeSpecTemplates />} />
      <Route path='/audit' element={<IdeAudit />} />
      <Route path='/deployments' element={<IdeDeployments />} />

      {/* System */}
      <Route path='/notifications' element={<IdeNotifications />} />
      <Route path='/search' element={<IdeSearch />} />
      <Route path='/more' element={<IdeMoreMenu />} />
      <Route path='/admin' element={<IdeAdmin />} />
      <Route path='/admin/*' element={<AdminPage />} />
    </>
  );
}

export default function App() {
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
      {/* Org-member invites (auth/) email /accept-invite → POST /api/org/accept-invite. */}
      <Route path='/accept-invite' element={<AcceptInvitePage kind='org' />} />
      {/* Project invites (V175) email a /invites/accept link (ProjectInviteService +
          api.acceptProjectInvite + GET /api/invites/accept all use this path); without this route
          the magic link dead-ended and the invitee never became a ProjectMember. RL-2026-05-066. */}
      <Route path='/invites/accept' element={<AcceptInvitePage kind='project' />} />
      <Route path='/auth/callback' element={<AuthCallbackPage />} />
      <Route path='/cli-login' element={<CliLoginPage />} />

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

      {/* ───── Protected app chrome ─────
          Control-plane reframe (RL-2026-05-052): the new ConsoleShell is opt-in behind
          localStorage `es:console`='1' (default OFF → the IDE chrome is byte-identical).
          The same protectedChildren render in either shell's <Outlet/>. */}
      <Route element={<ProtectedRoute />}>
        <Route
          element={localStorage.getItem('es:console') === '1' ? <ConsoleShell /> : <IdeWorkspace />}
        >
          {protectedChildren()}
        </Route>
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
