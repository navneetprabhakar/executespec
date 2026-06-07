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

type OutputFormat = 'text' | 'json' | 'stream-json';

interface WatchOpts {
  yes: boolean;
  format: OutputFormat;
}

const FORMATS: readonly string[] = ['text', 'json', 'stream-json'];
const PROVIDERS: readonly string[] = ['ANTHROPIC', 'OPENAI', 'GEMINI', 'GOOGLE', 'OPENROUTER'];
const EXEC_MODES: readonly string[] = ['PRODUCTION', 'PROTOTYPE'];
const REPO_MODES: readonly string[] = ['NEW', 'EXISTING'];

/** Resolve the output format: `--format` wins; else `--json` (back-compat) ⇒ json; else text. */
export function resolveFormat(flags: ParsedArgs['flags']): OutputFormat {
  const f = flagStr(flags, 'format');
  if (f === 'text' || f === 'json' || f === 'stream-json') return f;
  return flagBool(flags, 'json') ? 'json' : 'text';
}

/** True for any machine format (json / stream-json) — the single-shot read commands key off this. */
function isMachine(f: OutputFormat): boolean {
  return f !== 'text';
}

/** Read the whole of stdin as UTF-8 (for `run submit -`). */
async function readStdin(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const c of process.stdin) chunks.push(c as Buffer);
  return Buffer.concat(chunks).toString('utf8');
}

export async function runCommand(
  sub: string,
  args: ParsedArgs,
  client: ClientBundle,
): Promise<number> {
  const fmtRaw = flagStr(args.flags, 'format');
  if (fmtRaw && !FORMATS.includes(fmtRaw)) {
    errln(`Invalid --format ${fmtRaw} (${FORMATS.join(' | ')})`);
    return 2;
  }
  switch (sub) {
    case 'submit':
      return submit(args, client);
    case 'list':
      return list(args, client);
    case 'view':
      return view(args, client);
    case 'watch':
      return watch(args, client);
    case 'attach':
      return attach(args, client);
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
        'Usage: executespec run <submit|list|view|watch|attach|approve|reject|cancel|report|cost|retry|local>',
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
  if (isMachine(resolveFormat(args.flags))) {
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
  if (isMachine(resolveFormat(args.flags))) {
    printJson(res);
    return 0;
  }
  errln(green(`Retrying task ${taskId}.`) + dim(' Track with `run view` / `run watch`.'));
  return 0;
}

async function submit(args: ParsedArgs, client: ClientBundle): Promise<number> {
  // Spec from a positional, or from stdin when given as `-` (piping / CI).
  let spec = args.positionals[0];
  if (spec === '-') spec = (await readStdin()).trim();
  if (!spec) {
    errln('Usage: executespec run submit "<spec>" [--repo URL] [--project ID] [--provider P] [--watch]');
    errln(dim('       (use `-` as the spec to read it from stdin)'));
    return 2;
  }
  // Pre-flight the credential before doing any work, so an unauthenticated user gets an actionable
  // message instead of a raw 401 from submitRun.
  if (!(await client.tokens.get())) {
    errln(red('Not signed in.') + ' Run ' + bold('executespec auth login') + ' first (or set $EXECUTESPEC_TOKEN).');
    return 1;
  }
  // Validate enum-shaped flags up front so a typo fails fast here, not as an opaque server 4xx.
  const provider = flagStr(args.flags, 'provider')?.toUpperCase();
  if (provider && !PROVIDERS.includes(provider)) {
    errln(`Invalid --provider ${provider} (${PROVIDERS.join(' | ')})`);
    return 2;
  }
  const mode = flagStr(args.flags, 'mode')?.toUpperCase();
  if (mode && !EXEC_MODES.includes(mode)) {
    errln(`Invalid --mode ${mode} (${EXEC_MODES.join(' | ')})`);
    return 2;
  }
  const repoMode = flagStr(args.flags, 'repo-mode')?.toUpperCase();
  if (repoMode && !REPO_MODES.includes(repoMode)) {
    errln(`Invalid --repo-mode ${repoMode} (${REPO_MODES.join(' | ')})`);
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
    repoMode: repoMode as RunRequest['repoMode'],
    projectId,
    aiProvider: provider as RunRequest['aiProvider'],
    modelName: flagStr(args.flags, 'model'),
    executionMode: mode as RunRequest['executionMode'],
    tddMode: flagBool(args.flags, 'tdd'),
    userCostCap: flagNum(args.flags, 'cost-cap'),
    skipApproval: flagBool(args.flags, 'skip-approval'),
  };
  const summary = await client.api.submitRun(req);
  const format = resolveFormat(args.flags);
  const watching = flagBool(args.flags, 'watch');
  if (isMachine(format) && !watching) {
    if (format === 'stream-json') out(JSON.stringify({ type: 'submitted', ...summary }));
    else printJson(summary);
    return 0;
  }
  // Progress/diagnostics go to stderr so stdout stays clean for piping.
  errln(`${bold('Run submitted')} ${summary.runId} ${dim('(' + summary.status + ')')}`);
  if (watching) {
    return watchRun(client, summary.runId, { yes: flagBool(args.flags, 'yes'), format });
  }
  errln(dim('Watch it: ') + `executespec run watch ${summary.runId}`);
  return 0;
}

async function watch(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const runId = args.positionals[0];
  if (!runId) {
    errln('Usage: executespec run watch <runId>');
    return 2;
  }
  return watchRun(client, runId, { yes: flagBool(args.flags, 'yes'), format: resolveFormat(args.flags) });
}

async function attach(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const runId = args.positionals[0];
  if (!runId) {
    errln('Usage: executespec run attach <runId>');
    return 2;
  }
  const format = resolveFormat(args.flags);
  // Backfill the persisted log (24h Redis) so you see the run from the start, even if you joined
  // late or it already finished.
  try {
    for (const ev of await client.api.eventLog(runId)) renderEvent(ev, format);
  } catch {
    // backfill is best-effort — fall through to live/final
  }
  // If the run is already terminal, print the final result and exit (no live stream to follow).
  const detail0 = await client.api.getRun(runId).catch(() => null);
  if (detail0 && isTerminalStatus(detail0.status)) {
    printFinal(detail0, format);
    return exitForStatus(detail0.status, detail0.statusReason);
  }
  // Otherwise follow live from here.
  return watchRun(client, runId, { yes: flagBool(args.flags, 'yes'), format });
}

async function watchRun(client: ClientBundle, runId: string, opts: WatchOpts): Promise<number> {
  const ac = new AbortController();
  let exit = 0;
  let cancelling = false;
  // Ctrl-C / SIGTERM cancels the SERVER run (DELETE /runs/{id}) before exiting, so an interrupt
  // never orphans a still-executing (and billable) cloud run. Exit 130 = 128 + SIGINT (shell convention).
  const onSignal = (): void => {
    if (cancelling) return;
    cancelling = true;
    errln(dim(`\nCancelling run ${runId}…`));
    ac.abort();
    client.api
      .cancel(runId)
      .catch(() => {})
      .finally(() => process.exit(130));
  };
  process.on('SIGINT', onSignal);
  process.on('SIGTERM', onSignal);
  try {
    try {
      for await (const ev of streamRunEvents(client.api, runId, { signal: ac.signal })) {
        renderEvent(ev, opts.format);
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
    if (cancelling) return 130;
    try {
      const detail = await client.api.getRun(runId);
      printFinal(detail, opts.format);
      exit = exit || exitForStatus(detail.status, detail.statusReason);
    } catch {
      // best-effort final fetch
    }
    return exit;
  } finally {
    process.off('SIGINT', onSignal);
    process.off('SIGTERM', onSignal);
  }
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
    errln(green('Auto-approving — ') + desc);
    await client.api.approve(runId, approveBody);
    return;
  }
  if (isMachine(opts.format)) {
    errln(dim('Gate opened (non-interactive). Approve with: ') + `executespec run approve ${runId}`);
    return;
  }
  if (await confirm(`${yellow('Approve plan?')} ${desc}`)) {
    await client.api.approve(runId, approveBody);
    errln(green('Approved.'));
  } else {
    await client.api.approve(runId, { approved: false });
    errln(red('Rejected.'));
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
  errln(approved ? green('Approved.') : red('Rejected.'));
  return 0;
}

async function cancel(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const runId = args.positionals[0];
  if (!runId) {
    errln('Usage: executespec run cancel <runId>');
    return 2;
  }
  await client.api.cancel(runId);
  errln('Cancelled.');
  return 0;
}

async function list(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const page = await client.api.listRuns({
    status: flagStr(args.flags, 'status') as RunStatus | undefined,
    projectId: flagStr(args.flags, 'project'),
    scope: (flagStr(args.flags, 'scope') as 'mine' | 'org' | 'all') ?? 'mine',
    size: flagNum(args.flags, 'size') ?? 20,
  });
  if (isMachine(resolveFormat(args.flags))) {
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
  if (isMachine(resolveFormat(args.flags))) {
    printJson(detail);
    return 0;
  }
  printFinal(detail, 'text');
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
  if (isMachine(resolveFormat(args.flags))) {
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
  // SECURITY: ToolRuntime is now fail-closed — shell/buildProject/runTests/gitCommit are DENIED
  // unless an explicit policy approves them. When this re-wires, pass
  // `createInteractivePermissions(ask)` (a y/N/a prompt over `ui.prompt`) so every local command is
  // user-approved; `allowAllPermissions()` exists only for trusted/non-interactive contexts.
  // Tracked: docs/pending.md §J1.
  errln(
    red('run local: LOCAL-hands mode is not available yet.') +
      ' It lands in Phase 3 (server-side executionLocality + tool-proxy activation).' +
      ' For now use `executespec run submit` for a remote run.',
  );
  return 2;
}

// ── rendering ─────────────────────────────────────────────────────────────

function renderEvent(ev: RunEvent, format: OutputFormat): void {
  if (format === 'stream-json') {
    // stream-json: every event is one NDJSON line on stdout, timestamped for post-hoc timing.
    out(JSON.stringify({ ts: ev.timestamp ?? new Date().toISOString(), ...ev }));
    return;
  }
  // text & json: live progress → stderr (json keeps stdout for the single final result object).
  switch (ev.type) {
    case RunEventType.RUN_STATUS:
      errln(dim('• status ') + statusColor(ev.payload ?? ''));
      break;
    case RunEventType.TASK_STATUS:
      errln(dim(`  task ${ev.taskId ?? ''} `) + (ev.payload ?? ''));
      break;
    case RunEventType.LOG:
      errln(dim('  … ') + (ev.payload ?? ''));
      break;
    case RunEventType.FILE_WRITTEN:
      errln(dim('  ✎ ') + (ev.payload ?? ''));
      break;
    case RunEventType.COST_UPDATE:
      errln(dim('  $ ') + (ev.payload ?? ''));
      break;
    case RunEventType.TOOL_CALL:
      errln(dim('  ⚙ tool ') + toolName(ev.payload));
      break;
    case 'REVIEW_COMPLETE':
      errln(cyan('  review ') + (ev.payload ?? ''));
      break;
    case RunEventType.COMPLETE:
      errln(bold('• complete ') + (ev.payload ?? ''));
      break;
    default:
      break; // keep the stream quiet for the noisier event types
  }
}

function printFinal(d: RunDetail, format: OutputFormat): void {
  if (format === 'json') {
    printJson(resultEnvelope(d));
    return;
  }
  if (format === 'stream-json') {
    out(JSON.stringify(resultEnvelope(d)));
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

/** Stable, parseable terminal summary for json / stream-json modes (W5.1 #6). */
export function resultEnvelope(d: RunDetail): {
  type: 'result';
  runId: string;
  status: string;
  statusReason: string | null;
  isError: boolean;
  exitCode: number;
  verifiedScore: number | null;
  testsPassed: number | null;
  testsTotal: number | null;
  reviewerVerdict: string | null;
  prUrl: string | null;
} {
  const exitCode = exitForStatus(d.status, d.statusReason);
  return {
    type: 'result',
    runId: d.runId,
    status: d.status,
    statusReason: d.statusReason ?? null,
    isError: exitCode !== 0,
    exitCode,
    verifiedScore: d.verifiedScore ?? null,
    testsPassed: d.testsPassed ?? null,
    testsTotal: d.testsTotal ?? null,
    reviewerVerdict: d.reviewerVerdict ?? null,
    prUrl: d.scmPrUrl ?? d.githubPrUrl ?? null,
  };
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

/**
 * Map a terminal run status to a process exit code so CI/automation can branch:
 *   0  COMPLETE, or a PARTIAL that finished cleanly (no functional failure)
 *   1  FAILED / CANCELLED
 *   3  a *degraded* PARTIAL — produced output but build/tests/reviewer failed (statusReason says so)
 * An empty / unknown PARTIAL reason stays 0 to avoid false CI failures.
 */
export function exitForStatus(status: string, statusReason?: string | null): number {
  const s = (status || '').split('|')[0];
  if (s === 'FAILED' || s === 'CANCELLED') return 1;
  if (s === 'PARTIAL') {
    const r = statusReason ?? '';
    const clean =
      r === '' ||
      r === 'CLEAN_COMPLETION' ||
      r === 'CLEAN_WITH_INTERMEDIATE_WARNINGS' ||
      r === 'LEGACY_UNKNOWN';
    return clean ? 0 : 3;
  }
  return 0;
}

function isTerminalStatus(status: string): boolean {
  const s = (status || '').split('|')[0];
  return s === 'COMPLETE' || s === 'FAILED' || s === 'PARTIAL' || s === 'CANCELLED';
}

function toolName(payload: string | null | undefined): string {
  if (!payload) return '?';
  try {
    return String((JSON.parse(payload) as { name?: string }).name ?? '?');
  } catch {
    return '?';
  }
}
