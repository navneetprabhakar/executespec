import type { ClientBundle } from '@executespec/core';

import { type ParsedArgs, flagBool } from '../args.js';
import { runLine } from '../render.js';
import { bold, dim, out, printJson, yellow } from '../ui.js';

/**
 * `spec stats` — your account dashboard: total runs, status breakdown, how many are awaiting your
 * approval, and the most recent runs. Aggregated client-side from the runs list + account.
 */
export async function statsCommand(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const [me, page] = await Promise.all([
    client.api.me().catch(() => null),
    client.api.listRuns({ scope: 'mine', size: 100 }),
  ]);

  const runs = page.content;
  const byStatus = new Map<string, number>();
  for (const r of runs) byStatus.set(r.status, (byStatus.get(r.status) ?? 0) + 1);
  const awaiting = byStatus.get('AWAITING_APPROVAL') ?? 0;
  const complete = byStatus.get('COMPLETE') ?? 0;

  if (flagBool(args.flags, 'json')) {
    printJson({
      account: me ? { email: me.email, tier: me.tier } : null,
      totalRuns: page.totalElements,
      sampled: runs.length,
      complete,
      awaitingApproval: awaiting,
      byStatus: Object.fromEntries(byStatus),
    });
    return 0;
  }

  out(bold('Your ExecuteSpec dashboard') + (me ? dim(`  ${me.email} (${me.tier ?? '?'})`) : ''));
  out('');
  out(`  ${bold(String(page.totalElements))} run(s) total · ${bold(String(complete))} complete`);
  if (awaiting > 0) {
    out(
      yellow(`  ⚠ ${awaiting} awaiting your approval`) +
        dim('  → executespec run list --status AWAITING_APPROVAL'),
    );
  }

  if (byStatus.size) {
    out(dim(`\n  by status (last ${runs.length}):`));
    for (const [s, n] of [...byStatus.entries()].sort((a, b) => b[1] - a[1])) {
      out(`    ${s.padEnd(20)} ${n}`);
    }
  }

  if (runs.length) {
    out(bold('\nRecent'));
    for (const r of runs.slice(0, 5)) out('  ' + runLine(r));
  }
  return 0;
}
