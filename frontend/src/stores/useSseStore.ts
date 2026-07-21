import {create} from 'zustand';

export type SseConnectionStatus =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'RECONNECTING'
  // RR-202/RR-203 — retry budget exhausted or a required scoped token could not
  // be obtained. Distinct from RECONNECTING so the UI can stop implying an
  // imminent recovery and rely on the background poll instead.
  | 'ERROR';

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
  replayNotice: string | null;
  /**
   * PR-C (2026-05-13) — monotonic counter bumped on every `FILE_WRITTEN` SSE event for the
   * active run. RunDetail's file-tree subscribes via `useSseStore(s => s.fileWrittenTickAt)` and
   * triggers a debounced re-fetch on change, so the tree updates the moment a tool call lands
   * (~100ms) instead of waiting for the 5s polling fallback.
   *
   * <p>Value is the unix-ms timestamp of the most recent event. Components debounce client-side
   * (~300ms) so a burst of 30 writes in a hot iteration only fires one re-fetch.
   */
  fileWrittenTickAt: number;
  setStatus: (s: SseConnectionStatus) => void;
  setRunId: (id: string | null) => void;
  addEntry: (e: LogEntry) => void;
  hydrateEntries: (entries: LogEntry[], notice: string | null) => void;
  clearEntries: () => void;
  /** PR-C — called by useSse on every FILE_WRITTEN event. */
  bumpFileWrittenTick: () => void;
}

export const useSseStore = create<SseStore>((set) => ({
  status: 'DISCONNECTED',
  entries: [],
  currentRunId: null,
  replayNotice: null,
  fileWrittenTickAt: 0,

  setStatus: (status) => set({status}),
  setRunId: (currentRunId) => set({currentRunId}),
  addEntry: (e) =>
    set((state) => {
      const withoutDuplicate = state.entries.filter((entry) => entry.id !== e.id);
      return {entries: [...withoutDuplicate.slice(-499), e]};
    }),
  hydrateEntries: (entries, replayNotice) =>
    set((state) => {
      const merged = new Map<string, LogEntry>();
      for (const entry of [...entries, ...state.entries]) merged.set(entry.id, entry);
      return {entries: [...merged.values()].slice(-500), replayNotice};
    }),
  clearEntries: () => set({entries: [], replayNotice: null}),
  bumpFileWrittenTick: () => set({fileWrittenTickAt: Date.now()}),
}));
