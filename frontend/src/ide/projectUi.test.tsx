import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {
  ProjectBanner,
  ProjectChatMessageCard,
  ProjectField,
  ProjectMetaLabel,
  ProjectRouteTabs,
} from './projectUi';

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

describe('ProjectRouteTabs', () => {
  it('renders desktop tabs and closes the mobile menu after selection', () => {
    const onSelect = vi.fn();
    act(() => {
      root.render(
        <ProjectRouteTabs
          tabs={[
            {key: 'chat', label: 'Chat'},
            {key: 'artifacts', label: 'Artifacts'},
          ]}
          active='chat'
          onSelect={onSelect}
        />,
      );
    });

    expect(host.textContent).toContain('Chat');
    expect(host.textContent).toContain('Artifacts');

    const trigger = host.querySelector('.ide-project-mobile-trigger') as HTMLButtonElement;
    act(() => trigger.click());
    expect(host.querySelector('[role="menu"]')).not.toBeNull();

    const menuItems = host.querySelectorAll('[role="menuitem"]');
    act(() => (menuItems[1] as HTMLButtonElement).click());

    expect(onSelect).toHaveBeenCalledWith('artifacts');
    expect(host.querySelector('[role="menu"]')).toBeNull();
  });
});

describe('ProjectBanner', () => {
  it('prefers error copy over success copy and renders nothing when empty', () => {
    act(() => root.render(<ProjectBanner err='Failed' msg='Saved' />));
    expect(host.textContent).toContain('Failed');
    expect(host.textContent).not.toContain('Saved');

    act(() => root.render(<ProjectBanner />));
    expect(host.textContent).toBe('');
  });
});

describe('ProjectMetaLabel', () => {
  it('renders metric labels', () => {
    act(() => root.render(<ProjectMetaLabel>Branch</ProjectMetaLabel>));

    expect(host.textContent).toContain('Branch');
  });
});

describe('ProjectField', () => {
  it('renders a form label and child control', () => {
    act(() =>
      root.render(
        <ProjectField label='Target branch'>
          <input aria-label='branch' defaultValue='main' />
        </ProjectField>,
      ),
    );

    expect(host.textContent).toContain('Target branch');
    expect((host.querySelector('input') as HTMLInputElement).value).toBe('main');
  });
});

describe('ProjectChatMessageCard', () => {
  it('collapses long assistant artifacts and expands on demand', () => {
    const content = `## Artifact\n${'long artifact body '.repeat(90)}TAIL_SENTINEL`;
    act(() => {
      root.render(
        <ProjectChatMessageCard
          message={{role: 'assistant', content, ts: '2026-06-20T00:00:00Z'}}
        />,
      );
    });

    expect(host.textContent).toContain('Assistant artifact');
    expect(host.textContent).toContain('Expand');
    expect(host.textContent).not.toContain('TAIL_SENTINEL');

    const button = host.querySelector('button') as HTMLButtonElement;
    act(() => button.click());

    expect(host.textContent).toContain('Collapse');
    expect(host.textContent).toContain('TAIL_SENTINEL');
  });
});
