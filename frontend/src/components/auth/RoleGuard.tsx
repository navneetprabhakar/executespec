import {useAuthStore} from '@/stores/useAuthStore';
import AccessDenied from '@/components/shared/AccessDenied';

interface Props {
  requireSuperAdmin?: boolean;
  requireOrgAdmin?: boolean;
  /** Surface name for the AccessDenied frame ("Admin console", "Audit log", etc.). */
  featureName?: string;
  children: React.ReactNode;
  fallback?: React.ReactNode;
}

/**
 * R-316 (Wave 7) — explicit AccessDenied frame for non-admin / non-super-admin
 * visitors. Pre-fix this component rendered a faceless `<p>403 — Not authorized</p>`
 * with no `<h1>` (which the F1 walker captured as "?-headed"). F1 F-003 + F2
 * EM Mike both called it out as a perception bug at the worst possible moment
 * (procurement-evaluator clicking around).
 *
 * The AccessDenied frame names the surface, names the role required, and
 * gives the user a clear escape hatch back to the dashboard. Same pattern
 * R-311 wired into AuditPage — RoleGuard now uses the same component for
 * the role-gated front-of-page path.
 */
export default function RoleGuard({
  requireSuperAdmin,
  requireOrgAdmin,
  featureName,
  children,
  fallback,
}: Props) {
  const {user} = useAuthStore();

  if (!user) return null;

  if (requireSuperAdmin && user.globalRole !== 'SUPER_ADMIN') {
    return fallback ? (
      <>{fallback}</>
    ) : (
      <AccessDenied feature={featureName ?? 'Admin console'} requiredRole='SUPER_ADMIN' />
    );
  }

  if (requireOrgAdmin && user.globalRole !== 'SUPER_ADMIN' && user.orgRole !== 'ORG_ADMIN') {
    return fallback ? (
      <>{fallback}</>
    ) : (
      <AccessDenied feature={featureName ?? 'This page'} requiredRole='ORG_ADMIN' />
    );
  }

  return <>{children}</>;
}
