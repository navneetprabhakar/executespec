#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { createClient, type ClientBundle } from '@executespec/core';

import { parseArgs, flagBool, flagStr, type ParsedArgs } from './args.js';
import { authCommand } from './commands/auth.js';
import { doctorCommand } from './commands/doctor.js';
import { linkCommand, unlinkCommand } from './commands/link.js';
import { projectCommand } from './commands/project.js';
import { runCommand } from './commands/run.js';
import { statsCommand } from './commands/stats.js';
import { chatRepl } from './repl.js';
import { runChatTui } from './tui/chat.js';
import { dim, errln, errorMessage, out } from './ui.js';

// Single source of truth: read the version from package.json (dist/index.js → ../package.json),
// so `--version` never drifts from the published package.
const VERSION: string = (() => {
  try {
    const pkgPath = join(dirname(fileURLToPath(import.meta.url)), '..', 'package.json');
    return (JSON.parse(readFileSync(pkgPath, 'utf8')) as { version?: string }).version ?? '0.0.0';
  } catch {
    return '0.0.0';
  }
})();

const HELP = `executespec — spec-driven coding from your terminal (thin client; the agent loop stays server-side)

Usage
  executespec <command> [subcommand] [args] [--flags]
  es <command> ...                      (short alias)
  executespec                           no command on a TTY → interactive chat

Auth
  login [--token <pat>] [--no-browser]  sign in via browser (gcloud-style), or paste a PAT
  whoami                                show the logged-in user
  logout                                forget the stored token

Runs
  run submit "<spec>" [flags] [--watch] submit a run ('-' as the spec reads it from stdin)
  run list [--status S --scope mine|org]  |  run view <id>
  run watch <id>                        follow a run live
  run attach <id>                       replay history from the start, then follow live
  run approve <id> [--credits N]  |  run reject <id>  |  run cancel <id>
  run report <id>  |  run cost <id>  |  run retry <id> <taskId>
  run local "<spec>"                    local-hands — not available yet (Phase 3)

Projects
  project list  |  project view <id>  |  project runs <id>  |  project use <id>
  link [--project <id>]  |  unlink     bind this directory to a project (run/stats default to it)

Account
  stats                                 your dashboard (runs, awaiting approval)
  doctor                                check host reachability, token, docker

Common flags
  --host <url>        backend (default https://executespec.ai or $EXECUTESPEC_HOST)
  --json              machine-readable output (alias for --format json)
  --format <fmt>      text | json | stream-json (NDJSON of every event, timestamped)
  --watch / --yes     stream after submit / auto-approve the plan gate
  --repo, --project, --provider, --model, --mode, --tdd, --cost-cap   (run submit)

Auth: first run opens your browser to sign in automatically — or pass --token, set $EXECUTESPEC_TOKEN, or run \`auth login\`.`;

/**
 * The interactive chat entry (bare `executespec` or `executespec chat`). gh-style auto-login on first
 * run: on a TTY with no stored token, kick off the browser sign-in automatically, then drop straight
 * into the chat — so a brand-new user never has to run `auth login` as a separate step. `--plain` /
 * non-TTY fall back to the readline REPL and skip the browser (scripts/pipes still fail closed).
 */
async function interactiveChat(client: ClientBundle, flags: ParsedArgs['flags']): Promise<number> {
  if (!(await client.tokens.get()) && process.stdin.isTTY) {
    out(dim('Welcome to ExecuteSpec. Signing you in — your browser will open…'));
    const code = await authCommand('login', { positionals: [], flags }, client);
    if (code !== 0) return code; // sign-in failed or was declined
  }
  const chatOpts = {
    projectId: flagStr(flags, 'project'),
    provider: flagStr(flags, 'provider'),
  };
  return process.stdin.isTTY && !flagBool(flags, 'plain')
    ? runChatTui(client, chatOpts)
    : chatRepl(client, chatOpts);
}

async function main(): Promise<number> {
  const { positionals, flags } = parseArgs(process.argv.slice(2));

  if (flagBool(flags, 'version')) {
    out(`executespec ${VERSION}`);
    return 0;
  }
  if (flagBool(flags, 'help')) {
    out(HELP);
    return 0;
  }
  if (positionals.length === 0) {
    // No command: interactive chat on a TTY (auto-login on first run); else help (scripts use verbs).
    if (process.stdin.isTTY) {
      return interactiveChat(createClient({ host: flagStr(flags, 'host') }), flags);
    }
    out(HELP);
    return 1;
  }

  const group = positionals[0];
  const sub = positionals[1] ?? '';
  const cmdArgs = { positionals: positionals.slice(2), flags };
  const client = createClient({ host: flagStr(flags, 'host') });

  switch (group) {
    case 'auth':
      return authCommand(sub, cmdArgs, client);
    case 'login':
      return authCommand('login', cmdArgs, client);
    case 'logout':
      return authCommand('logout', cmdArgs, client);
    case 'whoami':
      return authCommand('status', cmdArgs, client);
    case 'run':
      return runCommand(sub, cmdArgs, client);
    case 'project':
    case 'projects':
      return projectCommand(sub, cmdArgs, client);
    case 'link':
      return linkCommand(cmdArgs, client);
    case 'unlink':
      return unlinkCommand();
    case 'stats':
      return statsCommand(cmdArgs, client);
    case 'doctor':
      return doctorCommand(client);
    case 'chat':
      return interactiveChat(client, flags);
    default:
      errln(`Unknown command: ${group}\n`);
      out(HELP);
      return 2;
  }
}

// Last-resort handlers: a stray async error/rejection escaping the main() chain prints cleanly and
// exits non-zero, instead of crashing with a raw Node stack trace.
process.on('uncaughtException', (e) => {
  errln(errorMessage(e));
  process.exit(1);
});
process.on('unhandledRejection', (e) => {
  errln(errorMessage(e));
  process.exit(1);
});

main()
  .then((code) => process.exit(code))
  .catch((e) => {
    errln(errorMessage(e));
    process.exit(1);
  });
