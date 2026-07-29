import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {RunTechnicalDetails} from './RunTechnicalDetails';

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

describe('RunTechnicalDetails', () => {
  it('keeps exact route, token, action, and command diagnostics collapsed by default', () => {
    act(() => {
      root.render(
        <RunTechnicalDetails>
          <div>npm run build</div>
          <div>CHANGE_PROVIDER -&gt; openrouter/grok-4.20</div>
          <div>128,000 tokens</div>
        </RunTechnicalDetails>,
      );
    });

    const disclosure = host.querySelector('details');
    expect(disclosure).not.toBeNull();
    expect(disclosure?.open).toBe(false);
    expect(disclosure?.querySelector('summary')?.textContent).toBe('Technical details');
    expect(disclosure?.textContent).toContain('npm run build');
    expect(disclosure?.textContent).toContain('CHANGE_PROVIDER -> openrouter/grok-4.20');
    expect(disclosure?.textContent).toContain('128,000 tokens');
  });
});
