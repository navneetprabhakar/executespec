#!/usr/bin/env node
import { createClient } from '@executespec/core';

import { parseArgs, flagBool, flagStr } from './args.js';
import { authCommand } from './commands/auth.js';
import { doctorCommand } from './commands/doctor.js';
import { projectCommand } from './commands/project.js';
import { runCommand } from './commands/run.js';
import { statsCommand } from './commands/stats.js';
import { chatRepl } from './repl.js';
import { errln, errorMessage, out } from './ui.js';

const VERSION = '0.1.0';

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
  run submit "<spec>" [flags] [--watch] submit a run (server clones + opens a PR)
  run list [--status S --scope mine|org]  |  run view <id>  |  run watch <id>
  run approve <id> [--credits N]  |  run reject <id>  |  run cancel <id>
  run report <id>  |  run cost <id>  |  run retry <id> <taskId>
  run local "<spec>"                    local-hands — not available yet (Phase 3)

Projects
  project list  |  project view <id>  |  project runs <id>

Account
  stats                                 your dashboard (runs, awaiting approval)
  doctor                                check host reachability, token, docker

Common flags
  --host <url>        backend (default https://executespec.ai or $EXECUTESPEC_HOST)
  --json              machine-readable output
  --watch / --yes     stream after submit / auto-approve the plan gate
  --repo, --project, --provider, --model, --mode, --tdd, --cost-cap   (run submit)

Auth: pass --token, set $EXECUTESPEC_TOKEN, or run \`auth login\`.`;

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
    // No command: interactive chat on a TTY; otherwise print help (scripts use the verbs).
    if (process.stdin.isTTY) {
      const client = createClient({ host: flagStr(flags, 'host') });
      return chatRepl(client, {
        projectId: flagStr(flags, 'project'),
        provider: flagStr(flags, 'provider'),
      });
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
    case 'stats':
      return statsCommand(cmdArgs, client);
    case 'doctor':
      return doctorCommand(client);
    case 'chat':
      return chatRepl(client, {
        projectId: flagStr(flags, 'project'),
        provider: flagStr(flags, 'provider'),
      });
    default:
      errln(`Unknown command: ${group}\n`);
      out(HELP);
      return 2;
  }
}

main()
  .then((code) => process.exit(code))
  .catch((e) => {
    errln(errorMessage(e));
    process.exit(1);
  });
