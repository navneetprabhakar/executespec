import { randomUUID } from 'node:crypto';

import { ChatSession, type ClientBundle } from '@executespec/core';
import { Box, render, Static, Text, useApp, useInput } from 'ink';
import TextInput from 'ink-text-input';
import { useCallback, useRef, useState } from 'react';

import { errorMessage } from '../ui.js';
import { looksLikeShellCommand } from '../repl.js';

/**
 * Rich terminal chat UI (Ink) — the conversational surface over `POST /api/spec/chat`. A scrolling
 * transcript ({@link Static}, printed once and never re-rendered), a live region that streams the
 * current assistant turn + its tool calls, a status line, and an input box. Drives the same
 * {@link ChatSession} the plain readline REPL uses; `--plain` (or a non-TTY) falls back to that.
 */

type Entry =
  | { kind: 'user'; text: string }
  | { kind: 'assistant'; text: string }
  | { kind: 'tool'; text: string }
  | { kind: 'note'; text: string }
  | { kind: 'error'; text: string };

interface ChatAppProps {
  client: ClientBundle;
  session: ChatSession;
  host: string;
  /** Called once when the user exits (Ctrl-C at idle / `/exit`). */
  onExit?: () => void;
}

export function ChatApp({ client, session, host, onExit }: ChatAppProps): React.ReactElement {
  const { exit } = useApp();
  const [entries, setEntries] = useState<Entry[]>([]);
  const [live, setLive] = useState('');
  const [toolLines, setToolLines] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [input, setInput] = useState('');
  const [status, setStatus] = useState('ready');
  const [provider, setProviderLabel] = useState(session.provider ?? 'default');
  const abortRef = useRef<AbortController | null>(null);

  const push = useCallback((e: Entry) => setEntries((prev) => [...prev, e]), []);

  const quit = useCallback(() => {
    onExit?.();
    exit();
  }, [exit, onExit]);

  useInput((ch, key) => {
    // Ctrl-C: cancel the in-flight turn if streaming, otherwise leave.
    if (key.ctrl && ch === 'c') {
      if (abortRef.current) abortRef.current.abort();
      else quit();
    }
  });

  const runTurn = useCallback(
    async (line: string): Promise<void> => {
      push({ kind: 'user', text: line });
      setBusy(true);
      setLive('');
      setToolLines([]);
      setStatus('thinking…');
      const ac = new AbortController();
      abortRef.current = ac;
      const turnTools: string[] = [];
      let asst = '';
      try {
        for await (const ev of session.send(client.http, line, ac.signal)) {
          if (ev.type === 'token' && ev.delta) {
            asst += (asst ? '\n' : '') + ev.delta;
            setLive(asst);
          } else if (ev.type === 'tool_call') {
            turnTools.push(`⚙ ${ev.tool}(${trunc(ev.args, 60)})`);
            setToolLines([...turnTools]);
          } else if (ev.type === 'tool_result') {
            turnTools.push(`→ ${summarizeEnvelope(ev.result)}`);
            setToolLines([...turnTools]);
          } else if (ev.type === 'done') {
            setStatus(
              ev.note ??
                `turn ${ev.turnCount ?? '?'}${ev.costUsd != null ? ` · $${ev.costUsd.toFixed(4)}` : ''}`,
            );
          } else if (ev.type === 'error') {
            push({ kind: 'error', text: ev.message });
          }
        }
      } catch (e) {
        if (ac.signal.aborted) setStatus('cancelled');
        else push({ kind: 'error', text: errorMessage(e) });
      } finally {
        // Commit the live turn into the scrollback (Static), then clear the live region.
        const committed: Entry[] = [
          ...turnTools.map((t): Entry => ({ kind: 'tool', text: t })),
          ...(asst ? [{ kind: 'assistant', text: asst } as Entry] : []),
        ];
        if (committed.length) setEntries((prev) => [...prev, ...committed]);
        setLive('');
        setToolLines([]);
        setBusy(false);
        abortRef.current = null;
      }
    },
    [client, session, push],
  );

  const onSubmit = useCallback(
    (value: string): void => {
      const line = value.trim();
      setInput('');
      if (!line || busy) return;
      if (line === '/exit' || line === '/quit' || line === '/q') {
        quit();
        return;
      }
      if (line === '/clear') {
        session.clear();
        setEntries([{ kind: 'note', text: '(conversation cleared)' }]);
        return;
      }
      if (line.startsWith('/provider')) {
        const p = line.split(/\s+/)[1];
        if (p) {
          session.setProvider(p.toLowerCase());
          setProviderLabel(p.toLowerCase());
          push({ kind: 'note', text: `provider → ${p.toLowerCase()}` });
        } else {
          push({ kind: 'note', text: `provider: ${session.provider ?? 'default'}` });
        }
        return;
      }
      if (line === '/help') {
        push({
          kind: 'note',
          text: '/provider <name> · /clear · /exit · Ctrl-C cancels a reply or exits at the prompt',
        });
        return;
      }
      if (line.startsWith('/')) {
        push({ kind: 'note', text: `unknown command ${line} — try /help` });
        return;
      }
      const shell = looksLikeShellCommand(line);
      if (shell) {
        push({
          kind: 'note',
          text: `"${shell}" is a CLI command — run it in your shell: executespec ${shell}`,
        });
        return;
      }
      void runTurn(line);
    },
    [busy, push, quit, runTurn, session],
  );

  return (
    <Box flexDirection="column">
      <Static items={entries}>{(e, i) => <TranscriptLine key={i} entry={e} />}</Static>

      {busy && (live.length > 0 || toolLines.length > 0) ? (
        <Box flexDirection="column" marginLeft={2}>
          {toolLines.map((t, i) => (
            <Text key={i} dimColor>
              {t}
            </Text>
          ))}
          {live ? <Text color="green">{live}</Text> : null}
        </Box>
      ) : null}

      <Box marginTop={1}>
        <Text backgroundColor="blue" color="white">
          {' ExecuteSpec '}
        </Text>
        <Text dimColor>{` ${host} · ${provider} · ${busy ? '⏳ ' + status : status}`}</Text>
      </Box>

      <Box>
        <Text color="cyan">{busy ? '· ' : '› '}</Text>
        {busy ? (
          <Text dimColor>working… (Ctrl-C to cancel)</Text>
        ) : (
          <TextInput
            value={input}
            onChange={setInput}
            onSubmit={onSubmit}
            placeholder="describe what to build, or /help"
          />
        )}
      </Box>
    </Box>
  );
}

function TranscriptLine({ entry }: { entry: Entry }): React.ReactElement | null {
  switch (entry.kind) {
    case 'user':
      return (
        <Text>
          <Text color="cyan" bold>
            you{' '}
          </Text>
          {entry.text}
        </Text>
      );
    case 'assistant':
      return (
        <Box flexDirection="column" marginTop={1}>
          <Text color="green" bold>
            spec
          </Text>
          <Text>{entry.text}</Text>
        </Box>
      );
    case 'tool':
      return <Text dimColor> {' ' + entry.text}</Text>;
    case 'note':
      return <Text color="yellow">{entry.text}</Text>;
    case 'error':
      return <Text color="red">{`! ${entry.text}`}</Text>;
    default:
      return null;
  }
}

function trunc(s: string, n: number): string {
  if (!s) return '';
  return s.length > n ? s.slice(0, n) + '…' : s;
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
    if (e.ok === false) return `error: ${e.error ?? 'failed'}`;
    if (e.summary) return e.summary;
    if (e.runId) return `runId ${e.runId}`;
    if (typeof e.count === 'number') return `${e.count} result(s)`;
    return 'ok';
  } catch {
    return trunc(result, 80);
  }
}

/**
 * Launch the Ink chat TUI. Returns when the user exits. Falls back to the caller (the readline REPL)
 * by returning a non-zero code if there is no token — the bare `executespec` path handles sign-in
 * messaging the same way the REPL does.
 */
export async function runChatTui(
  client: ClientBundle,
  opts: { projectId?: string; provider?: string } = {},
): Promise<number> {
  if (!(await client.tokens.get())) {
    process.stderr.write(
      'Not signed in. Run `executespec auth login` first (or set $EXECUTESPEC_TOKEN).\n',
    );
    return 1;
  }
  const session = new ChatSession(randomUUID(), {
    projectId: opts.projectId,
    provider: opts.provider,
  });
  const { waitUntilExit } = render(<ChatApp client={client} session={session} host={client.http.host} />, {
    exitOnCtrlC: false,
  });
  await waitUntilExit();
  return 0;
}
