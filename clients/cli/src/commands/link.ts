import { FileStateBackend, type ClientBundle } from '@executespec/core';

import { type ParsedArgs, flagStr } from '../args.js';
import { bold, dim, errln, errorMessage, green, out, red } from '../ui.js';

const backend = new FileStateBackend();

/**
 * `spec link [--project <id>]` — bind the current directory to a project (writes
 * `<cwd>/.executespec/project.json`), so `run`/`stats` default to it. With no `--project`, prints
 * the current link. The project id is validated against the server before linking.
 */
export async function linkCommand(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const dir = process.cwd();
  const projectId = flagStr(args.flags, 'project');

  if (!projectId) {
    const link = await backend.readLink(dir);
    if (link) {
      out(`${dim(dir)}\n→ project ${bold(link.projectId)}` + dim(` (linked ${link.linkedAt})`));
    } else {
      out(dim('This directory is not linked. ') + 'Run ' + bold('executespec link --project <id>'));
      out(dim('See your projects with ') + bold('executespec project list') + dim('.'));
    }
    return 0;
  }

  try {
    const p = await client.api.getProject(projectId);
    await backend.writeLink(dir, { projectId: p.id, linkedAt: new Date().toISOString() });
    out(green('Linked ') + dim(dir) + green(' → ') + bold(p.name) + dim(` (${p.id})`));
    out(dim('`run` / `stats` in this directory now default to this project.'));
    return 0;
  } catch (e) {
    errln(
      red('Link failed: ') +
        errorMessage(e) +
        dim(' — check the id with `executespec project list`.'),
    );
    return 1;
  }
}

/** `spec unlink` — remove the current directory's project link. */
export async function unlinkCommand(): Promise<number> {
  const removed = await backend.removeLink(process.cwd());
  out(removed ? 'Unlinked.' : dim('This directory was not linked.'));
  return 0;
}
