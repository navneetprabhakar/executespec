import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const api = vi.hoisted(() => ({
  listBenchmarks: vi.fn(),
  getBenchmark: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/stores/useAuthStore', () => ({
  selectIsSuperAdmin: (state: {user?: {globalRole?: string} | null}) =>
    state.user?.globalRole === 'SUPER_ADMIN',
  useAuthStore: (selector: (state: {user: {globalRole: string}}) => unknown) =>
    selector({user: {globalRole: 'SUPER_ADMIN'}}),
}));

import IdeBenchmark from './IdeBenchmark';

let host: HTMLDivElement;
let root: Root;

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.listBenchmarks.mockResolvedValue([
    {
      id: 'run-1',
      suite: 'SWE_BENCH',
      dataset: 'swebench_lite_test deepseek-v4-pro-native k-eval 2026-07-18 pass1',
      provider: 'deepseek-native-provider-with-a-long-identifier',
      model: 'deepseek-v4-pro-native',
      k: 1,
      totalTasks: 10,
      resolvedTasks: 7,
      status: 'DONE',
      costUsd: 1.25,
      inputTokens: 12345,
      outputTokens: 678,
      createdAt: '2026-07-18T08:00:00Z',
      finishedAt: '2026-07-18T08:05:00Z',
    },
  ]);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

describe('IdeBenchmark mobile run cards', () => {
  it('renders a readable card with the run identity and metrics', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/benchmark']}>
          <Routes>
            <Route path='/benchmark' element={<IdeBenchmark />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
    });

    const card = host.querySelector('.ide-benchmark-mobile-card');
    expect(card).not.toBeNull();
    const detailButton = card?.querySelector('button');
    expect(detailButton?.getAttribute('aria-label')).toContain('deepseek-v4-pro-native');
    expect(detailButton?.getAttribute('type')).toBe('button');
    expect(card?.textContent).toContain('swebench_lite_test');
    expect(card?.textContent).toContain('7/10');
    expect(card?.textContent).toContain('$1.2500');
    expect(card?.textContent).toContain('12K↑ 678↓');
  });
});
