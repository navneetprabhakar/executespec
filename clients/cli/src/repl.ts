import { randomUUID } from 'node:crypto';
import { stdin, stdout } from 'node:process';
import { createInterface } from 'node:readline/promises';

import { ChatSession, type ChatTurnEvent, type ClientBundle } from '@executespec/core';

import { bold, cyan, dim, errln, errorMessage, out, red, yellow } from './ui.js';

export interface ReplOptions {
  projectId?: string;
  provider?: string;
}

/**
 * Interactive chat REPL over `POST /api/spec/chat` (the Phase-1 `SpecChatService` loop). `spec` with
 * no command on a TTY drops in here; the deterministic verbs stay available for scripts/non-TTY.
 *
 * Ctrl-C during a reply cancels that reply (aborts the turn); at the idle prompt it exits. The
 * conversation lives in memory for the session (local-only persistence is a later slice).
 */
export async function chatRepl(client: ClientBundle, opts: ReplOptions = {}): Promise<number> {
  const token = await client.tokens.get();
  if (!token) {
    errln(red('Not signed in.') + ' Run ' + bold('executespec auth login') + ' first.');
    return 1;
  }

  const session = new ChatSession(randomUUID(), {
    projectId: opts.projectId,
    provider: opts.provider,
  });

  out(bold('ExecuteSpec') + dim(` chat · ${client.http.host}`));
  out(dim('Describe what you want, or /help. Ctrl-C cancels a reply / exits at the prompt.'));

  const rl = createInterface({ input: stdin, output: stdout });
  let turnAbort: AbortController | null = null;
  rl.on('SIGINT', () => {
    if (turnAbort) {
      turnAbort.abort();
      out(dim('  (cancelled)'));
    } else {
      out('');
      rl.close();
    }
  });

  try {
    for (;;) {
      let line: string;
      try {
        line = (await rl.question(cyan('\nspec> '))).trim();
      } catch {
        break; // interface closed (Ctrl-C at idle, or EOF)
      }
      if (!line) continue;

      if (line.startsWith('/')) {
        if (handleSlash(line, session) === 'exit') break;
        continue;
      }

      // Guard: a bare CLI verb (e.g. `auth login`, `run list`) typed into chat would otherwise be
      // shipped to the model as a spec and submitted as a run. Redirect to the shell instead.
      const shellCmd = looksLikeShellCommand(line);
      if (shellCmd) {
        out(yellow(`"${shellCmd}" is a CLI command, not a chat message.`));
        out(dim('  Run it in your shell:  ') + bold(`executespec ${shellCmd}`));
        out(dim('  Or just describe what you want — e.g. "submit a run that adds a /health endpoint".'));
        continue;
      }

      turnAbort = new AbortController();
      try {
        await renderTurn(session.send(client.http, line, turnAbort.signal));
      } catch (e) {
        if (!turnAbort.signal.aborted) errln(red('chat error: ') + errorMessage(e));
      } finally {
        turnAbort = null;
      }
    }
  } finally {
    rl.close();
  }
  out(dim('bye.'));
  return 0;
}

async function renderTurn(stream: AsyncGenerator<ChatTurnEvent>): Promise<void> {
  for await (const ev of stream) {
    switch (ev.type) {
      case 'token':
        if (ev.delta) out(ev.delta);
        break;
      case 'tool_call':
        out(dim(`  ⚙ ${ev.tool}(${trunc(ev.args, 80)})`));
        break;
      case 'tool_result':
        out(dim('  → ') + summarizeEnvelope(ev.result));
        break;
      case 'done':
        if (ev.note) out(yellow(`  · ${ev.note}`));
        else
          out(
            dim(
              `  · turn ${ev.turnCount ?? '?'}` +
                (ev.costUsd != null ? `, $${ev.costUsd.toFixed(4)}` : ''),
            ),
          );
        break;
      case 'error':
        errln(red(`  ! ${ev.message}`));
        break;
      case 'ack':
        break; // internal handshake
    }
  }
}

type SlashResult = 'exit' | 'continue';

function handleSlash(line: string, session: ChatSession): SlashResult {
  const [cmd, ...rest] = line.slice(1).split(/\s+/);
  switch (cmd) {
    case 'help':
      printReplHelp();
      return 'continue';
    case 'exit':
    case 'quit':
    case 'q':
      return 'exit';
    case 'provider':
      if (rest[0]) {
        session.setProvider(rest[0].toLowerCase());
        out(dim(`provider → ${rest[0].toLowerCase()}`));
      } else {
        out(dim(`provider: ${session.provider ?? 'default'}`));
      }
      return 'continue';
    case 'clear':
      session.clear();
      out(dim('(conversation cleared)'));
      return 'continue';
    default:
      out(dim(`unknown command /${cmd} — try /help`));
      return 'continue';
  }
}

function printReplHelp(): void {
  out(bold('Interactive chat') + dim(' — I can submit, watch, approve, and report runs for you.'));
  out(dim('  /provider <name>   set the LLM provider for this session'));
  out(dim('  /clear             start a fresh conversation'));
  out(dim('  /help              this help'));
  out(dim('  /exit              leave (or Ctrl-C at the prompt)'));
}

function summarizeEnvelope(result: string): string {
  try {
    const e = JSON.parse(result) as {
      ok?: boolean;
      error?: string;
      summary?: string;
      runId?: string;
      count?: number;
    };
    if (e.ok === false) return red(`error: ${e.error ?? 'failed'}`);
    if (e.summary) return e.summary;
    if (e.runId) return `runId ${e.runId}`;
    if (typeof e.count === 'number') return `${e.count} result(s)`;
    return dim('ok');
  } catch {
    return dim(trunc(result, 80));
  }
}

function trunc(s: string | undefined, n: number): string {
  if (!s) return '';
  return s.length > n ? s.slice(0, n) + '…' : s;
}

/** The deterministic CLI verbs, by group → subcommands. Matched against bare chat input. */
const KNOWN_VERBS: Record<string, ReadonlySet<string>> = {
  auth: new Set(['login', 'status', 'logout']),
  run: new Set([
    'submit',
    'list',
    'view',
    'watch',
    'approve',
    'reject',
    'cancel',
    'report',
    'local',
  ]),
};

/**
 * Detect a bare CLI command typed into the chat (e.g. `auth login`, `es run list`) so the REPL can
 * redirect the user to their shell instead of shipping it to the model as a spec — the footgun that
 * turned a stray `auth login` into a submitted run. Returns the normalized command (any
 * `executespec`/`es`/`spec` prefix stripped) or null when the line is ordinary chat. Matches only a
 * known group + known subcommand, so natural language like "run a test for me" is left alone.
 * Exported for tests.
 */
export function looksLikeShellCommand(line: string): string | null {
  const tokens = line.trim().split(/\s+/);
  let i = 0;
  if (tokens[i] === 'executespec' || tokens[i] === 'es' || tokens[i] === 'spec') i++;
  const group = tokens[i];
  const sub = tokens[i + 1];
  if (group && sub && KNOWN_VERBS[group]?.has(sub)) {
    return tokens.slice(i).join(' ');
  }
  return null;
}
