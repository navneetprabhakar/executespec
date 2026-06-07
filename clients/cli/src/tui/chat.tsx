import { randomUUID } from 'node:crypto';

import {
  ChatSession,
  RunEventType,
  streamRunEvents,
  type ClientBundle,
  type RunDetail,
  type RunEvent,
} from '@executespec/core';
import { Box, render, Static, Text, useApp, useInput } from 'ink';
import TextInput from 'ink-text-input';
import { useCallback, useMemo, useRef, useState } from 'react';

import { errorMessage } from '../ui.js';
import { looksLikeShellCommand } from '../repl.js';

/**
 * Rich terminal chat UI (Ink) — the conversational surface over `POST /api/spec/chat`. Modeled on the
 * post-login experience of the other coding-agent CLIs (Gemini / Claude Code / Codex): a gradient
 * banner + "signed in as" + getting-started tips printed once into a scrolling transcript
 * ({@link Static}), a live region that streams the current assistant turn + its tool calls, a bordered
 * input box, and a footer status bar.
 *
 * <p>**Phase 2 — inline run streaming.** When a chat turn submits a run (a tool result carrying a
 * `runId`), the TUI subscribes to that run's event stream and renders its progress inline — status,
 * tasks, files written, cost — and surfaces the plan gate as an inline `[y/N]` prompt. This makes the
 * remote-brain pipeline *show its work* in the terminal, the way a local coding agent does, without
 * the CLI ever embedding the agent loop. Drives the same {@link ChatSession} the readline REPL uses.
 */

type Entry =
  | { kind: 'welcome'; host: string; cwd: string; email?: string; tier?: string; projectId?: string }
  | { kind: 'user'; text: string }
  | { kind: 'assistant'; text: string }
  | { kind: 'tool'; text: string }
  | { kind: 'run'; text: string }
  | { kind: 'note'; text: string }
  | { kind: 'error'; text: string };

/** Live, in-place view of the run a chat turn kicked off (cleared once the run is terminal). */
interface RunView {
  runId: string;
  status: string;
  files: number;
  lastLine: string;
  cost?: string;
}

/** A plan gate awaiting the user's inline y/N. */
interface GateInfo {
  runId: string;
  credits?: number;
  usd?: string;
}

interface ChatAppProps {
  client: ClientBundle;
  session: ChatSession;
  host: string;
  /** Identity for the "signed in as" line (from `GET /auth/me`); omitted in tests. */
  me?: { email?: string; tier?: string };
  projectId?: string;
  /** Called once when the user exits (Ctrl-C at idle / `/exit`). */
  onExit?: () => void;
}

export function ChatApp({ client, session, host, me, projectId, onExit }: ChatAppProps): React.ReactElement {
  const { exit } = useApp();

  const welcome = useMemo<Entry>(
    () => ({
      kind: 'welcome',
      host,
      cwd: process.cwd(),
      email: me?.email,
      tier: me?.tier,
      projectId,
    }),
    [host, me, projectId],
  );

  const [entries, setEntries] = useState<Entry[]>(() => [welcome]);
  const [live, setLive] = useState('');
  const [toolLines, setToolLines] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [input, setInput] = useState('');
  const [status, setStatus] = useState('remote brain · spec.chat');
  const [provider, setProviderLabel] = useState(session.provider ?? 'auto');
  const [runView, setRunView] = useState<RunView | null>(null);
  const [gate, setGate] = useState<GateInfo | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const gateResolveRef = useRef<((approved: boolean) => void) | null>(null);

  const push = useCallback((e: Entry) => setEntries((prev) => [...prev, e]), []);

  const quit = useCallback(() => {
    onExit?.();
    exit();
  }, [exit, onExit]);

  useInput((ch, key) => {
    // Ctrl-C: resolve any open gate as a reject, cancel the in-flight turn/run, else leave.
    if (key.ctrl && ch === 'c') {
      if (gate) {
        const r = gateResolveRef.current;
        gateResolveRef.current = null;
        setGate(null);
        r?.(false);
      }
      if (abortRef.current) abortRef.current.abort();
      else quit();
    }
  });

  /** Surface the plan gate inline and resolve once the user answers (in onSubmit) or aborts. */
  const askGate = useCallback(
    (g: GateInfo): Promise<boolean> =>
      new Promise<boolean>((resolve) => {
        gateResolveRef.current = resolve;
        setGate(g);
        push({
          kind: 'note',
          text:
            `⏸ plan ready${g.credits != null ? ` · ${g.credits} credit(s)` : ''}` +
            `${g.usd ? ` · ~$${g.usd}` : ''} — approve? [y/N]`,
        });
      }),
    [push],
  );

  /** Stream a submitted run inline: update the live run panel, handle the gate, summarize on terminal. */
  const watchRunInline = useCallback(
    async (runId: string, ac: AbortController): Promise<void> => {
      setRunView({ runId, status: 'submitted', files: 0, lastLine: '' });
      setStatus(`run ${runId.slice(0, 8)} · streaming`);
      try {
        for await (const ev of streamRunEvents(client.api, runId, { signal: ac.signal })) {
          setRunView((prev) => (prev ? nextRunView(prev, ev) : prev));
          if (ev.type === RunEventType.RUN_STATUS && ev.payload) {
            setStatus(`run ${runId.slice(0, 8)} · ${runStatusOf(ev.payload)}`);
          }
          if (ev.type === RunEventType.GATE_OPENED) {
            const g = parseGate(runId, ev);
            const approved = await askGate(g);
            try {
              await client.api.approve(
                runId,
                approved
                  ? { approved: true, creditsConfirmed: g.credits ?? null, executionMode: 'REALTIME' }
                  : { approved: false },
              );
            } catch (e) {
              push({ kind: 'error', text: `approve failed: ${errorMessage(e)}` });
            }
            if (!approved) {
              push({ kind: 'note', text: 'plan rejected — run stopped' });
              break;
            }
          }
          if (isTerminalRunEvent(ev)) break;
        }
      } catch (e) {
        if (!ac.signal.aborted) push({ kind: 'error', text: errorMessage(e) });
      }
      if (ac.signal.aborted) {
        push({
          kind: 'note',
          text: `detached from run ${runId.slice(0, 8)} — it keeps running · executespec run watch ${runId}`,
        });
        setRunView(null);
        return;
      }
      try {
        const d = await client.api.getRun(runId);
        push({ kind: 'run', text: summarizeRunDetail(d) });
        setStatus(`run ${runId.slice(0, 8)} · ${runStatusOf(d.status)}`);
      } catch {
        // best-effort final summary
      }
      setRunView(null);
    },
    [client, push, askGate],
  );

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
      let submittedRunId: string | undefined;
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
            const rid = extractRunId(ev.result);
            if (rid) submittedRunId = rid;
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
      }
      // Commit the chat turn into the scrollback before streaming any run it kicked off.
      const committed: Entry[] = [
        ...turnTools.map((t): Entry => ({ kind: 'tool', text: t })),
        ...(asst ? [{ kind: 'assistant', text: asst } as Entry] : []),
      ];
      if (committed.length) setEntries((prev) => [...prev, ...committed]);
      setLive('');
      setToolLines([]);
      // If the assistant submitted a run, stream it inline (the coding-agent "show your work" view).
      if (submittedRunId && !ac.signal.aborted) {
        await watchRunInline(submittedRunId, ac);
      }
      setBusy(false);
      abortRef.current = null;
    },
    [client, session, push, watchRunInline],
  );

  const onSubmit = useCallback(
    (value: string): void => {
      const line = value.trim();
      setInput('');
      // A plan-gate answer takes precedence — it fires while a run is streaming (busy === true).
      if (gate) {
        const approved = /^y(es)?$/i.test(line);
        setGate(null);
        push({ kind: 'note', text: approved ? '→ approved, continuing…' : '→ plan rejected' });
        const r = gateResolveRef.current;
        gateResolveRef.current = null;
        r?.(approved);
        return;
      }
      if (!line || busy) return;
      if (line === '/exit' || line === '/quit' || line === '/q') {
        quit();
        return;
      }
      if (line === '/clear') {
        // Reset the model's history but keep the visible transcript (Static is append-only).
        session.clear();
        push({ kind: 'note', text: '(history cleared — the next message starts a fresh conversation)' });
        return;
      }
      if (line.startsWith('/provider')) {
        const p = line.split(/\s+/)[1];
        if (p) {
          session.setProvider(p.toLowerCase());
          setProviderLabel(p.toLowerCase());
          push({ kind: 'note', text: `provider → ${p.toLowerCase()}` });
        } else {
          push({ kind: 'note', text: `provider: ${session.provider ?? 'auto'}` });
        }
        return;
      }
      if (line === '/help') {
        push({
          kind: 'note',
          text: '/provider <name> · /clear · /exit · Ctrl-C cancels a reply/run or exits at the prompt',
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
    [busy, gate, push, quit, runTurn, session],
  );

  const barWidth = Math.min(process.stdout.columns ?? 80, 120);

  return (
    <Box flexDirection="column">
      <Static items={entries}>{(e, i) => <TranscriptLine key={i} entry={e} />}</Static>

      {busy && (live.length > 0 || toolLines.length > 0) ? (
        <Box flexDirection="column" marginLeft={2} marginTop={1}>
          {toolLines.map((t, i) => (
            <Text key={i} dimColor>
              {t}
            </Text>
          ))}
          {live ? <Text color="green">{live}</Text> : null}
        </Box>
      ) : null}

      {runView ? (
        <Box flexDirection="column" marginLeft={2} marginTop={1}>
          <Text color="cyan">
            ▶ run {runView.runId.slice(0, 8)} · {runView.status}
          </Text>
          {runView.lastLine ? <Text dimColor>{'  ' + trunc(runView.lastLine, 80)}</Text> : null}
          <Text dimColor>
            {'  '}
            {runView.files} file(s){runView.cost ? ` · ${runView.cost}` : ''}
          </Text>
        </Box>
      ) : null}

      <Box
        marginTop={1}
        borderStyle="round"
        borderColor={gate ? 'yellow' : busy ? 'gray' : 'cyan'}
        paddingX={1}
      >
        <Text color={gate ? 'yellow' : 'cyan'}>{gate ? '? ' : busy ? '· ' : '› '}</Text>
        {gate ? (
          <TextInput value={input} onChange={setInput} onSubmit={onSubmit} placeholder="approve plan? [y/N]" />
        ) : busy ? (
          <Text dimColor>working… (Ctrl-C to cancel)</Text>
        ) : (
          <TextInput
            value={input}
            onChange={setInput}
            onSubmit={onSubmit}
            placeholder="Type a spec to build, or /help"
          />
        )}
      </Box>

      <Box width={barWidth} justifyContent="space-between">
        <Text dimColor>{shortenPath(process.cwd())}</Text>
        <Text dimColor>{busy ? '⏳ ' + status : status}</Text>
        <Text dimColor>
          {provider} · {hostLabel(host)}
        </Text>
      </Box>
    </Box>
  );
}

function TranscriptLine({ entry }: { entry: Entry }): React.ReactElement | null {
  switch (entry.kind) {
    case 'welcome':
      return <WelcomeHeader entry={entry} />;
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
    case 'run':
      return (
        <Box marginTop={1}>
          <Text color="cyan">{entry.text}</Text>
        </Box>
      );
    case 'note':
      return <Text color="yellow">{entry.text}</Text>;
    case 'error':
      return <Text color="red">{`! ${entry.text}`}</Text>;
    default:
      return null;
  }
}

// ── Run-event helpers (shared shape with commands/run.ts) ─────────────────────────────────────────

/** Pull a runId out of a chat tool-result envelope (submit_run / approve / …). */
function extractRunId(result: string): string | undefined {
  try {
    const e = JSON.parse(result) as { runId?: unknown; run_id?: unknown };
    const v = e.runId ?? e.run_id;
    return typeof v === 'string' && v ? v : undefined;
  } catch {
    return undefined;
  }
}

/** Gate payload is `"PLAN"` or `"PLAN|<credits>|<usd>"`. */
function parseGate(runId: string, ev: RunEvent): GateInfo {
  const parts = (ev.payload ?? '').split('|');
  const raw = parts[1];
  const credits = raw != null && raw !== '' && Number.isFinite(Number(raw)) ? Number(raw) : undefined;
  const usd = parts[2] || undefined;
  return { runId, credits, usd };
}

function runStatusOf(payload: string): string {
  return payload.split('|')[0] ?? payload;
}

function isTerminalRunEvent(ev: RunEvent): boolean {
  if (ev.type === RunEventType.COMPLETE) return true;
  if (ev.type === RunEventType.RUN_STATUS && ev.payload) {
    const s = runStatusOf(ev.payload);
    return s === 'COMPLETE' || s === 'FAILED' || s === 'PARTIAL' || s === 'CANCELLED';
  }
  return false;
}

function nextRunView(prev: RunView, ev: RunEvent): RunView {
  switch (ev.type) {
    case RunEventType.RUN_STATUS:
      return ev.payload ? { ...prev, status: runStatusOf(ev.payload) } : prev;
    case RunEventType.TASK_STATUS:
      return { ...prev, lastLine: `task ${ev.taskId ?? ''} ${ev.payload ?? ''}`.trim() };
    case RunEventType.FILE_WRITTEN:
      return { ...prev, files: prev.files + 1, lastLine: `✎ ${ev.payload ?? ''}` };
    case RunEventType.LOG:
      return ev.payload ? { ...prev, lastLine: ev.payload } : prev;
    case RunEventType.COST_UPDATE:
      return ev.payload ? { ...prev, cost: ev.payload } : prev;
    case RunEventType.COMPLETE:
      return ev.payload ? { ...prev, status: runStatusOf(ev.payload) } : prev;
    default:
      return prev;
  }
}

function summarizeRunDetail(d: RunDetail): string {
  const id = d.runId.slice(0, 8);
  const score = d.verifiedScore != null ? ` · score ${d.verifiedScore}` : '';
  const tests = d.testsTotal != null ? ` · tests ${d.testsPassed ?? 0}/${d.testsTotal}` : '';
  const pr = d.scmPrUrl ?? d.githubPrUrl;
  const prStr = pr ? ` · PR ${pr}` : '';
  const reason = d.statusReason ? ` (${d.statusReason})` : '';
  return `✓ run ${id} · ${d.status}${reason}${score}${tests}${prStr}`;
}

// ── Welcome header ──────────────────────────────────────────────────────────────────────────────

function WelcomeHeader({
  entry,
}: {
  entry: Extract<Entry, { kind: 'welcome' }>;
}): React.ReactElement {
  const wide = (process.stdout.columns ?? 80) >= 34;
  return (
    <Box flexDirection="column" marginBottom={1}>
      {wide ? (
        <BigWord word="spec" />
      ) : (
        <Text bold>
          <GradientText text="executespec" />
        </Text>
      )}

      <Box marginTop={wide ? 1 : 0}>
        <Text dimColor>spec-driven coding — the agent loop runs on the server · executespec</Text>
      </Box>

      <Box marginTop={1}>
        <Text color="green" bold>
          ✓ Signed in
        </Text>
        {entry.email ? <Text> as {entry.email}</Text> : null}
        {entry.tier ? <Text dimColor> ({entry.tier})</Text> : null}
        <Text dimColor>{`  →  ${hostLabel(entry.host)}`}</Text>
      </Box>

      <Box flexDirection="column" marginTop={1}>
        <Text dimColor>Getting started</Text>
        <Text> 1. Describe what to build — I'll plan it and (with your OK) run the pipeline.</Text>
        <Text> 2. Be specific: language, framework, and the repo or project to target.</Text>
        <Text>
          {' 3. '}
          <Text color="cyan">/help</Text> for commands · <Text color="cyan">/provider</Text> to switch
          model · Ctrl-C to cancel.
        </Text>
      </Box>

      {entry.projectId ? (
        <Box marginTop={1}>
          <Text dimColor>linked project: {entry.projectId}</Text>
        </Box>
      ) : null}
    </Box>
  );
}

// ── Gradient banner (zero-dep: truecolor per-column interpolation) ────────────────────────────────

const CYAN: [number, number, number] = [54, 208, 255]; // #36d0ff
const MAGENTA: [number, number, number] = [192, 96, 255]; // #c060ff

/** Filled-block 5-row glyphs. Every row is exactly 6 cols so columns line up across letters. */
const GLYPHS: Record<string, [string, string, string, string, string]> = {
  s: ['██████', '██    ', '██████', '    ██', '██████'],
  p: ['██████', '██  ██', '██████', '██    ', '██    '],
  e: ['██████', '██    ', '█████ ', '██    ', '██████'],
  c: ['██████', '██    ', '██    ', '██    ', '██████'],
};
const BLANK: [string, string, string, string, string] = [
  '      ',
  '      ',
  '      ',
  '      ',
  '      ',
];

/** Render a word in big filled-block letters with a left→right cyan→magenta gradient (one hue per column). */
function BigWord({ word }: { word: string }): React.ReactElement {
  const glyphs = [...word.toLowerCase()].map((c) => GLYPHS[c] ?? BLANK);
  const rows = [0, 1, 2, 3, 4].map((r) => glyphs.map((g) => g[r]).join(' '));
  const width = rows[0]?.length ?? 1;
  return (
    <Box flexDirection="column">
      {rows.map((line, r) => (
        <Text key={r}>
          {[...line].map((chr, x) => (
            <Text
              key={x}
              bold
              color={chr === ' ' ? undefined : lerpHex(CYAN, MAGENTA, x / Math.max(width - 1, 1))}
            >
              {chr}
            </Text>
          ))}
        </Text>
      ))}
    </Box>
  );
}

/** Inline gradient text for the narrow-terminal fallback. */
function GradientText({ text }: { text: string }): React.ReactElement {
  const n = Math.max(text.length - 1, 1);
  return (
    <>
      {[...text].map((ch, i) => (
        <Text key={i} bold color={lerpHex(CYAN, MAGENTA, i / n)}>
          {ch}
        </Text>
      ))}
    </>
  );
}

function lerpHex(from: [number, number, number], to: [number, number, number], t: number): string {
  const u = Math.max(0, Math.min(1, t));
  const mix = (a: number, b: number): number => Math.round(a + (b - a) * u);
  const h = (n: number): string => n.toString(16).padStart(2, '0');
  return `#${h(mix(from[0], to[0]))}${h(mix(from[1], to[1]))}${h(mix(from[2], to[2]))}`;
}

// ── small helpers ─────────────────────────────────────────────────────────────────────────────

function trunc(s: string, n: number): string {
  if (!s) return '';
  return s.length > n ? s.slice(0, n) + '…' : s;
}

/** Strip the scheme so the footer reads `localhost:8080`, not `http://localhost:8080`. */
function hostLabel(host: string): string {
  return host.replace(/^https?:\/\//, '').replace(/\/+$/, '');
}

/** Home-relative, tail-trimmed cwd for the footer (e.g. `~/…/executespec`). */
function shortenPath(p: string): string {
  const home = process.env.HOME;
  const s = home && p.startsWith(home) ? '~' + p.slice(home.length) : p;
  const parts = s.split('/');
  if (parts.length > 4) return `${parts[0] ?? ''}/…/${parts.slice(-2).join('/')}`;
  return s;
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
 * Launch the Ink chat TUI. Resolves the identity once (for the "signed in as" line, which also
 * validates the token) before mounting. Returns when the user exits. A missing/invalid token short-
 * circuits with a sign-in hint (return 1) so the bare `executespec` path can message it like the REPL.
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
  let me: { email?: string; tier?: string } | undefined;
  try {
    const m = await client.api.me();
    me = { email: m.email, tier: m.tier };
  } catch {
    process.stderr.write(
      'Your session was rejected. Run `executespec auth login` to sign in again.\n',
    );
    return 1;
  }

  const session = new ChatSession(randomUUID(), {
    projectId: opts.projectId,
    provider: opts.provider,
  });
  const { waitUntilExit } = render(
    <ChatApp client={client} session={session} host={client.http.host} me={me} projectId={opts.projectId} />,
    { exitOnCtrlC: false },
  );
  await waitUntilExit();
  return 0;
}
