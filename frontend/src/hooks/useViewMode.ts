import { useUiStore } from '../stores/useUiStore';

export function useViewMode() {
  const viewMode = useUiStore((s) => s.viewMode);
  const setViewMode = useUiStore((s) => s.setViewMode);

  return {
    viewMode,
    setViewMode,
    isVibe: viewMode === 'vibe',
    isPro: viewMode === 'pro',
    /** Switch to Pro mode — used by Vibe error "View details" links. */
    escalateToPro: () => setViewMode('pro'),
  };
}
