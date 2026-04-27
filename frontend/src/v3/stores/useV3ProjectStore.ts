import { create } from 'zustand';
import {
  getProject,
  getRuns,
  type ProjectDto,
  type RunSummary,
} from '@/lib/api';

/**
 * R-120 — per-project chat-thread state.
 *
 * The conversation thread is the source of truth for the project home.
 * Each bubble is either:
 *   - role=user: a spec the user submitted
 *   - role=assistant + kind=run: a run-shaped bubble that morphs as the
 *     run progresses (planning → plan-ready → working → result)
 *   - role=assistant + kind=status: a transient status update (rare)
 *
 * Bubbles can carry a `replacesId` so an optimistic bubble can be
 * swapped for a real-runId one in place without a list re-flow.
 *
 * Existing project runs are seeded by reverse-scanning /api/runs?projectId=…
 * so a returning user immediately sees the conversation history.
 */
export type BubbleRole = 'user' | 'assistant';
export type BubbleKind = 'text' | 'run' | 'status';

export interface Bubble {
  id: string;
  role: BubbleRole;
  kind?: BubbleKind;
  /** For text/status bubbles. */
  text?: string;
  /** For run bubbles. */
  runId?: string;
  status?: string;
  /** ID of a prior bubble this one replaces (e.g. tmp → real). */
  replacesId?: string;
  createdAt: string;
}

interface V3ProjectStore {
  loaded: string | null; // projectId currently loaded; null if none
  loading: boolean;
  project: ProjectDto | null;
  bubbles: Bubble[];
  error: string | null;
  load: (projectId: string) => Promise<void>;
  pushBubble: (b: Bubble) => void;
  reset: () => void;
}

export const useV3ProjectStore = create<V3ProjectStore>((set, get) => ({
  loaded: null,
  loading: false,
  project: null,
  bubbles: [],
  error: null,

  async load(projectId: string) {
    if (get().loaded === projectId && !get().loading) return;
    set({ loading: true, error: null, bubbles: [], project: null, loaded: projectId });
    try {
      const [project, runs] = await Promise.all([
        getProject(projectId).catch(() => null),
        getRuns({ projectId, page: 0, size: 30 }).catch(() => ({ content: [] as RunSummary[] })),
      ]);
      // Seed bubbles oldest-first from existing runs. Each run becomes a
      // user bubble (the spec) + an assistant bubble (the run result).
      const bubbles: Bubble[] = [];
      const ordered = [...(runs.content ?? [])].sort((a, b) => {
        const da = new Date(a.startedAt).getTime();
        const db = new Date(b.startedAt).getTime();
        return da - db;
      });
      for (const r of ordered) {
        bubbles.push({
          id: `${r.runId}-spec`,
          role: 'user',
          kind: 'text',
          text: r.runTitle ?? r.userRequest ?? '(no spec recorded)',
          createdAt: r.startedAt,
        });
        bubbles.push({
          id: `${r.runId}-result`,
          role: 'assistant',
          kind: 'run',
          runId: r.runId,
          status: r.status,
          createdAt: r.completedAt ?? r.startedAt,
        });
      }
      set({ project, bubbles, loading: false });
    } catch (e) {
      set({
        error: (e as Error)?.message ?? 'Failed to load project',
        loading: false,
      });
    }
  },

  pushBubble(b: Bubble) {
    set((s) => {
      if (b.replacesId) {
        const idx = s.bubbles.findIndex((x) => x.id === b.replacesId);
        if (idx >= 0) {
          const next = s.bubbles.slice();
          next[idx] = { ...b, id: b.id };
          return { bubbles: next };
        }
      }
      return { bubbles: [...s.bubbles, b] };
    });
  },

  reset() {
    set({ loaded: null, project: null, bubbles: [], error: null, loading: false });
  },
}));
