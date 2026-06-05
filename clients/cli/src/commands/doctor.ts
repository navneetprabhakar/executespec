import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

import { ApiError, type ClientBundle } from '@executespec/core';

import { bold, dim, green, out, red, yellow } from '../ui.js';

const exec = promisify(execFile);

/**
 * `spec doctor` — diagnose the local setup: host reachability, token validity, and whether Docker
 * is present (needed only for local build/test in `run --local`). Exit 0 when healthy, 1 otherwise.
 */
export async function doctorCommand(client: ClientBundle): Promise<number> {
  out(bold('ExecuteSpec doctor'));
  out(dim('host: ') + client.http.host);

  let healthy = true;

  const token = await client.tokens.get();
  if (!token) {
    out(red('✗ ') + 'not signed in — run ' + bold('executespec auth login'));
    healthy = false;
  } else {
    try {
      const me = await client.api.me();
      out(green('✓ ') + `signed in as ${me.email}` + dim(` (${me.tier ?? 'tier ?'})`));
    } catch (e) {
      healthy = false;
      if (e instanceof ApiError && e.status === 401) {
        out(red('✗ ') + 'token rejected (expired/revoked) — re-run ' + bold('executespec auth login'));
      } else if (e instanceof ApiError && e.status === 0) {
        out(red('✗ ') + `can't reach ${client.http.host} — is the backend up / --host correct?`);
      } else {
        out(red('✗ ') + 'auth check failed: ' + (e instanceof Error ? e.message : String(e)));
      }
    }
  }

  try {
    const { stdout } = await exec('docker', ['--version']);
    out(green('✓ ') + 'docker: ' + stdout.trim());
  } catch {
    out(yellow('• ') + 'docker not found — only needed for local build/test in ' + bold('run --local'));
  }

  out('');
  out(healthy ? green('All good.') : yellow('Some checks failed — see above.'));
  return healthy ? 0 : 1;
}
