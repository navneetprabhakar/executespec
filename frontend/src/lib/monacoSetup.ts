import {loader} from '@monaco-editor/react';
import {GEIST_THEME_NAME, geistDarkTheme} from './monacoGeistTheme';
import {isMonacoDisposeRace} from './monacoErrorPatterns';

/**
 * Centralized Monaco editor initialization.
 * Configures the CDN loader and registers the Geist theme.
 * Safe to import from multiple files — init() is idempotent.
 */

// Configure the loader to use a specific CDN version for reliability
loader.config({
  paths: {
    vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min/vs',
  },
});

export const monacoReady: Promise<typeof import('monaco-editor')> = loader
  .init()
  .then((monaco) => {
    monaco.editor.defineTheme(GEIST_THEME_NAME, geistDarkTheme);
    return monaco;
  })
  .catch((err) => {
    console.warn('Monaco editor failed to load from CDN:', err);
    throw err;
  });

/**
 * Install a window-level suppressor for Monaco's disposal-race errors.
 *
 * Why this is needed even though `MonacoSafeMount` already wraps the
 * DiffEditor: Monaco's internal `onUnexpectedError` handler re-throws the
 * error inside `setTimeout(() => { throw err; }, 0)` so it surfaces past
 * any sync handler. That `setTimeout` callback fires OUTSIDE React's
 * render tree, so `componentDidCatch` cannot intercept it — the error
 * lands as an `Uncaught Error` on the `window`. Stack hint: every
 * occurrence we've seen has a frame containing `errors.ts:26` followed by
 * `setTimeout`/`scheduleTask`/`postMessage`/`unstable_scheduleCallback`.
 *
 * The error boundary still helps for the rarer in-render path (model
 * accessed mid-render after dispose), so we keep both layers.
 *
 * Idempotent: guarded by a module-level flag so HMR re-execution of this
 * module body doesn't double-register listeners.
 */
let monacoGlobalSuppressorInstalled = false;
function installMonacoGlobalErrorSuppressor() {
  if (monacoGlobalSuppressorInstalled || typeof window === 'undefined') return;
  monacoGlobalSuppressorInstalled = true;

  // Capture phase = run BEFORE the browser's default uncaught-error logger
  // and before any other listener that might re-rethrow.
  window.addEventListener(
    'error',
    (event) => {
      const msg = event.error?.message ?? event.message ?? '';
      if (!isMonacoDisposeRace(msg)) return;
      // Quiet log — once per occurrence — so the swallow remains visible
      // in dev without drowning the console (these can fire in pairs for
      // original + modified sides on every file switch).
      console.warn('[monacoSetup] swallowed Monaco disposal race:', msg);
      event.preventDefault();
      event.stopImmediatePropagation();
    },
    true,
  );

  // Defensive — Monaco's deferred error path is sync setTimeout today, but
  // a future internal change could route through a Promise. Same pattern,
  // promise flavour.
  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason;
    const msg = reason instanceof Error ? reason.message : typeof reason === 'string' ? reason : '';
    if (!isMonacoDisposeRace(msg)) return;
    console.warn('[monacoSetup] swallowed Monaco disposal race (rejection):', msg);
    event.preventDefault();
  });
}
installMonacoGlobalErrorSuppressor();
