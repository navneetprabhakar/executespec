import type {ReactNode} from 'react';
import {Navigate} from 'react-router-dom';
import {selectIsSuperAdmin, useAuthStore} from '@/stores/useAuthStore';

export default function SuperAdminOnly({children}: {children: ReactNode}) {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  if (!isSuperAdmin) return <Navigate to='/home' replace />;
  return <>{children}</>;
}
