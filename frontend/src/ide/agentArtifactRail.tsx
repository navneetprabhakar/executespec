import {useState, type Dispatch} from 'react';

import type {AgentAction} from '@/ide/agentState';
import type {AgentArtifact} from '@/ide/agentArtifacts';

/**
 * Generated-artifact rail and its safe preview.
 *
 * Split out of `agentPanels.tsx` (RR-623 follow-up) so that file stays under its committed
 * god-class ceiling. This is one coherent responsibility — presenting model-authored files and
 * rendering them without granting them the app's origin — so it is a real module boundary, not a
 * line-count dodge.
 */

export function ArtifactRail({
  artifacts,
  dispatch,
}: {
  artifacts: AgentArtifact[];
  dispatch: Dispatch<AgentAction>;
}) {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [copyErrorId, setCopyErrorId] = useState<string | null>(null);
  const [previewErrorId, setPreviewErrorId] = useState<string | null>(null);
  const [previewArtifactState, setPreviewArtifact] = useState<AgentArtifact | null>(null);
  if (!artifacts.length) return null;

  async function copyArtifact(artifact: AgentArtifact) {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(artifact.content);
      setCopyErrorId(null);
      setCopiedId(artifact.id);
      window.setTimeout(
        () => setCopiedId((current) => (current === artifact.id ? null : current)),
        1600,
      );
    } catch {
      setCopiedId(null);
      setCopyErrorId(artifact.id);
      window.setTimeout(
        () => setCopyErrorId((current) => (current === artifact.id ? null : current)),
        3000,
      );
    }
  }

  function downloadArtifact(artifact: AgentArtifact) {
    const blob = new Blob([artifact.content], {type: mimeForArtifact(artifact)});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = artifact.filename;
    a.click();
    URL.revokeObjectURL(url);
  }

  /**
   * RR-623: preview model-generated markup in an OPAQUE origin, never the app origin.
   *
   * This used to `URL.createObjectURL` the artifact and `window.open` it. A blob: URL **inherits the
   * origin of the document that created it**, so the model's HTML/SVG ran as first-party on the app
   * origin — able to read `localStorage` (where `access_token` lives), call the API with the user's
   * ambient session, and read anything else same-origin. `noopener,noreferrer` does not help: it
   * severs the window handle, not the origin.
   *
   * The artifact is now rendered into a sandboxed iframe via `srcdoc` with `sandbox="allow-scripts"`
   * and deliberately WITHOUT `allow-same-origin`, which gives the document an opaque origin with no
   * access to app storage, cookies or same-origin fetch. The CSP is embedded as a `<meta>` inside
   * the document because a response header does not govern `srcdoc` — the same reason
   * `previewrender/` embeds it server-side.
   *
   * Do not "simplify" this back to a blob URL or add `allow-same-origin`; either one restores the
   * hole.
   */
  function previewArtifact(artifact: AgentArtifact) {
    setPreviewErrorId(null);
    setPreviewArtifact(artifact);
  }

  // Honest naming (Phase 4, item 29): this only drafts a message into the
  // composer. It does not start a sandbox, and there is no sandbox runtime yet,
  // so the control must not claim one.
  function draftSandboxRequest(artifact: AgentArtifact) {
    dispatch({
      type: 'input:set',
      input: `Create a remote sandbox from the generated artifact ${artifact.filename}. Preserve the artifact content from the previous assistant message and return evidence, changed files, and the handoff path.`,
    });
  }

  return (
    <div className='agent-artifact-rail' aria-label='Generated artifacts'>
      {previewArtifactState && (
        <SandboxedArtifactPreview
          artifact={previewArtifactState}
          onClose={() => setPreviewArtifact(null)}
        />
      )}
      <div className='agent-artifact-head'>
        <span>Generated artifacts</span>
        <span>
          {artifacts.length} file{artifacts.length === 1 ? '' : 's'}
        </span>
      </div>
      <div className='agent-artifact-list'>
        {artifacts.map((artifact) => (
          <div className='agent-artifact-item' key={artifact.id}>
            <div className='agent-artifact-main'>
              <code>{artifact.filename}</code>
              <span>
                {artifact.language.toUpperCase()} · {artifact.lineCount} line
                {artifact.lineCount === 1 ? '' : 's'}
              </span>
            </div>
            <div className='agent-artifact-actions'>
              <button className='ide-btn ghost' onClick={() => void copyArtifact(artifact)}>
                {copiedId === artifact.id
                  ? 'Copied'
                  : copyErrorId === artifact.id
                    ? 'Copy unavailable'
                    : 'Copy'}
              </button>
              <button className='ide-btn ghost' onClick={() => downloadArtifact(artifact)}>
                Download
              </button>
              <button
                className='ide-btn ghost'
                onClick={() => previewArtifact(artifact)}
                disabled={!artifact.previewable}
                title={
                  artifact.previewable
                    ? 'Open a browser preview'
                    : 'Preview is unavailable for this file type'
                }
              >
                Preview
              </button>
              <button
                className='ide-btn ghost'
                onClick={() => draftSandboxRequest(artifact)}
                title='Draft a sandbox request in the composer. Nothing runs until you send it.'
              >
                Draft sandbox request
              </button>
            </div>
            {copyErrorId === artifact.id && (
              <div className='agent-artifact-action-error' role='alert'>
                Clipboard access was denied. Use Download instead.
              </div>
            )}
            {previewErrorId === artifact.id && (
              <div className='agent-artifact-action-error' role='alert'>
                Preview was blocked. Allow pop-ups or use Download instead.
              </div>
            )}
          </div>
        ))}
      </div>
      <div className='agent-artifact-handoff'>
        <span>Remote sandbox keeps local files untouched.</span>
        <a href='/cli-login'>Open local handoff</a>
      </div>
    </div>
  );
}

/**
 * Renders a model-generated artifact in an opaque origin (RR-623).
 *
 * `sandbox="allow-scripts"` WITHOUT `allow-same-origin` is the load-bearing part: the combination
 * gives the frame a unique opaque origin, so scripts may run but cannot reach app `localStorage`,
 * cookies, or same-origin API calls. Adding `allow-same-origin` alongside `allow-scripts` would let
 * the document remove its own sandbox attribute and defeats the whole control.
 *
 * The CSP travels inside the document as a `<meta>` element, because a response header cannot govern
 * a `srcdoc` frame. `previewrender/` does the same thing server-side for the same reason.
 */
function SandboxedArtifactPreview({
  artifact,
  onClose,
}: {
  artifact: AgentArtifact;
  onClose: () => void;
}) {
  const mime = mimeForArtifact(artifact);
  const doc =
    mime === 'text/html'
      ? `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'none'; base-uri 'none'">${artifact.content}`
      : `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'"><body style="margin:0"><pre style="white-space:pre-wrap;word-break:break-word;font:12px ui-monospace,monospace;padding:12px">${escapeHtmlText(
          artifact.content,
        )}</pre>`;

  return (
    <div
      className='agent-artifact-preview'
      role='dialog'
      aria-label={`Preview ${artifact.filename}`}
    >
      <div className='agent-artifact-preview-head'>
        <span>{artifact.filename}</span>
        <span className='agent-artifact-preview-note'>
          Sandboxed preview — isolated from your session
        </span>
        <button className='ide-btn ghost' onClick={onClose}>
          Close
        </button>
      </div>
      <iframe
        title={`Preview of ${artifact.filename}`}
        sandbox='allow-scripts'
        srcDoc={doc}
        className='agent-artifact-preview-frame'
      />
    </div>
  );
}

/** Escapes artifact text so a non-HTML artifact cannot inject markup into its own viewer. */
function escapeHtmlText(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function mimeForArtifact(artifact: AgentArtifact): string {
  const ext = artifact.filename.split('.').pop()?.toLowerCase();
  if (ext === 'html' || ext === 'htm') return 'text/html';
  if (ext === 'svg') return 'image/svg+xml';
  if (ext === 'json') return 'application/json';
  if (ext === 'md' || ext === 'markdown') return 'text/markdown';
  if (ext === 'css') return 'text/css';
  if (ext === 'js' || ext === 'ts' || ext === 'tsx' || ext === 'jsx') return 'text/javascript';
  return 'text/plain';
}
