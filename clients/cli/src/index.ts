#!/usr/bin/env node
import { createClient } from '@executespec/core';

import { parseArgs, flagBool, flagStr } from './args.js';
import { authCommand } from './commands/auth.js';
import { runCommand } from './commands/run.js';
import { errln, errorMessage, out } from './ui.js';

const VERSION = '0.1.0';

const HELP = `executespec — spec-driven coding from your terminal (thin client; the agent loop stays server-side)

Usage
  executespec <command> [subcommand] [args] [--flags]
  es <command> ...                      (short alias)

Auth
  auth login [--token <pat>] [--no-browser]  sign in via browser (gcloud-style), or paste a PAT
  auth status                           show the logged-in user
  auth logout                           forget the stored token

Runs
  run submit "<spec>" [flags] [--watch] submit a run (server clones + opens a PR)
  run watch <runId>                     stream a run's events; approve the plan gate
  run list [--status S --scope mine|org]
  run view <runId>
  run approve <runId> [--credits N] | run reject <runId>
  run cancel <runId>
  run report <runId>
  run local "<spec>" [--dir .]          local-hands: tools run on YOUR tree, nothing
                                        published by default (experimental — needs
                                        server local-mode)

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
  if (positionals.length === 0 || flagBool(flags, 'help')) {
    out(HELP);
    return positionals.length === 0 ? 1 : 0;
  }

  const group = positionals[0];
  const sub = positionals[1] ?? '';
  const cmdArgs = { positionals: positionals.slice(2), flags };
  const client = createClient({ host: flagStr(flags, 'host') });

  switch (group) {
    case 'auth':
      return authCommand(sub, cmdArgs, client);
    case 'run':
      return runCommand(sub, cmdArgs, client);
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
