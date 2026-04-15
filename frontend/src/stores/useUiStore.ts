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
}

let toastSeq = 0;

export const useUiStore = create<UiStore>((set) => ({
  sidebarExpanded: false,
  mobileSidebarOpen: false,
  commandPaletteOpen: false,
  activeTab: 'overview',
  toasts: [],
  pendingNewRunConfig: null,
  runListCollapsed: false,
  runListWidth: Number(localStorage.getItem('es:runListWidth')) || 420,
  copilotMaximized: false,
  viewMode: ((): ViewMode => {
    const saved = localStorage.getItem('es:viewMode') as ViewMode | null;
    // 'project' is a deliberate opt-in — never persist as the default session mode.
    // On login (fresh session), land on 'pro'; respect saved 'vibe' or 'pro' only.
    if (saved === 'vibe' || saved === 'pro') return saved;
    return 'pro';
  })(),

  setSidebarExpanded: (v) => set({ sidebarExpanded: v }),
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

  addToast: (t) => {
    const id = String(++toastSeq);
    set((state) => ({ toasts: [...state.toasts, { ...t, id }] }));
    setTimeout(() => set((state) => ({ toasts: state.toasts.filter((x) => x.id !== id) })), 5000);
  },

  removeToast: (id) =>
    set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })),
}));
