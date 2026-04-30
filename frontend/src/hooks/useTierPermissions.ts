import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Tier-based permission flags computed by the backend.
 * Fetched once on login via GET /api/me/permissions and cached in auth store.
 */
export interface TierPermissions {
  canManageAiKeys: boolean;
  /**
   * @deprecated [F-09] (audit Sprint 2, 2026-04-30) — currently consumed
   * by zero call sites in `frontend/src/`. Kept on the API contract so
   * removing it doesn't break stale frontend bundles or third-party
   * integrations that cached the shape, but new code should not branch
   * on this. Pricing-page visibility is gated at the route-level (no
   * runtime permission check) — anonymous users hit /pricing fine.
   */
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
  // Finance permissions
  canViewInvoices: boolean;
  canViewAccounting: boolean;
  canManageCreditNotes: boolean;
  canExportGstr: boolean;
  canManageSellerConfig: boolean;
  canManageBillingProfile: boolean;
  monthlyRunLimit: number;
  currentRunCount: number;
  maxOrgMembers: number;
  currentOrgMembers: number;
  /** Max apply-to-run actions per run. -1 = unlimited. */
  maxApplySuggestions: number;
  /** Max preview starts per run. -1 = unlimited. */
  maxPreviewStarts: number;
  /** Whether user can use the deploy engine. PRO/Enterprise only. */
  canDeploy: boolean;
  /** Max active deployments. 0 = disabled, -1 = unlimited. */
  maxDeployments: number;
}

/** Convenience hook — reads permissions from the auth store. */
export function useTierPermissions(): TierPermissions | null {
  return useAuthStore((s) => s.permissions);
}
