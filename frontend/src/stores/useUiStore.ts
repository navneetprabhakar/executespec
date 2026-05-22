import {create} from 'zustand';

export interface Toast {
  id: string;
  type: 'success' | 'error' | 'info';
  message: string;
}

export interface NewRunConfig {
  goal: string;
  executionMode?: 'PROTOTYPE' | 'PRODUCTION';
  model?: string;
  provider?: string;
  projectId?: string;
  repoMode?: 'NONE' | 'NEW' | 'EXISTING';
  repoUrl?: string;
}

// R-190 (post-cutover) — Vibe view-mode removed. 'pro' is default; 'project'
// is session-local opt-in for multi-repo project mode.
type ViewMode = 'pro' | 'project';
/**
 * Theme preference. X-07 (UX audit, 2026-05-03) introduces a 'system' option
 * which defers to `prefers-color-scheme` and re-resolves whenever the OS
 * value changes. Internally we still write `dark` or `light` to the
 * `<html data-theme>` attribute so downstream CSS is unchanged; the store
 * just remembers which mode the user picked.
 */
type Theme = 'dark' | 'light' | 'system';

/**
 * UI chrome A/B switch (2026-05-22 redesign). 'signal' = the current production
 * chrome; 'ide' = the new VS Code-style chrome. Per-user choice persisted to
 * localStorage; a public feature flag (`ui.ide.default`) can flip the default
 * for a rollout cohort when the user hasn't chosen explicitly. Applied to
 * `<html data-ui>` so CSS can key off it too.
 */
type UiMode = 'signal' | 'ide';

interface UiStore {
  sidebarExpanded: boolean;
  mobileSidebarOpen: boolean;
  commandPaletteOpen: boolean;
  activeTab: string;
  toasts: Toast[];
  /** Pre-filled config for NewRunModal — set by Re-run or Copilot "Iterate as New Run". */
  pendingNewRunConfig: NewRunConfig | null;
  /** Whether the run list panel is collapsed to give more space to run detail. */
  runListCollapsed: boolean;
  /** Width of the run list panel in pixels (persisted). */
  runListWidth: number;
  /** Whether the copilot chat panel is maximized (full-width overlay). */
  copilotMaximized: boolean;
  /** Pro (full pipeline) or Project (multi-repo) view mode. R-190 removed
   *  the legacy Vibe simplified mode; persisted 'vibe' values normalize to
   *  'pro' at parse time (see lib/api.ts + the saved-value coercion below). */
  viewMode: ViewMode;
  /** Theme preference — v2.5.14 Phase 2. Persists to localStorage. */
  theme: Theme;
  /** UI chrome A/B mode (signal | ide). Persists to localStorage. */
  uiMode: UiMode;
  /**
   * P-08 (UX audit, 2026-05-03) — name of the project the user is currently
   * viewing on a /projects/:id/dashboard route. ProjectDashboardPage sets
   * this on mount + clears on unmount so the Header breadcrumb can render
   * "ExecuteSpec / Projects / {projectName}" instead of the legacy literal
   * "Projects / Dashboard". Null when the user isn't on a project surface.
   */
  currentProjectName: string | null;

  setSidebarExpanded: (v: boolean) => void;
  setMobileSidebarOpen: (v: boolean) => void;
  setCommandPaletteOpen: (v: boolean) => void;
  setActiveTab: (t: string) => void;
  addToast: (t: Omit<Toast, 'id'>) => void;
  removeToast: (id: string) => void;
  setPendingNewRunConfig: (config: NewRunConfig | null) => void;
  setRunListCollapsed: (v: boolean) => void;
  setRunListWidth: (w: number) => void;
  setCopilotMaximized: (v: boolean) => void;
  setViewMode: (mode: ViewMode) => void;
  setTheme: (t: Theme) => void;
  toggleTheme: () => void;
  setUiMode: (m: UiMode) => void;
  toggleUiMode: () => void;
  setCurrentProjectName: (name: string | null) => void;
}

let toastSeq = 0;

// v2.5.14 Phase 2 — apply saved theme to <html> before React paints so there's
// no flash of unstyled content. Called synchronously at module import time.
// X-07 — saved value can now be 'system'; in that case we resolve via
// matchMedia and apply the resolved value to the DOM. Subscriber lives on the
// store consumer (Header's ThemeToggle) so the listener can be cleaned up.
function resolveSystemTheme(): 'dark' | 'light' {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return 'dark';
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function initTheme(): Theme {
  const saved = localStorage.getItem('es:theme') as Theme | null;
  const theme: Theme = saved === 'light' || saved === 'system' ? saved : 'dark';
  if (typeof document !== 'undefined') {
    document.documentElement.dataset.theme = theme === 'system' ? resolveSystemTheme() : theme;
  }
  return theme;
}

// UI chrome A/B mode — applied to <html data-ui> pre-paint so the right chrome
// renders without a flash. Defaults to 'signal' (production); the user's explicit
// choice in es:uiMode wins. A rollout flag can override the default at app boot
// (see App.tsx) for users who haven't chosen.
function initUiMode(): UiMode {
  const saved = localStorage.getItem('es:uiMode');
  const mode: UiMode = saved === 'ide' ? 'ide' : 'signal';
  if (typeof document !== 'undefined') {
    document.documentElement.dataset.ui = mode;
  }
  return mode;
}

// M-026 (Wave 1) — first-time users land with sidebar expanded so they can
// see the nav labels. Returning users get whatever they last set. Persistence
// key: es:sidebarExpanded ('true' | 'false'); absent = first-time → default true.
function initSidebarExpanded(): boolean {
  try {
    const saved = localStorage.getItem('es:sidebarExpanded');
    if (saved === 'true') return true;
    if (saved === 'false') return false;
    return true; // first-time default
  } catch {
    return true;
  }
}

export const useUiStore = create<UiStore>((set, get) => ({
  sidebarExpanded: initSidebarExpanded(),
  mobileSidebarOpen: false,
  commandPaletteOpen: false,
  activeTab: 'overview',
  toasts: [],
  pendingNewRunConfig: null,
  runListCollapsed: false,
  runListWidth: Number(localStorage.getItem('es:runListWidth')) || 420,
  copilotMaximized: false,
  theme: initTheme(),
  uiMode: initUiMode(),
  currentProjectName: null,
  viewMode: ((): ViewMode => {
    const saved = localStorage.getItem('es:viewMode');
    // 'project' is a deliberate opt-in — never persist as the default session mode.
    // R-190 (post-cutover): saved 'vibe' from V48-era users coerces to 'pro';
    // any other value also falls through to the 'pro' default.
    if (saved === 'pro') return 'pro';
    return 'pro';
  })(),

  setSidebarExpanded: (v) => {
    try {
      localStorage.setItem('es:sidebarExpanded', String(v));
    } catch {}
    set({sidebarExpanded: v});
  },
  setMobileSidebarOpen: (v) => set({mobileSidebarOpen: v}),
  setCommandPaletteOpen: (v) => set({commandPaletteOpen: v}),
  setActiveTab: (t) => set({activeTab: t}),
  setPendingNewRunConfig: (config) => set({pendingNewRunConfig: config}),
  setRunListCollapsed: (v) => set({runListCollapsed: v}),
  setRunListWidth: (w) => {
    localStorage.setItem('es:runListWidth', String(w));
    set({runListWidth: w});
  },
  setCopilotMaximized: (v) => set({copilotMaximized: v}),
  setViewMode: (mode) => {
    localStorage.setItem('es:viewMode', mode);
    // R-190 (post-cutover): both surviving modes (pro / project) start on
    // 'overview' tab. The previous Vibe mode started on 'code'; that path
    // is gone with the surface.
    set({viewMode: mode, activeTab: 'overview'});
  },
  setTheme: (t) => {
    localStorage.setItem('es:theme', t);
    if (typeof document !== 'undefined') {
      document.documentElement.dataset.theme = t === 'system' ? resolveSystemTheme() : t;
    }
    set({theme: t});
  },
  toggleTheme: () => {
    // X-07 — toggle now cycles dark → light → system → dark. Keeps the
    // 1-click behaviour of the old Sun/Moon button while exposing the new
    // System option. The Header renders a Sun / Moon / Monitor icon based
    // on the current resolved value.
    const cur = get().theme;
    const next: Theme = cur === 'dark' ? 'light' : cur === 'light' ? 'system' : 'dark';
    localStorage.setItem('es:theme', next);
    if (typeof document !== 'undefined') {
      document.documentElement.dataset.theme = next === 'system' ? resolveSystemTheme() : next;
    }
    set({theme: next});
  },
  setUiMode: (m) => {
    localStorage.setItem('es:uiMode', m);
    if (typeof document !== 'undefined') document.documentElement.dataset.ui = m;
    set({uiMode: m});
  },
  toggleUiMode: () => {
    const next: UiMode = get().uiMode === 'ide' ? 'signal' : 'ide';
    localStorage.setItem('es:uiMode', next);
    if (typeof document !== 'undefined') document.documentElement.dataset.ui = next;
    set({uiMode: next});
  },

  setCurrentProjectName: (name) => set({currentProjectName: name}),

  addToast: (t) => {
    const id = String(++toastSeq);
    set((state) => ({toasts: [...state.toasts, {...t, id}]}));
    setTimeout(() => set((state) => ({toasts: state.toasts.filter((x) => x.id !== id)})), 5000);
  },

  removeToast: (id) => set((state) => ({toasts: state.toasts.filter((t) => t.id !== id)})),
}));
