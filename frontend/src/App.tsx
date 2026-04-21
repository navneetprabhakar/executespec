import { useEffect } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from '@/components/layout/AppLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';

// Main app pages
import RunsPage from '@/pages/RunsPage';
import DashboardPage from '@/pages/DashboardPage';
import ExecSummaryPage from '@/pages/ExecSummaryPage';
import PricingPage from '@/pages/PricingPage';
import PlansPage from '@/pages/PlansPage';
import BillingPage from '@/pages/BillingPage';
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
import ProjectsListPage from '@/pages/project/ProjectsListPage';
import ExecutionViewPage from '@/pages/execution/ExecutionViewPage';

// Admin pages
import AdminPage from '@/pages/admin/AdminPage';
import FeatureFlagsPage from '@/pages/admin/FeatureFlagsPage';
import OrgAdminDashboard from '@/pages/admin/OrgAdminDashboard';
import PricingManager from '@/pages/admin/PricingManager';

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

import { useAuthStore } from '@/stores/useAuthStore';
import { useKeyboardScroll } from '@/hooks/useKeyboardScroll';

export default function App() {
  const { loadMe } = useAuthStore();

  // iOS Safari: scroll focused input into view when soft keyboard opens
  useKeyboardScroll();

  useEffect(() => {
    // Try to restore session from access_token or refresh cookie on mount
    loadMe();
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

      {/* ── Protected app routes ── */}
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route index element={<RunsPage />} />
          <Route path="dashboard" element={<DashboardPage />} />
          <Route path="pricing" element={<PricingPage />} />
          <Route path="plans" element={<PlansPage />} />
          <Route path="billing" element={<BillingPage />} />
          <Route path="integrations" element={<IntegrationsPage />} />
          <Route path="invoices" element={<InvoicesPage />} />
          <Route path="accounting" element={<AccountingPage />} />
          <Route path="audit" element={<AuditPage />} />
          <Route path="deployments" element={<DeploymentsPage />} />
          <Route path="settings" element={<SettingsPage />} />
          {/* v2.5.14 Phase 3.1 — executive summary (business-owner persona). */}
          <Route path="exec" element={<ExecSummaryPage />} />

          {/* Org pages */}
          <Route path="org/settings" element={<OrgSettingsPage />} />
          <Route path="org/projects" element={<OrgProjectsPage />} />
          <Route path="projects" element={<ProjectsListPage />} />
          {/* v2.5.12 — fallback so /projects/:id (without /dashboard) resolves.
              The API side lists projects at /api/projects/{id}/dashboard; users
              were hitting /projects/{id} and seeing a blank page because the
              route fell through. */}
          <Route path="projects/:projectId" element={<Navigate to="dashboard" replace />} />
          <Route path="projects/:projectId/dashboard" element={<ProjectDashboardPage />} />
          <Route path="projects/:projectId/executions/new" element={<ExecutionViewPage />} />
          <Route path="projects/:projectId/executions/:executionId" element={<ExecutionViewPage />} />

          {/* Admin */}
          <Route path="admin" element={<AdminPage />} />
          <Route path="admin/analytics" element={<OrgAdminDashboard />} />
          <Route path="admin/pricing" element={<PricingManager />} />
          <Route path="admin/feature-flags" element={<FeatureFlagsPage />} />
          <Route path="admin/*" element={<AdminPage />} />

          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}
