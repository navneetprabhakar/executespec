import type { ClientBundle } from '@executespec/core';

import { type ParsedArgs, flagStr } from '../args.js';
import { dim, errln, errorMessage, green, out, printJson, prompt } from '../ui.js';

export async function authCommand(
  sub: string,
  args: ParsedArgs,
  client: ClientBundle,
): Promise<number> {
  switch (sub) {
    case 'login':
      return login(args, client);
    case 'status':
      return status(args, client);
    case 'logout':
      return logout(client);
    default:
      errln('Usage: executespec auth <login|status|logout>');
      return 2;
  }
}

async function login(args: ParsedArgs, client: ClientBundle): Promise<number> {
  let token = flagStr(args.flags, 'token');
  if (!token) {
    token = await prompt('Paste your Personal Access Token (es_pat_…): ');
  }
  if (!token) {
    errln('No token provided. Create one in the web app: Settings → API tokens.');
    return 1;
  }
  await client.tokens.set(token);
  try {
    const me = await client.api.me();
    out(green(`Logged in as ${me.email}`) + dim(` (${me.tier ?? 'tier ?'})`));
    return 0;
  } catch (e) {
    await client.tokens.clear();
    errln(`Login failed: ${errorMessage(e)}`);
    return 1;
  }
}

async function status(args: ParsedArgs, client: ClientBundle): Promise<number> {
  const token = await client.tokens.get();
  if (!token) {
    out('Not logged in. Run `executespec auth login`.');
    return 0;
  }
  try {
    const me = await client.api.me();
    if (args.flags.json) printJson(me);
    else out(`Logged in as ${me.email} ${dim('(' + (me.tier ?? '?') + ')')}`);
    return 0;
  } catch (e) {
    errln(`Token rejected: ${errorMessage(e)}`);
    return 1;
  }
}

async function logout(client: ClientBundle): Promise<number> {
  await client.tokens.clear();
  out('Logged out.');
  return 0;
}
