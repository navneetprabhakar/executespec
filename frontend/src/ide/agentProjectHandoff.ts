export interface AgentProjectHandoff {
  id: string;
  sourceSessionId: string | null;
  draft: string;
  provider: string | null;
  model: string | null;
  returnPath: string;
  createdAt: number;
}

const PREFIX = 'es:agent:project-handoff:';
const MAX_AGE_MS = 30 * 60 * 1000;

function mintId(): string {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `handoff-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export function createAgentProjectHandoff(
  input: Omit<AgentProjectHandoff, 'id' | 'createdAt'>,
): AgentProjectHandoff {
  const handoff = {...input, id: mintId(), createdAt: Date.now()};
  sessionStorage.setItem(`${PREFIX}${handoff.id}`, JSON.stringify(handoff));
  return handoff;
}

export function readAgentProjectHandoff(id: string | null): AgentProjectHandoff | null {
  if (!id) return null;
  const key = `${PREFIX}${id}`;
  const raw = sessionStorage.getItem(key);
  if (!raw) return null;
  try {
    const handoff = JSON.parse(raw) as AgentProjectHandoff;
    if (
      handoff.id !== id ||
      typeof handoff.returnPath !== 'string' ||
      typeof handoff.draft !== 'string' ||
      Date.now() - handoff.createdAt > MAX_AGE_MS
    ) {
      sessionStorage.removeItem(key);
      return null;
    }
    return handoff;
  } catch {
    sessionStorage.removeItem(key);
    return null;
  }
}

export function consumeAgentProjectHandoff(id: string | null): AgentProjectHandoff | null {
  const handoff = readAgentProjectHandoff(id);
  if (id) sessionStorage.removeItem(`${PREFIX}${id}`);
  return handoff;
}
