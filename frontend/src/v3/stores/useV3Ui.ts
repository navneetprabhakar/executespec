import {create} from 'zustand';

/**
 * R-103+ — tiny v3 UI store. Avoids prop-drilling for cross-component
 * concerns like opening the command palette from the top-bar button.
 *
 * Kept deliberately small; v3 screens own their own state. Anything
 * that needs to be reachable from "anywhere in v3" goes here.
 */

export interface CapState {
  balance: number;
  cap: number;
  tierKey: string;
  isUnlimited: boolean;
}

interface V3UiStore {
  cmdKOpen: boolean;
  openCmdK: () => void;
  closeCmdK: () => void;
  toggleCmdK: () => void;

  // R-163 — free-tier balance + cap-toast dismiss flag.
  cap: CapState | null;
  setCap: (c: CapState) => void;
  capToastDismissed: boolean;
  dismissCapToast: () => void;

  // R-163 — explicit "show the blocking modal now" flag, set by the
  // composer right before submit when balance == 0.
  capModalOpen: boolean;
  openCapModal: () => void;
  closeCapModal: () => void;
}

export const useV3Ui = create<V3UiStore>((set) => ({
  cmdKOpen: false,
  openCmdK: () => set({cmdKOpen: true}),
  closeCmdK: () => set({cmdKOpen: false}),
  toggleCmdK: () => set((s) => ({cmdKOpen: !s.cmdKOpen})),

  cap: null,
  setCap: (c) => set({cap: c}),
  capToastDismissed: false,
  dismissCapToast: () => set({capToastDismissed: true}),

  capModalOpen: false,
  openCapModal: () => set({capModalOpen: true}),
  closeCapModal: () => set({capModalOpen: false}),
}));
