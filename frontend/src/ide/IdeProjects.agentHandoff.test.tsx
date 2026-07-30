import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes, useLocation} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {createAgentProjectHandoff, readAgentProjectHandoff} from './agentProjectHandoff';
import IdeProjects from './IdeProjects';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
  getProjects: vi.fn(),
  createProject: vi.fn(),
  getProjectDashboard: vi.fn(),
  getTechDebt: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/ide/useProviderReadiness', () => ({
  useProviderReadiness: () => ({
    rows: [
      {
        provider: 'DEEPSEEK',
        state: 'AVAILABLE_MANAGED',
        dispatchable: true,
      },
    ],
    byProvider: new Map([
      ['DEEPSEEK', {provider: 'DEEPSEEK', state: 'AVAILABLE_MANAGED', dispatchable: true}],
    ]),
    loading: false,
    error: false,
    supported: true,
  }),
}));
vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: (selector?: (state: unknown) => unknown) =>
    typeof selector === 'function'
      ? selector({user: {globalRole: 'DEVELOPER'}})
      : {user: {globalRole: 'DEVELOPER'}},
  selectIsSuperAdmin: () => false,
}));

function LocationProbe() {
  const location = useLocation();
  return <div data-testid='location'>{location.pathname + location.search}</div>;
}

describe('IdeProjects agent handoff', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  beforeEach(() => {
    vi.clearAllMocks();
    const values = new Map<string, string>();
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
    });
    api.getProjects.mockResolvedValue([]);
    api.createProject.mockResolvedValue({id: 'project-new'});
  });

  afterEach(() => {
    act(() => root?.unmount());
    host?.remove();
    vi.unstubAllGlobals();
  });

  async function render(path: string) {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    await act(async () => {
      root?.render(
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path='/projects' element={<IdeProjects />} />
            <Route path='/projects/:projectId/agent' element={<LocationProbe />} />
            <Route path='/agent' element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
    });
  }

  it('creates once through the canonical project form and returns the handoff to agent binding', async () => {
    const handoff = createAgentProjectHandoff({
      sourceSessionId: 'session-1',
      draft: 'Add project tests',
      provider: null,
      model: null,
      returnPath: '/agent',
    });
    await render(`/projects?agentProjectHandoff=${handoff.id}`);

    const newProject = [...host!.querySelectorAll<HTMLButtonElement>('button')].find((button) =>
      button.textContent?.includes('New project'),
    );
    await act(async () => newProject?.click());
    const input = host!.querySelector<HTMLInputElement>('input[placeholder="Project name"]');
    if (!input) throw new Error('Project name input was not rendered.');
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(input, 'Agent project');
      input.dispatchEvent(new Event('input', {bubbles: true}));
    });
    const create = [...host!.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent === 'Create',
    );
    await act(async () => create?.click());

    expect(api.createProject).toHaveBeenCalledTimes(1);
    expect(api.createProject).toHaveBeenCalledWith({
      name: 'Agent project',
      aiProvider: 'DEEPSEEK',
    });
    expect(host!.textContent).toContain(
      `/projects/project-new/agent?agentProjectHandoff=${handoff.id}`,
    );
  });

  it('cancels back to the original conversation without consuming its draft handoff', async () => {
    const handoff = createAgentProjectHandoff({
      sourceSessionId: 'session-1',
      draft: 'Keep this draft',
      provider: null,
      model: null,
      returnPath: '/agent',
    });
    await render(`/projects?agentProjectHandoff=${handoff.id}`);

    const cancel = [...host!.querySelectorAll<HTMLButtonElement>('button')].find((button) =>
      button.textContent?.includes('Cancel and return'),
    );
    await act(async () => cancel?.click());

    expect(host!.textContent).toContain('/agent');
    expect(readAgentProjectHandoff(handoff.id)?.draft).toBe('Keep this draft');
    expect(api.createProject).not.toHaveBeenCalled();
  });
});
