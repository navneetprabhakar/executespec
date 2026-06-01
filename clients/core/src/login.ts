import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createServer, type ServerResponse } from 'node:http';
import { platform } from 'node:process';

export interface LoopbackLoginOptions {
  /** Web origin (no trailing slash), e.g. `https://executespec.ai`. The page lives at `${host}/cli-login`. */
  host: string;
  /** Override the browser launch (tests / headless). Receives the full auth URL. */
  openBrowser?: (url: string) => void;
  /** Notified with the auth URL so the caller can print a manual-open hint. */
  onUrl?: (url: string) => void;
  /** Abort if no callback arrives within this many ms (default 180_000). */
  timeoutMs?: number;
}

/**
 * gcloud-style loopback browser login. Starts a one-shot HTTP server on `127.0.0.1:<ephemeral>`,
 * opens the browser to the web app's `/cli-login` page, and waits for it to redirect back to
 * `http://127.0.0.1:<port>/cb?token=…&state=…` once the user has authenticated + authorized this
 * device. Resolves with the `es_pat_…` token.
 *
 * <p>Security: a random {@code state} is generated here and must echo back unchanged; the server
 * binds `127.0.0.1` only and serves exactly one callback before closing. The token never leaves the
 * loopback interface.
 */
export function loopbackLogin(opts: LoopbackLoginOptions): Promise<string> {
  const state = randomBytes(24).toString('base64url');
  const timeoutMs = opts.timeoutMs ?? 180_000;
  const open = opts.openBrowser ?? defaultOpenBrowser;

  return new Promise<string>((resolve, reject) => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const cleanup = (): void => {
      if (timer) clearTimeout(timer);
      server.close();
    };

    const server = createServer((req, res) => {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      if (url.pathname !== '/cb') {
        res.writeHead(404, { connection: 'close' }).end('not found');
        return;
      }
      const err = url.searchParams.get('error');
      const token = url.searchParams.get('token');
      const gotState = url.searchParams.get('state');
      if (err) {
        respond(res, false, 'Sign-in failed: ' + err);
        cleanup();
        reject(new Error('sign-in failed: ' + err));
      } else if (!token || gotState !== state) {
        respond(res, false, 'Invalid sign-in callback.');
        cleanup();
        reject(new Error('invalid sign-in callback (state mismatch or missing token)'));
      } else {
        respond(res, true, 'You are signed in to the ExecuteSpec CLI — you can close this tab.');
        cleanup();
        resolve(token);
      }
    });

    server.on('error', (e) => {
      cleanup();
      reject(e);
    });

    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      if (addr === null || typeof addr === 'string') {
        server.close();
        reject(new Error('could not bind a loopback port'));
        return;
      }
      const authUrl = `${opts.host}/cli-login?port=${addr.port}&state=${encodeURIComponent(state)}`;
      opts.onUrl?.(authUrl);
      try {
        open(authUrl);
      } catch {
        /* browser launch failed — the caller prints the URL for manual open */
      }
      timer = setTimeout(() => {
        server.close();
        reject(new Error(`sign-in timed out (no response in ${Math.round(timeoutMs / 1000)}s)`));
      }, timeoutMs);
    });
  });
}

function respond(res: ServerResponse, ok: boolean, msg: string): void {
  const color = ok ? '#16a34a' : '#dc2626';
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', connection: 'close' });
  res.end(
    `<!doctype html><meta charset="utf-8"><title>ExecuteSpec CLI</title>` +
      `<body style="font:15px/1.5 system-ui,sans-serif;max-width:32rem;margin:4rem auto;text-align:center">` +
      `<h2 style="color:${color};margin:0 0 .5rem">${ok ? '✓ Signed in' : '✗ Sign-in failed'}</h2>` +
      `<p style="color:#475569">${escapeHtml(msg)}</p></body>`,
  );
}

function escapeHtml(s: string): string {
  return s.replace(
    /[&<>"]/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c] ?? c,
  );
}

function defaultOpenBrowser(url: string): void {
  let cmd: string;
  let args: string[];
  if (platform === 'darwin') {
    cmd = 'open';
    args = [url];
  } else if (platform === 'win32') {
    cmd = 'cmd';
    args = ['/c', 'start', '', url];
  } else {
    cmd = 'xdg-open';
    args = [url];
  }
  const child = spawn(cmd, args, { stdio: 'ignore', detached: true });
  child.on('error', () => {
    /* ignore — the caller prints the URL as a manual-open fallback */
  });
  child.unref();
}
