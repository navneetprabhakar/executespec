import {useEffect} from 'react';
import SignalShell from '@/signal/components/Shell';
import IdeShell from '@/ide/IdeShell';
import {useUiStore} from '@/stores/useUiStore';
import * as api from '@/lib/api';

/**
 * A/B chrome resolver (2026-05-22 redesign). Renders the new VS Code-style
 * {@link IdeShell} when `uiMode === 'ide'`, else the production {@link SignalShell}.
 *
 * Rollout: an explicit per-user choice (localStorage `es:uiMode`, set by the
 * settings toggle / status-bar switch) always wins. When the user hasn't chosen,
 * the public feature flag `ui.ide.default` flips the default for the cohort —
 * applied via setState (NOT persisted), so the flag stays in control until the
 * user makes an explicit choice.
 */
export default function ChromeShell() {
  const uiMode = useUiStore((s) => s.uiMode);

  useEffect(() => {
    if (localStorage.getItem('es:uiMode')) return; // explicit user choice wins
    api
      .getPublicFeatureFlags()
      .then((flags) => {
        if (flags['ui.ide.default']) {
          useUiStore.setState({uiMode: 'ide'});
          if (typeof document !== 'undefined') document.documentElement.dataset.ui = 'ide';
        }
      })
      .catch(() => {
        /* no flag service → stay on Signal */
      });
  }, []);

  return uiMode === 'ide' ? <IdeShell /> : <SignalShell />;
}
