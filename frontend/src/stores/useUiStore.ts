import { create } from 'zustand';

export interface Toast {
  id: string;
  type: 'success' | 'error' | 'info';
  message: string;
}

type ViewMode = 'vibe' | 'pro';

interface UiStore {
  sidebarExpanded: boolean;
  mobileSidebarOpen: boolean;
  commandPaletteOpen: boolean;
  activeTab: string;
  toasts: Toast[];
  /** Pre-filled goal for NewRunModal — set by RunConversation "Implement as New Run". */
  pendingNewRunGoal: string | null;
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
  setPendingNewRunGoal: (goal: string | null) => void;
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
  pendingNewRunGoal: null,
  runListCollapsed: false,
  runListWidth: Number(localStorage.getItem('es:runListWidth')) || 420,
  copilotMaximized: false,
  viewMode: (localStorage.getItem('es:viewMode') as ViewMode) || 'vibe',

  setSidebarExpanded: (v) => set({ sidebarExpanded: v }),
  setMobileSidebarOpen: (v) => set({ mobileSidebarOpen: v }),
  setCommandPaletteOpen: (v) => set({ commandPaletteOpen: v }),
  setActiveTab: (t) => set({ activeTab: t }),
  setPendingNewRunGoal: (goal) => set({ pendingNewRunGoal: goal }),
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
