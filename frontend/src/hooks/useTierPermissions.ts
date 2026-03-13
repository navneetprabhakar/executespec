import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Tier-based permission flags computed by the backend.
 * Fetched once on login via GET /api/me/permissions and cached in auth store.
 */
export interface TierPermissions {
  canManageAiKeys: boolean;
  canViewPricing: boolean;
  canManageIntegrations: boolean;
  canViewMetrics: boolean;
  canViewCostBreakdown: boolean;
  canViewTokenUsage: boolean;
  canUseChat: boolean;
  canCreateOrg: boolean;
  canInviteMembers: boolean;
  canViewOrgMembers: boolean;
  canViewAudit: boolean;
  canViewLiveLog: boolean;
  monthlyRunLimit: number;
  currentRunCount: number;
  maxOrgMembers: number;
  currentOrgMembers: number;
  /** Max apply-to-run actions per run. -1 = unlimited. */
  maxApplySuggestions: number;
}

/** Convenience hook — reads permissions from the auth store. */
export function useTierPermissions(): TierPermissions | null {
  return useAuthStore((s) => s.permissions);
}
