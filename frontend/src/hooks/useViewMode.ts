import { useCallback } from 'react';
import { useUiStore } from '../stores/useUiStore';
import { useAuthStore } from '../stores/useAuthStore';
import { updateViewMode as apiUpdateViewMode } from '../lib/api';

type ViewMode = 'vibe' | 'pro';
type Tier = 'FREE' | 'PRO' | 'MAX' | 'ENTERPRISE';

export function useViewMode() {
  const uiViewMode = useUiStore((s) => s.viewMode);
  const setUiViewMode = useUiStore((s) => s.setViewMode);
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);

  // Prefer the server-stored viewMode when the user is authenticated
  const viewMode: ViewMode = user?.viewMode ?? uiViewMode;
  const tier: Tier = user?.tier ?? 'FREE';

  const setViewMode = useCallback(
    (mode: ViewMode) => {
      // Always update local UI store for instant feedback
      setUiViewMode(mode);

      // Persist to backend if authenticated
      if (user) {
        setUser({ ...user, viewMode: mode });
        apiUpdateViewMode(mode).catch(() => {
          // Revert on failure
          setUser({ ...user, viewMode: viewMode });
          setUiViewMode(viewMode);
        });
      }
    },
    [user, viewMode, setUiViewMode, setUser],
  );

  const isVibe = viewMode === 'vibe';
  const isPro = viewMode === 'pro';

  return {
    viewMode,
    setViewMode,
    isVibe,
    isPro,
    tier,

    // ── Feature visibility flags ────────────────────────────────────────────
    // Vibe hides these; Pro shows them
    showTaskGraph: isPro,
    showValidationGates: isPro,
    showModelRouting: isPro,
    showCostBreakdown: isPro,
    showCodeDiffs: isPro,
    showPlanApproval: isPro,

    // Always visible regardless of mode
    showConversation: true,
    showPreview: true,
    showDeploy: true,

    // ── Tier-gated flags ────────────────────────────────────────────────────
    canUseIntegrations: tier !== 'FREE',
    canUseOwnKeys: tier === 'ENTERPRISE' || tier === 'MAX',
    canCreateOrg: tier !== 'FREE',

    // ── Default run flags for Vibe mode ─────────────────────────────────────
    vibeRunDefaults: {
      skipApproval: true,
      autoMerge: true,
      autoPreview: true,
    },

    /** Switch to Pro mode — used by Vibe error "View details" links. */
    escalateToPro: () => setViewMode('pro'),
  };
}
