import {create} from 'zustand';

export type SseConnectionStatus = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING';

export interface LogEntry {
  id: string;
  type: string;
  data: Record<string, unknown>;
  timestamp: number;
}

interface SseStore {
  status: SseConnectionStatus;
  entries: LogEntry[];
  currentRunId: string | null;
  setStatus: (s: SseConnectionStatus) => void;
  setRunId: (id: string | null) => void;
  addEntry: (e: LogEntry) => void;
  clearEntries: () => void;
  // F-07 fix (2026-05-09) — full reset, used by useAuthStore.logout.
  reset: () => void;
}

export const useSseStore = create<SseStore>((set) => ({
  status: 'DISCONNECTED',
  entries: [],
  currentRunId: null,

  setStatus: (status) => set({status}),
  setRunId: (currentRunId) => set({currentRunId}),
  addEntry: (e) => set((state) => ({entries: [...state.entries.slice(-500), e]})),
  clearEntries: () => set({entries: []}),
  reset: () => set({status: 'DISCONNECTED', entries: [], currentRunId: null}),
}));
