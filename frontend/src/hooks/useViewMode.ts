import {useCallback} from 'react';
import {useUiStore} from '../stores/useUiStore';
import {useAuthStore} from '../stores/useAuthStore';
import {updateViewMode as apiUpdateViewMode} from '../lib/api';

/**
 * R-190 (post-cutover) — Vibe view-mode removed. Two modes remain:
 *   - 'pro'     — default; full pipeline visibility (plan, validation, costs)
 *   - 'project' — session-local opt-in for multi-repo project mode (TEAM/ENT)
 *
 * Any persisted `view_mode='vibe'` from V48-era users is normalized to 'pro'
 * at parse time in lib/api.ts; this hook never sees a 'vibe' value. The
 * `isVibe` / `vibeRunDefaults` / `escalateToPro` helpers were the v2
 * Vibe-mode affordances and are gone with the surface.
 */
type ViewMode = 'pro' | 'project';
// Tier-coherence: 4-tier ladder (FREE / PRO / TEAM / ENTERPRISE). The legacy
// MAX tier was renamed to TEAM in V141; no MAX value reaches the frontend.
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
  const rawViewMode: ViewMode =
    uiViewMode === 'project'
      ? 'project'
      : user?.viewMode && user.viewMode !== 'project'
        ? (user.viewMode as ViewMode)
        : 'pro';
  const viewMode: ViewMode = rawViewMode;
  const tier: Tier = user?.tier ?? 'FREE';

  const setViewMode = useCallback(
    (mode: ViewMode) => {
      // Always update local UI store for instant feedback
      setUiViewMode(mode);

      // Persist 'pro' to backend; 'project' is opt-in per session only.
      if (user && mode !== 'project') {
        setUser({...user, viewMode: mode});
        apiUpdateViewMode(mode).catch(() => {
          setUser({...user, viewMode: viewMode});
          setUiViewMode(viewMode);
        });
      }
    },
    [user, viewMode, setUiViewMode, setUser],
  );

  const isPro = viewMode === 'pro';
  const isProject = viewMode === 'project';
  // Tier-coherence: TEAM + ENTERPRISE unlock multi-repo project mode.
  const canUseProjectMode = tier === 'ENTERPRISE' || tier === 'TEAM';

  return {
    viewMode,
    setViewMode,
    isPro,
    isProject,
    canUseProjectMode,
    tier,

    // ── Feature visibility flags ────────────────────────────────────────────
    // R-190: Vibe-mode previously hid these; with Vibe gone, Pro is the
    // only mode that controls them. They remain Pro-gated for callers that
    // expect the flag-shaped API; project mode also shows them.
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
  };
}
