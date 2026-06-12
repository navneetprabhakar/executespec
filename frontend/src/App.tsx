import {lazy, Suspense} from 'react';
import {Routes, Route, Navigate, useLocation} from 'react-router-dom';
import ProtectedRoute from '@/signal/components/auth/ProtectedRoute';

// The protected app now uses the Console shell as the product chrome. Code/file
// surfaces keep their route-level components, but the VS Code-style workspace
// shell is no longer mounted.
import ConsoleShell from '@/console/ConsoleShell';
import ConsoleHome from '@/console/ConsoleHome';
import ConsoleDecisions from '@/console/ConsoleDecisions';
import {IdeCompose, IdeLive} from '@/ide/IdeSurfaces';
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
import IdeNotifications, {IdeSearch, IdeAudit, IdeEmptyProject, IdeMoreMenu} from '@/ide/IdeSystem';

// C13 (UI/UX review 2026-06-11) — route-level code-splitting, the follow-up
// layer on top of the RL-2026-05-041 vendor manualChunks. The heavy, less-
// trafficked surface groups load on demand; the core run loop (welcome /
// compose / live / result / runs / projects) stays eager so the funnel never
// shows a chunk-load spinner. lazy() calls sharing one import() share a chunk.
const IdeIntegrations = lazy(() =>
  import('@/ide/IdeSettings').then((m) => ({default: m.IdeIntegrations})),
);
const IdeAdmin = lazy(() => import('@/ide/IdeSettings').then((m) => ({default: m.IdeAdmin})));
const IdeInsights = lazy(() =>
  import('@/ide/IdeDiscovery').then((m) => ({default: m.IdeInsights})),
);
const IdeApiKeys = lazy(() => import('@/ide/IdeOrg').then((m) => ({default: m.IdeApiKeys})));
const IdeOrgMembers = lazy(() => import('@/ide/IdeOrg').then((m) => ({default: m.IdeOrgMembers})));
const IdeOrgSettings = lazy(() =>
  import('@/ide/IdeOrg').then((m) => ({default: m.IdeOrgSettings})),
);
const IdeBilling = lazy(() => import('@/ide/IdeBilling').then((m) => ({default: m.IdeBilling})));
const IdePlans = lazy(() => import('@/ide/IdeBilling').then((m) => ({default: m.IdePlans})));
const IdeBenchmark = lazy(() => import('@/ide/IdeBenchmark'));

// Auth + onboarding
import {LoginPage, SignupPage, VerifyPage, WelcomePage} from '@/signal/pages/AuthPages';
import AuthCallbackPage from '@/signal/pages/AuthCallbackPage';
import CliLoginPage from '@/signal/pages/CliLoginPage';

// Admin sub-routes + public share pages are the only Signal pages that survive
// inside the router (no IDE-native surface by design); AdminPage also backs the
// /admin/* deep links. Lazy: AdminPage is operator-only and ExtraPages is a
// 2,700-line god-file — neither belongs in the entry chunk.
const AdminPage = lazy(() =>
  import('@/signal/pages/MiscPages').then((m) => ({default: m.AdminPage})),
);
const SharedPage = lazy(() =>
  import('@/signal/pages/MiscPages').then((m) => ({default: m.SharedPage})),
);

// Standalone public pages (auth / marketing / legal) — no IDE chrome.
const TrustPage = lazy(() =>
  import('@/signal/pages/ExtraPages').then((m) => ({default: m.TrustPage})),
);
const PublicPricingPage = lazy(() =>
  import('@/signal/pages/ExtraPages').then((m) => ({default: m.PublicPricingPage})),
);
const ForgotPasswordPage = lazy(() =>
  import('@/signal/pages/ExtraPages').then((m) => ({default: m.ForgotPasswordPage})),
);
const ResetPasswordPage = lazy(() =>
  import('@/signal/pages/ExtraPages').then((m) => ({default: m.ResetPasswordPage})),
);
const AcceptInvitePage = lazy(() =>
  import('@/signal/pages/ExtraPages').then((m) => ({default: m.AcceptInvitePage})),
);
const TermsPage = lazy(() =>
  import('@/signal/pages/LegalPages').then((m) => ({default: m.TermsPage})),
);
const PrivacyPage = lazy(() =>
  import('@/signal/pages/LegalPages').then((m) => ({default: m.PrivacyPage})),
);
const DpaPage = lazy(() => import('@/signal/pages/LegalPages').then((m) => ({default: m.DpaPage})));

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
 * Protected route children. Console provides the product chrome; the route
 * components keep their current implementation until each surface is redesigned.
 * Bare /live + /result redirect to /runs because there is no run id to render.
 */
function protectedChildren() {
  return (
    <>
      {/* Default + run loop */}
      <Route path='/' element={<Navigate to='/home' replace />} />
      <Route path='/home' element={<ConsoleHome />} />
      <Route path='/decisions' element={<ConsoleDecisions />} />
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
      <Route path='/projects/:projectId/system-map' element={<IdeProjectArchitecture />} />
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
      <Route path='/provider-keys' element={<IdeApiKeys />} />
      <Route path='/org-members' element={<IdeOrgMembers />} />
      <Route path='/org/settings' element={<IdeOrgSettings />} />

      {/* Discover */}
      <Route path='/insights' element={<IdeInsights />} />
      <Route path='/library' element={<Navigate to='/home' replace />} />
      <Route path='/library/:slug' element={<Navigate to='/home' replace />} />
      <Route path='/migrations' element={<Navigate to='/home' replace />} />
      <Route path='/migrations/:slug' element={<Navigate to='/home' replace />} />
      <Route path='/templates' element={<Navigate to='/home' replace />} />
      <Route path='/audit' element={<IdeAudit />} />
      <Route path='/deployments' element={<IdeDeployments />} />

      {/* System */}
      <Route path='/notifications' element={<IdeNotifications />} />
      <Route path='/search' element={<IdeSearch />} />
      <Route path='/more' element={<IdeMoreMenu />} />
      <Route path='/admin' element={<IdeAdmin />} />
      <Route path='/admin/*' element={<AdminPage />} />
      {/* Operator-only benchmark tracking (SUPER_ADMIN) — RL-2026-06. */}
      <Route path='/benchmark' element={<IdeBenchmark />} />
      <Route path='/benchmarks' element={<Navigate to='/benchmark' replace />} />
    </>
  );
}

export default function App() {
  return (
    <Suspense
      fallback={
        <div
          style={{
            display: 'flex',
            height: '100vh',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'var(--signal-text-mute, #888)',
            fontSize: 13,
          }}
        >
          Loading…
        </div>
      }
    >
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
          Console is now the only protected shell. Code/file-oriented surfaces
          still render inside the route outlet, but the global VS Code chrome is
          not part of the product frame. */}
        <Route element={<ProtectedRoute />}>
          <Route element={<ConsoleShell />}>{protectedChildren()}</Route>
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
    </Suspense>
  );
}
