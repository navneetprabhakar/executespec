import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {getPreviewRender, getPreviewRenderSurfaceHtml, startPreviewRender} from '@/lib/api/reviews';
import type {PreviewRenderManifest, PreviewRenderSurface} from '@/lib/api/core';

/**
 * Static UI preview.
 *
 * Renders the run's screens as self-contained HTML produced by the preview render agent. Nothing
 * is built and nothing is deployed, so this works on runs whose build is red — the case the
 * container preview cannot serve at all.
 *
 * The rendered document is model-authored and therefore untrusted. It is mounted via `srcdoc` on a
 * sandbox that deliberately omits `allow-same-origin`, which puts it on an opaque origin with no
 * access to this app's DOM, cookies, or storage.
 */

const POLL_MS = 3000;

/** Device chrome dimensions per target, so a phone screen doesn't render as a browser page. */
const VIEWPORTS = [
  {id: 'auto', label: 'Default'},
  {id: 'desktop', label: 'Desktop', width: 1280, height: 800},
  {id: 'tablet', label: 'Tablet', width: 768, height: 1024},
  {id: 'phone', label: 'Phone', width: 390, height: 844},
] as const;

type ViewportId = (typeof VIEWPORTS)[number]['id'];

const TARGET_LABEL: Record<PreviewRenderSurface['target'], string> = {
  WEB: 'Web',
  REACT_NATIVE: 'React Native',
  ANDROID_NATIVE: 'Android',
  IOS_NATIVE: 'iOS',
};

/** Why a surface failed, in words an operator can act on. */
function failureText(reason: string | null): string {
  if (!reason) return 'This screen could not be rendered.';
  if (reason.startsWith('CREDENTIAL_IN_BUNDLE')) {
    return 'Blocked: the render contained something shaped like a credential, so it was discarded.';
  }
  if (reason === 'EXTERNAL_NETWORK_REFERENCE') {
    return 'Blocked: the render referenced an external resource, so it was discarded.';
  }
  if (reason.startsWith('FORBIDDEN_API')) {
    return 'Blocked: the render tried to use a network or storage API, so it was discarded.';
  }
  if (reason === 'TIMEOUT') return 'The model did not finish this screen in time.';
  if (reason === 'BUNDLE_TOO_LARGE') return 'The render came back too large to display.';
  if (reason === 'NOT_HTML') return 'The model did not return a usable HTML document.';
  if (reason === 'AI_CREDITS_REQUIRED') {
    return 'This screen was not rendered because managed AI credits are unavailable.';
  }
  if (reason === 'BUDGET_EXHAUSTED') {
    return 'This screen was not rendered because its provider budget was exhausted.';
  }
  if (reason === 'PROVIDER_CREDENTIAL_UNAVAILABLE') {
    return 'This screen was not rendered because the selected provider credential is unavailable.';
  }
  if (reason === 'PROVIDER_ROUTE_UNAVAILABLE') {
    return 'This screen was not rendered because the run’s provider route is unavailable.';
  }
  return 'This screen could not be rendered.';
}

export default function IdeRunScreens({
  runId,
  hasProducedWorkspace,
  unavailableReason,
}: {
  runId: string;
  hasProducedWorkspace: boolean;
  unavailableReason?: string;
}) {
  const [manifest, setManifest] = useState<PreviewRenderManifest | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [html, setHtml] = useState<string | null>(null);
  const [viewport, setViewport] = useState<ViewportId>('auto');
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [loadingManifest, setLoadingManifest] = useState(true);
  const [loadingSurface, setLoadingSurface] = useState(false);
  const pollRef = useRef<number | null>(null);
  const activeRunRef = useRef(runId);

  const inFlight = manifest?.status === 'QUEUED' || manifest?.status === 'RENDERING';

  const load = useCallback(async () => {
    try {
      const next = await getPreviewRender(runId);
      if (activeRunRef.current !== runId) return null;
      setManifest(next);
      setError(null);
      return next;
    } catch (e) {
      if (activeRunRef.current !== runId) return null;
      setError(e instanceof Error ? e.message : 'Could not load the preview.');
      return null;
    }
  }, [runId]);

  useEffect(() => {
    activeRunRef.current = runId;
    setManifest(null);
    setSelected(null);
    setHtml(null);
    setError(null);
    setLoadingManifest(true);
    void load().finally(() => {
      if (activeRunRef.current === runId) setLoadingManifest(false);
    });
  }, [load, runId]);

  // Poll only while work is outstanding, then stop.
  useEffect(() => {
    if (!inFlight) {
      if (pollRef.current) {
        window.clearInterval(pollRef.current);
        pollRef.current = null;
      }
      return;
    }
    pollRef.current = window.setInterval(() => void load(), POLL_MS);
    return () => {
      if (pollRef.current) window.clearInterval(pollRef.current);
      pollRef.current = null;
    };
  }, [inFlight, load]);

  const rendered = useMemo(() => (manifest?.surfaces ?? []).filter((s) => s.rendered), [manifest]);

  // Select the first rendered screen once results arrive.
  useEffect(() => {
    if (!selected && rendered.length > 0) setSelected(rendered[0].surfaceId);
  }, [rendered, selected]);

  // Fetch the selected bundle as text; an iframe src cannot carry the auth header.
  useEffect(() => {
    if (!selected) {
      setHtml(null);
      return;
    }
    let cancelled = false;
    setLoadingSurface(true);
    getPreviewRenderSurfaceHtml(runId, selected)
      .then((text) => {
        if (!cancelled) setHtml(text);
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setHtml(null);
          setError(e instanceof Error ? e.message : 'Could not load that screen.');
        }
      })
      .finally(() => {
        if (!cancelled) setLoadingSurface(false);
      });
    return () => {
      cancelled = true;
    };
  }, [runId, selected]);

  const start = async () => {
    setStarting(true);
    setError(null);
    try {
      await startPreviewRender(runId);
      setSelected(null);
      setHtml(null);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not start the preview.');
    } finally {
      setStarting(false);
    }
  };

  const activeSurface = rendered.find((s) => s.surfaceId === selected) ?? null;
  const frame = frameSize(viewport, activeSurface);
  const failed = (manifest?.surfaces ?? []).filter((s) => !s.rendered);
  // The server uses the admitted run-start baseline. `hasProducedWorkspace` is retained only as a
  // compatibility fallback for older deployments that do not return renderAllowed yet.
  const renderAllowed = inFlight || (manifest?.renderAllowed ?? hasProducedWorkspace);

  return (
    <div className='ide-screens'>
      <div className='ide-screens-bar'>
        <div className='ide-screens-bar-left'>
          {loadingManifest ? (
            <span className='ide-screens-status'>Checking screen availability…</span>
          ) : manifest?.available === false ? (
            /* The flag is off for this deployment. Previously this rendered an always-enabled
               button whose 503 surfaced as "temporarily unavailable" — false for a feature that
               is simply disabled. Say what is actually true instead. */
            <span className='ide-screens-status'>
              Screen rendering is turned off for this deployment.
            </span>
          ) : !renderAllowed ? (
            <span className='ide-screens-status'>
              {manifest?.jobId
                ? 'Re-rendering is unavailable because no product-file change can be verified.'
                : 'No generated product files are available.'}
            </span>
          ) : (
            <button className='ide-btn' onClick={start} disabled={starting || inFlight}>
              {inFlight ? 'Rendering…' : manifest?.jobId ? 'Re-render' : 'Render screens'}
            </button>
          )}
          {inFlight && (
            <span className='ide-screens-status'>
              Drawing your screens. This does not build or deploy anything.
            </span>
          )}
        </div>

        {rendered.length > 0 && (
          <div className='ide-screens-viewports' role='group' aria-label='Viewport'>
            {VIEWPORTS.map((v) => (
              <button
                key={v.id}
                className={`ide-chip ${viewport === v.id ? 'is-active' : ''}`}
                onClick={() => setViewport(v.id)}
              >
                {v.label}
              </button>
            ))}
          </div>
        )}
      </div>

      {error && <div className='ide-screens-error'>{error}</div>}

      {manifest?.fidelityNote && rendered.length > 0 && (
        <p className='ide-screens-note'>{manifest.fidelityNote}</p>
      )}

      {!loadingManifest &&
        !error &&
        manifest?.available !== false &&
        !manifest?.jobId &&
        !inFlight &&
        renderAllowed && (
          <div className='ide-screens-empty'>
            <h3>See what you built</h3>
            <p>
              Renders this run&apos;s screens as a visual approximation — no container, no deploy,
              and it works even when the build is failing. Data shown is invented sample content.
            </p>
          </div>
        )}

      {!loadingManifest &&
        !error &&
        manifest?.available !== false &&
        !manifest?.jobId &&
        !inFlight &&
        !renderAllowed && (
          <div className='ide-screens-empty'>
            <h3>No generated screens</h3>
            <p>
              {unavailableReason ||
                'This run has no verified product-file changes, so there is nothing honest to render.'}
            </p>
          </div>
        )}

      {manifest?.status === 'NO_SURFACES' && (
        <div className='ide-screens-empty'>
          <h3>No screens found</h3>
          <p>This run doesn&apos;t appear to contain any UI files to render.</p>
        </div>
      )}

      {manifest?.status === 'FAILED' && (
        <div className='ide-screens-empty'>
          <h3>Nothing could be rendered</h3>
          <p>
            {manifest.failureReason === 'WORKSPACE_UNAVAILABLE'
              ? "This run's workspace is no longer available."
              : 'Every screen failed to render. You can try again.'}
          </p>
        </div>
      )}

      {rendered.length > 0 && (
        <div className='ide-screens-body'>
          <nav className='ide-screens-list' aria-label='Screens'>
            {rendered.map((s) => (
              <button
                key={s.surfaceId}
                className={`ide-screens-item ${selected === s.surfaceId ? 'is-active' : ''}`}
                onClick={() => setSelected(s.surfaceId)}
                title={s.sourcePath}
              >
                <span className='ide-screens-item-name'>{s.displayName}</span>
                <span className='ide-screens-item-target'>{TARGET_LABEL[s.target]}</span>
              </button>
            ))}
          </nav>

          <div className='ide-screens-stage'>
            {loadingSurface && <div className='ide-screens-loading'>Loading…</div>}
            {html && activeSurface && (
              <div
                className={`ide-screens-frame ${activeSurface.target !== 'WEB' ? 'is-device' : ''}`}
                style={{width: frame.width, height: frame.height}}
              >
                <iframe
                  key={activeSurface.surfaceId}
                  title={`${activeSurface.displayName} preview`}
                  srcDoc={html}
                  /*
                   * allow-scripts only. Omitting allow-same-origin is what puts this document on an
                   * opaque origin — with both together the sandbox would be defeated entirely.
                   */
                  sandbox='allow-scripts'
                  referrerPolicy='no-referrer'
                  width={frame.width}
                  height={frame.height}
                  style={{border: 0, display: 'block'}}
                />
              </div>
            )}
          </div>
        </div>
      )}

      {failed.length > 0 && (
        <details className='ide-screens-failed'>
          <summary>
            {failed.length} screen{failed.length === 1 ? '' : 's'} could not be rendered
          </summary>
          <ul>
            {failed.map((s) => (
              <li key={s.surfaceId}>
                <code>{s.sourcePath}</code> — {failureText(s.failureReason)}
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}

/** Resolves the frame size, defaulting to whatever the surface was rendered for. */
function frameSize(
  viewport: ViewportId,
  surface: PreviewRenderSurface | null,
): {width: number; height: number} {
  if (viewport !== 'auto') {
    const preset = VIEWPORTS.find((v) => v.id === viewport);
    if (preset && 'width' in preset) return {width: preset.width, height: preset.height};
  }
  if (surface) return {width: surface.viewportWidth, height: surface.viewportHeight};
  return {width: 1280, height: 800};
}
