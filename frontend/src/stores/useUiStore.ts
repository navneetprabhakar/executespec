import { create } from 'zustand';

export interface Toast {
  id: string;
  type: 'success' | 'error' | 'info';
  message: string;
}

interface UiStore {
  sidebarExpanded: boolean;
  mobileSidebarOpen: boolean;
  commandPaletteOpen: boolean;
  activeTab: string;
  toasts: Toast[];
  /** Pre-filled goal for NewRunModal — set by RunConversation "Implement as New Run". */
  pendingNewRunGoal: string | null;

  setSidebarExpanded: (v: boolean) => void;
  setMobileSidebarOpen: (v: boolean) => void;
  setCommandPaletteOpen: (v: boolean) => void;
  setActiveTab: (t: string) => void;
  addToast: (t: Omit<Toast, 'id'>) => void;
  removeToast: (id: string) => void;
  setPendingNewRunGoal: (goal: string | null) => void;
}

let toastSeq = 0;

export const useUiStore = create<UiStore>((set) => ({
  sidebarExpanded: false,
  mobileSidebarOpen: false,
  commandPaletteOpen: false,
  activeTab: 'overview',
  toasts: [],
  pendingNewRunGoal: null,

  setSidebarExpanded: (v) => set({ sidebarExpanded: v }),
  setMobileSidebarOpen: (v) => set({ mobileSidebarOpen: v }),
  setCommandPaletteOpen: (v) => set({ commandPaletteOpen: v }),
  setActiveTab: (t) => set({ activeTab: t }),
  setPendingNewRunGoal: (goal) => set({ pendingNewRunGoal: goal }),

  addToast: (t) => {
    const id = String(++toastSeq);
    set((state) => ({ toasts: [...state.toasts, { ...t, id }] }));
    setTimeout(() => set((state) => ({ toasts: state.toasts.filter((x) => x.id !== id) })), 5000);
  },

  removeToast: (id) =>
    set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })),
}));
