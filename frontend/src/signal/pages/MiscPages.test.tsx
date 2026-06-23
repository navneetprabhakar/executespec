import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {SharedPage} from './SharedPages';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
  getSharedProjectSurface: vi.fn(),
  getSharedReport: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/stores/useAuthStore', () => ({
  selectIsSuperAdmin: () => false,
  useAuthStore: () => null,
}));
vi.mock('@/signal/components/Compose', () => ({
  ScoreCard: ({
    verdict,
    score,
    cells,
  }: {
    verdict: string;
    score: number;
    cells: Array<{v: string}>;
  }) => (
    <div>
      <strong>{verdict}</strong>
      <span>{score}</span>
      {cells.map((cell, i) => (
        <span key={i}>{cell.v}</span>
      ))}
    </div>
  ),
  PrCard: ({title, repo}: {title: string; repo: string}) => (
    <div>
      <span>{title}</span>
      <span>{repo}</span>
    </div>
  ),
}));

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.getSharedProjectSurface.mockRejectedValue(new Error('not a project share'));
  api.getSharedReport.mockResolvedValue({
    verifiedScore: 92,
    userRequest: 'Build a public report smoke',
    testsPassed: 3,
    testsTotal: 3,
    totalCost: 0.42,
    prUrl: 'https://github.com/acme/app/pull/12',
  });
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

describe('SharedPages', () => {
  it('renders legacy shared report links through the public report endpoint', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter
          initialEntries={['/reports/shared/share-token']}
          future={{v7_relativeSplatPath: true, v7_startTransition: true}}
        >
          <Routes>
            <Route path='/reports/shared/:token' element={<SharedPage />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(api.getSharedProjectSurface).not.toHaveBeenCalled();
    expect(api.getSharedReport).toHaveBeenCalledWith('share-token');
    expect(host.textContent).toContain('Public report');
    expect(host.textContent).toContain('Build a public report smoke');
    expect(host.textContent).toContain('92');
    expect(host.textContent).toContain('3/3');
  });
});
