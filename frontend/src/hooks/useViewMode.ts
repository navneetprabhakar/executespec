import { useCallback } from 'react';
import { useUiStore } from '../stores/useUiStore';
import { useAuthStore } from '../stores/useAuthStore';
import { updateViewMode as apiUpdateViewMode } from '../lib/api';

type ViewMode = 'vibe' | 'pro' | 'project';
// V141 (2026-04-26) — MAX renamed to TEAM; old per-seat TEAM dropped.
type Tier = 'FREE' | 'PRO' | 'TEAM' | 'ENTERPRISE';

export function useViewMode() {
  const uiViewMode = useUiStore((s) => s.viewMode);
  const setUiViewMode = useUiStore((s) => s.setViewMode);
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);

  // 'project' is session-local — never persisted / never the login default.
  // On login we read user.viewMode from the server but coerce any stored
  // 'project' back to 'pro' so the toggle and the view agree. The local
  // uiViewMode wins when the user explicitly enters project mode this session.
  const rawViewMode: ViewMode = (uiViewMode === 'project')
    ? 'project'
    : (user?.viewMode && user.viewMode !== 'project')
      ? user.viewMode as ViewMode
      : (uiViewMode === 'vibe' ? 'vibe' : 'pro');
  const viewMode: ViewMode = rawViewMode;
  const tier: Tier = user?.tier ?? 'FREE';

  const setViewMode = useCallback(
    (mode: ViewMode) => {
      // Always update local UI store for instant feedback
      setUiViewMode(mode);

      // Persist vibe/pro to backend; project is opt-in per session only.
      if (user && mode !== 'project') {
        setUser({ ...user, viewMode: mode });
        apiUpdateViewMode(mode).catch(() => {
          setUser({ ...user, viewMode: viewMode });
          setUiViewMode(viewMode);
        });
      }
    },
    [user, viewMode, setUiViewMode, setUser],
  );

  const isVibe = viewMode === 'vibe';
  const isPro = viewMode === 'pro';
  const isProject = viewMode === 'project';
  const canUseProjectMode = tier === 'ENTERPRISE' || tier === 'TEAM';

  return {
    viewMode,
    setViewMode,
    isVibe,
    isPro,
    isProject,
    canUseProjectMode,
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
    canUseOwnKeys: tier === 'ENTERPRISE' || tier === 'TEAM',
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
