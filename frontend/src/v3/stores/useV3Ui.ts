import { create } from 'zustand';

/**
 * R-103+ — tiny v3 UI store. Avoids prop-drilling for cross-component
 * concerns like opening the command palette from the top-bar button.
 *
 * Kept deliberately small; v3 screens own their own state. Anything
 * that needs to be reachable from "anywhere in v3" goes here.
 */
interface V3UiStore {
  cmdKOpen: boolean;
  openCmdK: () => void;
  closeCmdK: () => void;
  toggleCmdK: () => void;
}

export const useV3Ui = create<V3UiStore>((set) => ({
  cmdKOpen: false,
  openCmdK: () => set({ cmdKOpen: true }),
  closeCmdK: () => set({ cmdKOpen: false }),
  toggleCmdK: () => set((s) => ({ cmdKOpen: !s.cmdKOpen })),
}));
