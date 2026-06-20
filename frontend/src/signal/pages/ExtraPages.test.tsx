import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {AcceptInvitePage, PublicPricingPage, TrustPage} from './ExtraPages';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
  acceptInvite: vi.fn(),
  acceptProjectInvite: vi.fn(),
}));

vi.mock('@/lib/api', () => api);

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.acceptInvite.mockResolvedValue(undefined);
  api.acceptProjectInvite.mockResolvedValue({projectId: 'p1'});
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

describe('ExtraPages', () => {
  it('keeps the public trust copy honest about current certifications', async () => {
    await renderPublic(<TrustPage />);

    expect(host.textContent).toContain('Trust & security');
    expect(host.textContent).toContain('SOC 2 Type II is on our roadmap');
    expect(host.textContent).toContain('Training on your code');
    expect(host.textContent).toContain('Never');
  });

  it('keeps the public pricing tiers aligned to the launch ladder', async () => {
    await renderPublic(<PublicPricingPage />);

    expect(host.textContent).toContain('FREE');
    expect(host.textContent).toContain('PRO');
    expect(host.textContent).toContain('$29');
    expect(host.textContent).toContain('TEAM');
    expect(host.textContent).toContain('$39');
    expect(host.textContent).toContain('ENTERPRISE');
    expect(host.textContent).toContain('1 credit ≈ $5 of model spend');
  });

  it('accepts project invite links through the project invite endpoint', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter
          initialEntries={['/invites/accept?token=project-token']}
          future={{v7_relativeSplatPath: true, v7_startTransition: true}}
        >
          <Routes>
            <Route path='/invites/accept' element={<AcceptInvitePage kind='project' />} />
            <Route path='/projects/:projectId' element={<div>Project opened</div>} />
          </Routes>
        </MemoryRouter>,
      );
    });

    await clickButton('Accept & open project');

    expect(api.acceptProjectInvite).toHaveBeenCalledWith('project-token');
    expect(api.acceptInvite).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Project opened');
  });

  it('accepts org invite links through the org invite endpoint', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter
          initialEntries={['/accept-invite?token=org-token']}
          future={{v7_relativeSplatPath: true, v7_startTransition: true}}
        >
          <Routes>
            <Route path='/accept-invite' element={<AcceptInvitePage kind='org' />} />
            <Route path='/setup' element={<div>Setup opened</div>} />
          </Routes>
        </MemoryRouter>,
      );
    });

    await clickButton('Accept & continue');

    expect(api.acceptInvite).toHaveBeenCalledWith('org-token');
    expect(api.acceptProjectInvite).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Setup opened');
  });
});

async function renderPublic(element: React.ReactElement) {
  await act(async () => {
    root.render(
      <MemoryRouter future={{v7_relativeSplatPath: true, v7_startTransition: true}}>
        {element}
      </MemoryRouter>,
    );
  });
}

async function clickButton(label: string) {
  const button = Array.from(host.querySelectorAll('button')).find((el) =>
    el.textContent?.includes(label),
  );
  if (!button) throw new Error(`Button not found: ${label}`);
  await act(async () => {
    button.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true}));
    await Promise.resolve();
    await Promise.resolve();
  });
}
