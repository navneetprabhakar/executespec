import {
  FileStateBackend,
  RunEventType,
  resolveProjectId,
  streamRunEvents,
  type ApprovalRequest,
  type ClientBundle,
  type RunDetail,
  type RunEvent,
  type RunRequest,
  type RunStatus,
} from '@executespec/core';

import { type ParsedArgs, flagBool, flagNum, flagStr } from '../args.js';
import { bold, confirm, cyan, dim, errln, errorMessage, green, out, printJson, red, yellow } from '../ui.js';

interface WatchOpts {
  yes: boolean;
  json: boolean;
}

export async function runCommand(
  sub: string,
  args: ParsedArgs,
  client: ClientBundle,
): Promise<number> {
  switch (sub) {
    case 'submit':
      return submit(args, client);
    case 'list':
      return list(args, client);
    case 'view':
      return view(args, client);
    case 'watch':
      return watch(args, client);
    case 'approve':
      return decide(args, client, true);
    case 'reject':
      return decide(args, client, false);
    case 'cancel':
      return cancel(args, client);
    case 'report':
      return report(args, client);
    case 'cost':
      return cost(args, client);
    case 'retry':
      return retry(args, client);
    case 'local':
      return local(args, client);
    default:
      errln(
        'Usage: executespec run <submit|list|view|watch|approve|reject|cancel|report|cost|retry|local>',
      );
      return 2;
  }
}

async function cost(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const runId = args.positionals[0];
  if (!runId) {
    errln('Usage: executespec run cost <runId>');
    return 2;
  }
  const c = await client.api.getCost(runId);
  if (flagBool(args.flags, 'json')) {
    printJson(c);
    return 0;
  }
  out(`${bold('$' + c.costUsd.toFixed(4))}  ${dim(`${c.inputTokens} in / ${c.outputTokens} out tokens`)}`);
  return 0;
}

async function retry(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const runId = args.positionals[0];
  const taskId = args.positionals[1];
  if (!runId || !taskId) {
    errln('Usage: executespec run retry <runId> <taskId>');
    return 2;
  }
  const res = await client.api.retryTask(runId, taskId);
  if (flagBool(args.flags, 'json')) {
    printJson(res);
    return 0;
  }
  out(green(`Retrying task ${taskId}.`) + dim(' Track with `run view` / `run watch`.'));
  return 0;
}

async function submit(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const spec = args.positionals[0];
  if (!spec) {
    errln('Usage: executespec run submit "<spec>" [--repo URL] [--project ID] [--provider P] [--watch]');
    return 2;
  }
  // Project precedence: --project flag > this directory's link > global default.
  const projectId = await resolveProjectId(
    new FileStateBackend(),
    process.cwd(),
    flagStr(args.flags, 'project'),
  );
  const req: RunRequest = {
    userRequest: spec,
    source: 'REST',
    repoUrl: flagStr(args.flags, 'repo'),
    repoMode: flagStr(args.flags, 'repo-mode')?.toUpperCase() as RunRequest['repoMode'],
    projectId,
    aiProvider: flagStr(args.flags, 'provider')?.toUpperCase() as RunRequest['aiProvider'],
    modelName: flagStr(args.flags, 'model'),
    executionMode: flagStr(args.flags, 'mode')?.toUpperCase() as RunRequest['executionMode'],
    tddMode: flagBool(args.flags, 'tdd'),
    userCostCap: flagNum(args.flags, 'cost-cap'),
    skipApproval: flagBool(args.flags, 'skip-approval'),
  };
  const summary = await client.api.submitRun(req);
  const json = flagBool(args.flags, 'json');
  if (json && !flagBool(args.flags, 'watch')) {
    printJson(summary);
    return 0;
  }
  out(`${bold('Run submitted')} ${summary.runId} ${dim('(' + summary.status + ')')}`);
  if (flagBool(args.flags, 'watch')) {
    return watchRun(client, summary.runId, { yes: flagBool(args.flags, 'yes'), json });
  }
  out(dim('Watch it: ') + `executespec run watch ${summary.runId}`);
  return 0;
}

async function watch(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const runId = args.positionals[0];
  if (!runId) {
    errln('Usage: executespec run watch <runId>');
    return 2;
  }
  return watchRun(client, runId, { yes: flagBool(args.flags, 'yes'), json: flagBool(args.flags, 'json') });
}

async function watchRun(client: ClientBundle, runId: string, opts: WatchOpts): Promise<number> {
  const ac = new AbortController();
  let exit = 0;
  try {
    for await (const ev of streamRunEvents(client.api, runId, { signal: ac.signal })) {
      renderEvent(ev, opts.json);
      if (ev.type === RunEventType.GATE_OPENED) {
        await handleGate(client, runId, ev, opts);
      }
      if (isTerminal(ev)) {
        ac.abort();
        break;
      }
    }
  } catch (e) {
    if (!ac.signal.aborted) {
      errln(`stream error: ${errorMessage(e)}`);
      exit = 1;
    }
  }
  try {
    const detail = await client.api.getRun(runId);
    printFinal(detail, opts.json);
    if (detail.status === 'FAILED' || detail.status === 'CANCELLED') exit = 1;
  } catch {
    // best-effort final fetch
  }
  return exit;
}

async function handleGate(
  client: ClientBundle,
  runId: string,
  ev: RunEvent,
  opts: WatchOpts,
): Promise<void> {
  // payload: "PLAN" or "PLAN|<credits>|<usd>"
  const parts = (ev.payload ?? '').split('|');
  const credits = parts[1] !== undefined && parts[1] !== '' ? Number(parts[1]) : undefined;
  const usd = parts[2];
  const desc =
    `Plan ready${credits !== undefined ? `, ${credits} credit(s)` : ''}` +
    `${usd ? `, ~$${usd}` : ''}.`;
  const approveBody: ApprovalRequest = {
    approved: true,
    creditsConfirmed: credits ?? null,
    executionMode: 'REALTIME',
  };
  if (opts.yes) {
    out(green('Auto-approving — ') + desc);
    await client.api.approve(runId, approveBody);
    return;
  }
  if (opts.json) {
    out(dim('Gate opened (non-interactive). Approve with: ') + `executespec run approve ${runId}`);
    return;
  }
  if (await confirm(`${yellow('Approve plan?')} ${desc}`)) {
    await client.api.approve(runId, approveBody);
    out(green('Approved.'));
  } else {
    await client.api.approve(runId, { approved: false });
    out(red('Rejected.'));
  }
}

async function decide(args: ParsedArgs, client: ClientBundle, approved: boolean): Promise<number> {
  const runId = args.positionals[0];
  if (!runId) {
    errln(`Usage: executespec run ${approved ? 'approve' : 'reject'} <runId> [--credits N]`);
    return 2;
  }
  const body: ApprovalRequest = approved
    ? {
        approved: true,
        creditsConfirmed: flagNum(args.flags, 'credits') ?? null,
        executionMode: (flagStr(args.flags, 'mode')?.toUpperCase() as 'REALTIME' | 'BATCH') ?? 'REALTIME',
      }
    : { approved: false };
  await client.api.approve(runId, body);
  out(approved ? green('Approved.') : red('Rejected.'));
  return 0;
}

async function cancel(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const runId = args.positionals[0];
  if (!runId) {
    errln('Usage: executespec run cancel <runId>');
    return 2;
  }
  await client.api.cancel(runId);
  out('Cancelled.');
  return 0;
}

async function list(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const page = await client.api.listRuns({
    status: flagStr(args.flags, 'status') as RunStatus | undefined,
    projectId: flagStr(args.flags, 'project'),
    scope: (flagStr(args.flags, 'scope') as 'mine' | 'org' | 'all') ?? 'mine',
    size: flagNum(args.flags, 'size') ?? 20,
  });
  if (flagBool(args.flags, 'json')) {
    printJson(page);
    return 0;
  }
  if (page.content.length === 0) {
    out(dim('No runs.'));
    return 0;
  }
  for (const r of page.content) {
    const spec = (r.userRequest ?? '').replace(/\s+/g, ' ').slice(0, 60);
    out(`${dim(r.runId)}  ${statusColor(r.status)}  ${spec}`);
  }
  out(dim(`\n${page.content.length} of ${page.totalElements} run(s).`));
  return 0;
}

async function view(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const runId = args.positionals[0];
  if (!runId) {
    errln('Usage: executespec run view <runId>');
    return 2;
  }
  const detail = await client.api.getRun(runId);
  if (flagBool(args.flags, 'json')) {
    printJson(detail);
    return 0;
  }
  printFinal(detail, false);
  if (detail.tasks && detail.tasks.length) {
    out(bold('\nTasks'));
    for (const t of detail.tasks) {
      out(`  ${statusColor(t.status)}  ${t.title}`);
    }
  }
  return 0;
}

async function report(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const runId = args.positionals[0];
  if (!runId) {
    errln('Usage: executespec run report <runId>');
    return 2;
  }
  const rpt = await client.api.getReport(runId);
  if (flagBool(args.flags, 'json')) {
    printJson(rpt);
    return 0;
  }
  const score = rpt['verifiedScore'];
  const scoreStr = score === undefined || score === null ? dim('n/a') : String(score);
  out(`${bold('verifiedScore')}: ${scoreStr}`);
  const verdict = rpt['reviewerVerdict'];
  if (verdict) out(`reviewer: ${String(verdict)}`);
  const passed = rpt['testsPassed'];
  const total = rpt['testsTotal'];
  if (total !== undefined && total !== null) out(`tests: ${String(passed)}/${String(total)}`);
  return 0;
}

async function local(_args: ParsedArgs, _client: ClientBundle): Promise<number> {
  // LOCAL-hands execution (code stays on your machine, nothing published) needs server-side
  // activation that lands in Phase 3 of the unified-client plan: PipelineRun.executionLocality +
  // RemoteToolDispatcher.register(runId) so the server emits TOOL_CALL events for the client's
  // ToolRuntime. Until then this would silently submit a normal SERVER-side run (which may even
  // auto-push) — the opposite of "local" — so it is gated off rather than mislead (design §10 #3).
  // The core LocalExecutionSession + ToolRuntime stay exported, ready for Phase 3 to re-wire here.
  // Tracked: docs/pending.md §J1.
  errln(
    red('run local: LOCAL-hands mode is not available yet.') +
      ' It lands in Phase 3 (server-side executionLocality + tool-proxy activation).' +
      ' For now use `executespec run submit` for a remote run.',
  );
  return 2;
}

// ── rendering ─────────────────────────────────────────────────────────────

function renderEvent(ev: RunEvent, json: boolean): void {
  if (json) {
    printJson(ev);
    return;
  }
  switch (ev.type) {
    case RunEventType.RUN_STATUS:
      out(dim('• status ') + statusColor(ev.payload ?? ''));
      break;
    case RunEventType.TASK_STATUS:
      out(dim(`  task ${ev.taskId ?? ''} `) + (ev.payload ?? ''));
      break;
    case RunEventType.LOG:
      out(dim('  … ') + (ev.payload ?? ''));
      break;
    case RunEventType.FILE_WRITTEN:
      out(dim('  ✎ ') + (ev.payload ?? ''));
      break;
    case RunEventType.COST_UPDATE:
      out(dim('  $ ') + (ev.payload ?? ''));
      break;
    case RunEventType.TOOL_CALL:
      out(dim('  ⚙ tool ') + toolName(ev.payload));
      break;
    case 'REVIEW_COMPLETE':
      out(cyan('  review ') + (ev.payload ?? ''));
      break;
    case RunEventType.COMPLETE:
      out(bold('• complete ') + (ev.payload ?? ''));
      break;
    default:
      break; // keep the stream quiet for the noisier event types
  }
}

function printFinal(d: RunDetail, json: boolean): void {
  if (json) {
    printJson(d);
    return;
  }
  out(`\n${bold(d.runId)}  ${statusColor(d.status)}`);
  if (d.statusReason) out(dim(`reason: ${d.statusReason}`));
  if (d.verifiedScore !== undefined && d.verifiedScore !== null) {
    out(`verifiedScore: ${bold(String(d.verifiedScore))}`);
  }
  const pr = d.scmPrUrl ?? d.githubPrUrl;
  if (pr) out(`PR: ${cyan(pr)}`);
}

function statusColor(status: string): string {
  switch (status.split('|')[0]) {
    case 'COMPLETE':
      return green(status);
    case 'FAILED':
    case 'CANCELLED':
      return red(status);
    case 'PARTIAL':
    case 'AWAITING_APPROVAL':
      return yellow(status);
    default:
      return status;
  }
}

function isTerminal(ev: RunEvent): boolean {
  if (ev.type === RunEventType.COMPLETE) return true;
  if (ev.type === RunEventType.RUN_STATUS && ev.payload) {
    const s = ev.payload.split('|')[0] ?? ev.payload;
    return s === 'COMPLETE' || s === 'FAILED' || s === 'PARTIAL' || s === 'CANCELLED';
  }
  return false;
}

function toolName(payload: string | null | undefined): string {
  if (!payload) return '?';
  try {
    return String((JSON.parse(payload) as { name?: string }).name ?? '?');
  } catch {
    return '?';
  }
}
