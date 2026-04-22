import { useEffect } from 'react';
import { Navigate } from 'react-router-dom';
import { useUiStore } from '@/stores/useUiStore';

/**
 * Wave 2.2 — thin landing that reads {@code sandboxPrefill} (set by
 * {@link WelcomePage}), hands it to {@code useUiStore.pendingNewRunConfig}
 * so {@code RunList} auto-opens the {@code NewRunModal} on mount with the
 * spec pre-filled, then redirects to {@code /}.
 *
 * <p>Keeps the URL pattern {@code /runs/new} clean for deep-linking into
 * the run-submit flow from docs / emails without any code on the Runs
 * page. If {@code sandboxPrefill} is absent the page still works — it
 * just opens an empty NewRunModal.
 */
export default function RunsNewLanding() {
  const { setPendingNewRunConfig } = useUiStore();

  useEffect(() => {
    try {
      const raw = sessionStorage.getItem('sandboxPrefill');
      if (!raw) return;
      const payload = JSON.parse(raw);
      setPendingNewRunConfig({
        goal: payload?.spec ?? '',
        executionMode: 'PROTOTYPE',  // onboarding: skip plan gate for instant feedback
        provider: payload?.preferredProvider ?? undefined,
        projectId: payload?.projectId ?? undefined,
        repoMode: 'NONE',
      });
      sessionStorage.removeItem('sandboxPrefill');
    } catch {
      // fall through — empty modal is still a valid state
    }
  }, [setPendingNewRunConfig]);

  return <Navigate to="/" replace />;
}
