import type { ClientBundle } from '@executespec/core';

import { type ParsedArgs, flagBool, flagNum } from '../args.js';
import { runLine, truncate } from '../render.js';
import { bold, dim, errln, out, printJson } from '../ui.js';

export async function projectCommand(
  sub: string,
  args: ParsedArgs,
  client: ClientBundle,
): Promise<number> {
  switch (sub) {
    case '':
    case 'list':
      return list(args, client);
    case 'view':
      return view(args, client);
    case 'runs':
      return runs(args, client);
    default:
      errln('Usage: executespec project <list|view|runs> [projectId] [--json]');
      return 2;
  }
}

async function list(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const projects = await client.api.listProjects();
  if (flagBool(args.flags, 'json')) {
    printJson(projects);
    return 0;
  }
  if (projects.length === 0) {
    out(dim('No projects. Create one in the web app or with `link --create`.'));
    return 0;
  }
  for (const p of projects) {
    const status = p.status && p.status !== 'ACTIVE' ? dim(` (${p.status})`) : '';
    out(`${dim(p.id)}  ${bold(p.name)}${status}`);
    if (p.description) out(dim('  ' + truncate(p.description, 70)));
  }
  out(dim(`\n${projects.length} project(s).`));
  return 0;
}

async function view(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const id = args.positionals[0];
  if (!id) {
    errln('Usage: executespec project view <projectId>');
    return 2;
  }
  const p = await client.api.getProject(id);
  if (flagBool(args.flags, 'json')) {
    printJson(p);
    return 0;
  }
  out(bold(p.name) + dim(`  (${p.id})`));
  if (p.description) out(p.description);
  if (p.status) out(dim('status:   ') + p.status);
  if (p.aiProvider) out(dim('provider: ') + p.aiProvider);
  if (p.githubOwner) out(dim('github:   ') + p.githubOwner);
  if (p.targetBranch) out(dim('branch:   ') + p.targetBranch);

  // Best-effort: the project's most recent runs.
  try {
    const page = await client.api.listRuns({ projectId: id, scope: 'all', size: 5 });
    if (page.content.length) {
      out(bold('\nRecent runs'));
      for (const r of page.content) out('  ' + runLine(r));
    }
  } catch {
    // best-effort — the project view is still useful without the runs
  }
  return 0;
}

async function runs(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const id = args.positionals[0];
  if (!id) {
    errln('Usage: executespec project runs <projectId> [--size N] [--json]');
    return 2;
  }
  const page = await client.api.listRuns({
    projectId: id,
    scope: 'all',
    size: flagNum(args.flags, 'size') ?? 20,
  });
  if (flagBool(args.flags, 'json')) {
    printJson(page);
    return 0;
  }
  if (page.content.length === 0) {
    out(dim('No runs for this project yet.'));
    return 0;
  }
  for (const r of page.content) out(runLine(r));
  out(dim(`\n${page.content.length} of ${page.totalElements} run(s).`));
  return 0;
}
