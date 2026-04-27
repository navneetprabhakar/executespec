import { create } from 'zustand';

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

type ViewMode = 'vibe' | 'pro' | 'project';
type Theme = 'dark' | 'light';

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
  /** Vibe (simplified) or Pro (full pipeline) view mode. */
  viewMode: ViewMode;
  /** Theme preference — v2.5.14 Phase 2. Persists to localStorage. */
  theme: Theme;

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
}

let toastSeq = 0;

// v2.5.14 Phase 2 — apply saved theme to <html> before React paints so there's
// no flash of unstyled content. Called synchronously at module import time.
function initTheme(): Theme {
  const saved = localStorage.getItem('es:theme') as Theme | null;
  const theme: Theme = saved === 'light' ? 'light' : 'dark';
  if (typeof document !== 'undefined') {
    document.documentElement.dataset.theme = theme;
  }
  return theme;
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
  viewMode: ((): ViewMode => {
    const saved = localStorage.getItem('es:viewMode') as ViewMode | null;
    // 'project' is a deliberate opt-in — never persist as the default session mode.
    // On login (fresh session), land on 'pro'; respect saved 'vibe' or 'pro' only.
    if (saved === 'vibe' || saved === 'pro') return saved;
    return 'pro';
  })(),

  setSidebarExpanded: (v) => {
    try { localStorage.setItem('es:sidebarExpanded', String(v)); } catch {}
    set({ sidebarExpanded: v });
  },
  setMobileSidebarOpen: (v) => set({ mobileSidebarOpen: v }),
  setCommandPaletteOpen: (v) => set({ commandPaletteOpen: v }),
  setActiveTab: (t) => set({ activeTab: t }),
  setPendingNewRunConfig: (config) => set({ pendingNewRunConfig: config }),
  setRunListCollapsed: (v) => set({ runListCollapsed: v }),
  setRunListWidth: (w) => {
    localStorage.setItem('es:runListWidth', String(w));
    set({ runListWidth: w });
  },
  setCopilotMaximized: (v) => set({ copilotMaximized: v }),
  setViewMode: (mode) => {
    localStorage.setItem('es:viewMode', mode);
    // Reset activeTab to the default for the new view mode
    // VIBE view starts on 'code', PRO view starts on 'overview'
    set({ viewMode: mode, activeTab: mode === 'vibe' ? 'code' : 'overview' });
  },
  setTheme: (t) => {
    localStorage.setItem('es:theme', t);
    if (typeof document !== 'undefined') document.documentElement.dataset.theme = t;
    set({ theme: t });
  },
  toggleTheme: () => {
    const next: Theme = get().theme === 'dark' ? 'light' : 'dark';
    localStorage.setItem('es:theme', next);
    if (typeof document !== 'undefined') document.documentElement.dataset.theme = next;
    set({ theme: next });
  },

  addToast: (t) => {
    const id = String(++toastSeq);
    set((state) => ({ toasts: [...state.toasts, { ...t, id }] }));
    setTimeout(() => set((state) => ({ toasts: state.toasts.filter((x) => x.id !== id) })), 5000);
  },

  removeToast: (id) =>
    set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })),
}));
