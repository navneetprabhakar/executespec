/**
 * Tiny global store for the agent input's contextual label.
 *
 * When a page sets a context (e.g. ProjectPage sets "Hostel Eval"), the
 * shell-mounted AgentInput shows "In <name>" above the textarea so users
 * know their submission is scoped to that project.
 *
 * The page resets the context on unmount.
 */
import {create} from 'zustand';

interface AgentContext {
  label?: string;
  projectId?: string;
  set: (ctx: {label?: string; projectId?: string}) => void;
  clear: () => void;
}

export const useAgentContext = create<AgentContext>((set) => ({
  label: undefined,
  projectId: undefined,
  set: (ctx) => set({label: ctx.label, projectId: ctx.projectId}),
  clear: () => set({label: undefined, projectId: undefined}),
}));
