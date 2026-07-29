import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import IdeRunScreens from './IdeRunScreens';
import type {PreviewRenderManifest} from '@/lib/api/core';
import * as reviews from '@/lib/api/reviews';

const absentManifest: PreviewRenderManifest = {
  jobId: null,
  runId: 'run-failed-plan',
  status: 'NONE' as const,
  failureReason: null,
  surfacesFound: 0,
  surfacesRendered: 0,
  fidelityNote: null,
  requestedAt: null,
  completedAt: null,
  surfaces: [],
  available: true,
};

vi.mock('@/lib/api/reviews', () => ({
  getPreviewRender: vi.fn(),
  getPreviewRenderSurfaceHtml: vi.fn(),
  startPreviewRender: vi.fn(),
}));

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  vi.mocked(reviews.getPreviewRender).mockReset();
  vi.mocked(reviews.getPreviewRenderSurfaceHtml).mockReset();
  vi.mocked(reviews.startPreviewRender).mockReset();
  vi.mocked(reviews.getPreviewRender).mockResolvedValue(absentManifest);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

describe('IdeRunScreens evidence gate', () => {
  it('recovers an initial manifest failure without reloading the route', async () => {
    vi.mocked(reviews.getPreviewRender)
      .mockRejectedValueOnce(new Error('manifest unavailable'))
      .mockResolvedValue({...absentManifest, renderAllowed: true});

    await act(async () => {
      root.render(<IdeRunScreens runId='retry-run' hasProducedWorkspace={true} />);
      await Promise.resolve();
    });

    expect(host.querySelector('[role="alert"]')?.textContent).toContain('manifest unavailable');
    expect(host.textContent).not.toContain('See what you built');

    await act(async () => {
      const retry = Array.from(host.querySelectorAll('button')).find((candidate) =>
        candidate.textContent?.includes('Retry loading screens'),
      );
      retry?.click();
      await Promise.resolve();
    });

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.textContent).toContain('Render screens');
    expect(reviews.getPreviewRender).toHaveBeenCalledTimes(2);
  });

  it('recovers a selected surface failure and preserves the opaque-origin iframe sandbox', async () => {
    vi.mocked(reviews.getPreviewRender).mockResolvedValue({
      ...absentManifest,
      runId: 'surface-run',
      jobId: 'job-1',
      status: 'READY',
      surfacesFound: 1,
      surfacesRendered: 1,
      renderAllowed: true,
      surfaces: [
        {
          surfaceId: 'home',
          sourcePath: 'src/Home.tsx',
          displayName: 'Home',
          target: 'WEB',
          fidelity: 'STRUCTURAL',
          viewportWidth: 1280,
          viewportHeight: 800,
          rendered: true,
          failureReason: null,
        },
      ],
    });
    vi.mocked(reviews.getPreviewRenderSurfaceHtml)
      .mockRejectedValueOnce(new Error('surface unavailable'))
      .mockResolvedValue('<html><body>Recovered</body></html>');

    await act(async () => {
      root.render(<IdeRunScreens runId='surface-run' hasProducedWorkspace={true} />);
      await Promise.resolve();
      await Promise.resolve();
    });

    await vi.waitFor(() => {
      expect(host.querySelector('[role="alert"]')?.textContent).toContain('surface unavailable');
    });

    await act(async () => {
      const retry = Array.from(host.querySelectorAll('button')).find((candidate) =>
        candidate.textContent?.includes('Retry loading screen'),
      );
      retry?.click();
      await Promise.resolve();
    });

    await vi.waitFor(() => {
      const iframe = host.querySelector('iframe');
      expect(iframe?.getAttribute('sandbox')).toBe('allow-scripts');
      expect(iframe?.getAttribute('srcdoc')).toContain('Recovered');
    });
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(reviews.getPreviewRenderSurfaceHtml).toHaveBeenCalledTimes(2);
  });

  it('does not offer a render when the run produced no product files', async () => {
    vi.mocked(reviews.getPreviewRender).mockResolvedValue({
      ...absentManifest,
      renderAllowed: false,
    });

    await act(async () => {
      root.render(
        <IdeRunScreens
          runId='run-failed-plan'
          hasProducedWorkspace={false}
          unavailableReason='This run stopped before execution and produced no code changes.'
        />,
      );
      await Promise.resolve();
    });

    expect(host.textContent).toContain('No generated product files are available.');
    expect(host.textContent).toContain(
      'This run stopped before execution and produced no code changes.',
    );
    expect(host.textContent).not.toContain('Render screens');
    expect(host.textContent).not.toContain('See what you built');
    expect(reviews.startPreviewRender).not.toHaveBeenCalled();
  });

  it('uses the admitted-baseline server gate instead of a clean current git status', async () => {
    vi.mocked(reviews.getPreviewRender).mockResolvedValue({
      ...absentManifest,
      renderAllowed: true,
    });

    await act(async () => {
      root.render(<IdeRunScreens runId='brownfield-run' hasProducedWorkspace={false} />);
      await Promise.resolve();
    });

    expect(host.textContent).toContain('Render screens');
    expect(host.textContent).not.toContain('No generated product files are available.');
  });

  it('keeps an existing active job visible even if local evidence says no files', async () => {
    vi.mocked(reviews.getPreviewRender).mockResolvedValue({
      ...absentManifest,
      jobId: 'job-1',
      status: 'RENDERING',
      renderAllowed: false,
    });

    await act(async () => {
      root.render(<IdeRunScreens runId='active-run' hasProducedWorkspace={false} />);
      await Promise.resolve();
    });

    expect(host.textContent).toContain('Rendering…');
    expect(host.textContent).toContain('Drawing your screens.');
    expect(host.textContent).not.toContain('No generated product files are available.');
  });

  it('gives feature-off precedence over the no-product-files state', async () => {
    vi.mocked(reviews.getPreviewRender).mockResolvedValue({
      ...absentManifest,
      available: false,
      renderAllowed: false,
    });

    await act(async () => {
      root.render(<IdeRunScreens runId='disabled-run' hasProducedWorkspace={false} />);
      await Promise.resolve();
    });

    expect(host.textContent).toContain('Screen rendering is turned off for this deployment.');
    expect(host.textContent).not.toContain('No generated product files are available.');
    expect(host.textContent).not.toContain('See what you built');
  });

  it('shows loading without making an early empty-state claim', async () => {
    let resolveManifest: ((value: typeof absentManifest) => void) | undefined;
    vi.mocked(reviews.getPreviewRender).mockImplementation(
      () => new Promise((resolve) => (resolveManifest = resolve)),
    );

    await act(async () => {
      root.render(<IdeRunScreens runId='slow-run' hasProducedWorkspace={false} />);
      await Promise.resolve();
    });

    expect(host.textContent).toContain('Checking screen availability…');
    expect(host.textContent).not.toContain('No generated screens');

    await act(async () => {
      resolveManifest?.({...absentManifest, renderAllowed: false});
      await Promise.resolve();
    });
    expect(host.textContent).toContain('No generated screens');
  });
});
