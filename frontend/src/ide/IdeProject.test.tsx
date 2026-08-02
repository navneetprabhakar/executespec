import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import IdeProject from './IdeProject';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
  getProject: vi.fn(),
  getProjectRepos: vi.fn(),
  getRuns: vi.fn(),
  createRun: vi.fn(),
  getProviderReadiness: vi.fn(),
  getMyModels: vi.fn(),
}));

let authTier = 'PRO';

vi.mock('@/lib/api', () => api);
vi.mock('@/stores/useAuthStore', () => ({
  selectNormalizedTier: (state: {tier: string}) => state.tier,
  useAuthStore: (selector: (state: {tier: string}) => unknown) => selector({tier: authTier}),
}));
vi.mock('@/ide/IdeProjectTabs', () => ({ProjectTabs: () => <div>Project navigation</div>}));
vi.mock('@/signal/components/SpecChat', () => ({SpecChat: () => <div>Spec chat</div>}));

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.getProject.mockResolvedValue({
    id: 'project-1',
    name: 'Brownfield API',
    aiProvider: 'deepseek',
    status: 'ACTIVE',
  });
  api.getProjectRepos.mockResolvedValue([{repoId: 'repo-1', fullName: 'org/service'}]);
  api.getRuns.mockResolvedValue({content: []});
  api.getProviderReadiness.mockResolvedValue({
    providers: [
      {provider: 'ANTHROPIC', state: 'UNAVAILABLE', dispatchable: false},
      {provider: 'OPENAI', state: 'UNAVAILABLE', dispatchable: false},
      {provider: 'DEEPSEEK', state: 'AVAILABLE_MANAGED', dispatchable: true},
    ],
  });
  api.createRun.mockResolvedValue({runId: 'run-1'});
  api.getMyModels.mockResolvedValue([
    {
      id: 'deepseek-v4-pro-native',
      provider: 'DEEPSEEK',
      label: 'DeepSeek V4 Pro',
      minTier: 'ENTERPRISE',
      available: true,
    },
  ]);
  authTier = 'PRO';
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function renderProject() {
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={[{pathname: '/projects/project-1', state: {compose: true}}]}>
        <Routes>
          <Route path='/projects/:projectId' element={<IdeProject />} />
          <Route path='/live/:runId' element={<div>Live run</div>} />
          {/* RR-1519 — PRODUCTION runs pause at the plan gate, so the composer routes there. */}
          <Route path='/plan/:runId' element={<div>Plan gate</div>} />
        </Routes>
      </MemoryRouter>,
    );
  });
  await flush();
}

describe('IdeProject brownfield compose contract', () => {
  it('hydrates the project provider and submits the explicit linked-repository boundary', async () => {
    await renderProject();

    const provider = host.querySelector(
      'select[aria-label="Project run provider"]',
    ) as HTMLSelectElement;
    expect(provider.value).toBe('DEEPSEEK');
    expect(host.textContent).toContain('Repos selected');
    expect(host.textContent).toContain('org/service');

    const textarea = host.querySelector('textarea') as HTMLTextAreaElement;
    await act(async () => {
      const setValue = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setValue?.call(textarea, 'Fix the failing repository test');
      textarea.dispatchEvent(new Event('input', {bubbles: true}));
    });

    const start = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Start work',
    ) as HTMLButtonElement;
    await act(async () => start.click());
    await flush();

    expect(api.createRun).toHaveBeenCalledWith(
      expect.objectContaining({
        projectId: 'project-1',
        aiProvider: 'DEEPSEEK',
        selectedRepoIds: ['repo-1'],
        userRequest: 'Fix the failing repository test',
      }),
      expect.any(String),
    );
    // RR-1519 — the default project composer mode is PRODUCTION, which pauses at AWAITING_APPROVAL,
    // so submission must land on the plan gate, not the live console.
    expect(host.textContent).toContain('Plan gate');
  });

  it('shows a non-actionable not-found state when the project cannot be loaded', async () => {
    api.getProject.mockRejectedValue({status: 404});
    await renderProject();
    expect(host.textContent).toContain('Project not found');
    expect(host.textContent).not.toContain('Start work in this project');
  });

  it('offers Enterprise exact-model selection and sends the pin for this run only', async () => {
    authTier = 'ENTERPRISE';
    await renderProject();

    const modelTrigger = host.querySelector(
      'button[aria-label="Project run model"]',
    ) as HTMLButtonElement;
    expect(modelTrigger).toBeTruthy();
    await act(async () => modelTrigger.click());
    const modelOption = Array.from(
      host.querySelectorAll<HTMLButtonElement>('[role="option"]'),
    ).find((option) => option.textContent?.includes('DeepSeek V4 Pro'));
    await act(async () => modelOption?.click());

    const textarea = host.querySelector('textarea') as HTMLTextAreaElement;
    await act(async () => {
      const setValue = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setValue?.call(textarea, 'Add a typed health endpoint');
      textarea.dispatchEvent(new Event('input', {bubbles: true}));
    });
    const start = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Start work',
    ) as HTMLButtonElement;
    await act(async () => start.click());
    await flush();

    expect(api.createRun).toHaveBeenCalledWith(
      expect.objectContaining({modelName: 'deepseek-v4-pro-native'}),
      expect.any(String),
    );
  });
});
