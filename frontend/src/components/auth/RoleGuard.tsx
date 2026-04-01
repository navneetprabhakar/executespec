import { useAuthStore } from '@/stores/useAuthStore';

interface Props {
  requireSuperAdmin?: boolean;
  requireOrgAdmin?: boolean;
  children: React.ReactNode;
  fallback?: React.ReactNode;
}

export default function RoleGuard({ requireSuperAdmin, requireOrgAdmin, children, fallback }: Props) {
  const { user } = useAuthStore();

  if (!user) return null;

  if (requireSuperAdmin && user.globalRole !== 'SUPER_ADMIN') {
    return fallback ? <>{fallback}</> : (
      <div className="flex h-full items-center justify-center text-muted">
        <p>403 — Not authorized</p>
      </div>
    );
  }

  if (requireOrgAdmin &&
      user.globalRole !== 'SUPER_ADMIN' &&
      user.orgRole !== 'ORG_ADMIN') {
    return fallback ? <>{fallback}</> : (
      <div className="flex h-full items-center justify-center text-muted">
        <p>403 — Org Admin access required</p>
      </div>
    );
  }

  return <>{children}</>;
}
