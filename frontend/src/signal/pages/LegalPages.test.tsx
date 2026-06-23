import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {DpaPage, PrivacyPage, TermsPage} from './LegalPages';

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

describe('LegalPages', () => {
  it('renders the public terms page copy', async () => {
    await render(<TermsPage />);

    expect(host.textContent).toContain('Terms of Service');
    expect(host.textContent).toContain('Acceptable use');
    expect(host.textContent).toContain('We do not train foundation models on your code.');
  });

  it('renders the public privacy page copy', async () => {
    await render(<PrivacyPage />);

    expect(host.textContent).toContain('Privacy Policy');
    expect(host.textContent).toContain('No advertising cookies.');
    expect(host.textContent).toContain('privacy@executespec.ai');
  });

  it('renders the public DPA page copy', async () => {
    await render(<DpaPage />);

    expect(host.textContent).toContain('Data Processing Addendum');
    expect(host.textContent).toContain('GDPR Article 28');
    expect(host.textContent).toContain('Breach notification');
  });
});

async function render(element: React.ReactElement) {
  await act(async () => {
    root.render(
      <MemoryRouter future={{v7_relativeSplatPath: true, v7_startTransition: true}}>
        {element}
      </MemoryRouter>,
    );
  });
}
