import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {ArchDocView} from './projectArchitecturePanels';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

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

describe('ArchDocView', () => {
  it('renders small architecture docs expanded and toggles sections', () => {
    act(() => {
      root.render(
        <ArchDocView md={'Intro\n\n## Runtime\nRuntime body\n## Storage\nStorage body'} />,
      );
    });

    expect(host.textContent).toContain('Intro');
    expect(host.textContent).toContain('Runtime body');
    expect(host.textContent).toContain('Storage body');

    const buttons = host.querySelectorAll('button[aria-expanded]');
    expect(buttons).toHaveLength(2);
    act(() => {
      (buttons[0] as HTMLButtonElement).click();
    });
    expect(host.textContent).not.toContain('Runtime body');
    expect(host.textContent).toContain('Storage body');
  });

  it('collapses large architecture docs except the first section', () => {
    const sections = Array.from({length: 11}, (_, i) => `## Section ${i + 1}\nBody ${i + 1}`).join(
      '\n',
    );

    act(() => {
      root.render(<ArchDocView md={sections} />);
    });

    expect(host.textContent).toContain('Body 1');
    expect(host.textContent).not.toContain('Body 2');
    expect(host.textContent).toContain('Expand all');
  });
});
