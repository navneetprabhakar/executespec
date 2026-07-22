import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {useAsyncData} from './loadState';

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

/** Renders `useAsyncData(key)` into the DOM so the test can read data/loading. */
function Probe({scope, resolve}: {scope: string; resolve: (scope: string) => Promise<string>}) {
  const {data, loading} = useAsyncData(() => resolve(scope), [scope]);
  return (
    <div>
      <span data-testid='data'>{data ?? 'null'}</span>
      <span data-testid='loading'>{loading ? 'loading' : 'idle'}</span>
    </div>
  );
}

describe('useAsyncData (RR-1423)', () => {
  it('clears the previous scope value when the dependency key changes', async () => {
    // A controllable per-scope loader so we can inspect the interim state before
    // the second scope resolves.
    const pending: Record<string, (v: string) => void> = {};
    const resolve = (scope: string) =>
      new Promise<string>((res) => {
        pending[scope] = res;
      });

    await act(async () => {
      root.render(<Probe scope='A' resolve={resolve} />);
    });
    await act(async () => {
      pending['A']('A-data');
      await Promise.resolve();
    });
    expect(host.querySelector('[data-testid="data"]')?.textContent).toBe('A-data');

    // Navigate to scope B; B has not resolved yet.
    await act(async () => {
      root.render(<Probe scope='B' resolve={resolve} />);
      await Promise.resolve();
    });
    // RR-1423 — A's data must NOT still be showing under scope B.
    expect(host.querySelector('[data-testid="data"]')?.textContent).toBe('null');
    expect(host.querySelector('[data-testid="loading"]')?.textContent).toBe('loading');

    // Once B resolves, its own data appears.
    await act(async () => {
      pending['B']('B-data');
      await Promise.resolve();
    });
    expect(host.querySelector('[data-testid="data"]')?.textContent).toBe('B-data');
  });
});
