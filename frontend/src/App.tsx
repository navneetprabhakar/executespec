import { useEffect } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from '@/components/layout/AppLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';

// Main app pages
import RunsPage from '@/pages/RunsPage';
import DashboardPage from '@/pages/DashboardPage';
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

// Org pages
import OrgSettingsPage from '@/pages/org/OrgSettingsPage';
import OrgProjectsPage from '@/pages/org/OrgProjectsPage';

// Admin page
import AdminPage from '@/pages/admin/AdminPage';

// Audit page
import AuditPage from '@/pages/AuditPage';

// Finance pages
import InvoicesPage from '@/pages/InvoicesPage';
import AccountingPage from '@/pages/AccountingPage';

// Deploy page
import DeploymentsPage from '@/pages/DeploymentsPage';

// Public pages
import SharedReportPage from '@/pages/SharedReportPage';

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

      {/* ── Public shared pages ── */}
      <Route path="/reports/shared/:shareToken" element={<SharedReportPage />} />

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

          {/* Org pages */}
          <Route path="org/settings" element={<OrgSettingsPage />} />
          <Route path="org/projects" element={<OrgProjectsPage />} />

          {/* Admin */}
          <Route path="admin" element={<AdminPage />} />
          <Route path="admin/*" element={<AdminPage />} />

          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}
