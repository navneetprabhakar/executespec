import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import path from 'node:path';

import type { ToolCallFrame, ToolEnvelope } from '../types.js';
import { safeResolve, ToolError } from './pathSafety.js';

// Truncation limits — match the server token-cost controls (readFile 8k, shell 6k, diff 5k chars).
const READ_LIMIT = 8000;
const SHELL_LIMIT = 6000;
const DIFF_LIMIT = 5000;
const MAX_HITS = 100;
const MAX_FILE_BYTES = 512 * 1024;

const IGNORED_DIRS = new Set([
  '.git',
  'node_modules',
  'dist',
  'build',
  'target',
  '.gradle',
  '.idea',
  '.next',
  'out',
  'vendor',
  '__pycache__',
  '.venv',
]);

/** Lets a client gate side-effecting commands (shell/build/test/commit). Default: allow. */
export interface ToolPermissions {
  allowCommand?(tool: string, command: string): boolean | Promise<boolean>;
}

export interface ToolRuntimeOptions {
  /** Absolute path the run operates on — the user's local working tree. */
  workspaceRoot: string;
  permissions?: ToolPermissions;
  /** Timeout for shell/git/test commands (default 120s). */
  commandTimeoutMs?: number;
  /** Timeout for buildProject (default 600s). */
  buildTimeoutMs?: number;
}

/**
 * The "hands" of the local-hands/remote-brain model. Executes a single {@link ToolCallFrame} from
 * the server-side agent against the local working tree and returns a {@link ToolEnvelope} that is
 * byte-compatible with the server's `ToolResult` serialization.
 *
 * <p>This is mechanical only — read/write/edit/list/search/git/shell/build. The intelligence (which
 * tool, which args, when to stop) stays server-side. Nothing here is secret sauce.
 */
export class ToolRuntime {
  private readonly root: string;
  private readonly perms: ToolPermissions;
  private readonly timeoutMs: number;
  private readonly buildTimeoutMs: number;

  constructor(opts: ToolRuntimeOptions) {
    this.root = path.resolve(opts.workspaceRoot);
    this.perms = opts.permissions ?? {};
    this.timeoutMs = opts.commandTimeoutMs ?? 120_000;
    this.buildTimeoutMs = opts.buildTimeoutMs ?? 600_000;
  }

  async execute(frame: ToolCallFrame): Promise<ToolEnvelope> {
    try {
      const content = await this.dispatch(frame.name, asRecord(frame.args));
      return { ok: true, content };
    } catch (e) {
      if (e instanceof ToolError) return { ok: false, kind: e.kind, message: e.message };
      const message = e instanceof Error ? e.message : String(e);
      return { ok: false, kind: 'INTERNAL', message };
    }
  }

  private async dispatch(name: string, args: Record<string, unknown>): Promise<string> {
    switch (name) {
      case 'readFile':
        return this.readFile(args);
      case 'writeFile':
        return this.writeFile(args);
      case 'editFile':
        return this.editFile(args);
      case 'listFiles':
        return this.listFiles(args);
      case 'searchFiles':
        return this.searchFiles(args);
      case 'gitStatus':
        return this.gitStatus();
      case 'gitDiff':
        return this.gitDiff(args);
      case 'gitCommit':
        return this.gitCommit(args);
      case 'shell':
        return this.shell(args);
      case 'buildProject':
        return this.buildProject(args);
      case 'runTests':
        return this.runTests(args);
      case 'completeTask':
        return optString(args, 'summary') ?? 'task marked complete';
      default:
        throw new ToolError('INVALID_ARGS', `unknown tool: ${name}`);
    }
  }

  private async readFile(args: Record<string, unknown>): Promise<string> {
    const rel = reqString(args, 'path');
    const file = safeResolve(this.root, rel);
    try {
      return truncate(await fs.readFile(file, 'utf8'), READ_LIMIT);
    } catch (e) {
      throw new ToolError('NOT_FOUND', `cannot read ${rel}: ${(e as Error).message}`);
    }
  }

  private async writeFile(args: Record<string, unknown>): Promise<string> {
    const rel = reqString(args, 'path');
    const file = safeResolve(this.root, rel);
    const content = reqString(args, 'content');
    await fs.mkdir(path.dirname(file), { recursive: true });
    await fs.writeFile(file, content, 'utf8');
    return `wrote ${Buffer.byteLength(content)} bytes to ${rel}`;
  }

  private async editFile(args: Record<string, unknown>): Promise<string> {
    const rel = reqString(args, 'path');
    const file = safeResolve(this.root, rel);
    const oldStr = reqString(args, 'oldString');
    const newStr = reqString(args, 'newString');
    const replaceAll = args['replaceAll'] === true;

    let content: string;
    try {
      content = await fs.readFile(file, 'utf8');
    } catch (e) {
      throw new ToolError('NOT_FOUND', `cannot read ${rel}: ${(e as Error).message}`);
    }
    if (!content.includes(oldStr)) {
      throw new ToolError('INVALID_ARGS', `oldString not found in ${rel}`);
    }
    const count = content.split(oldStr).length - 1;
    if (!replaceAll && count > 1) {
      throw new ToolError(
        'INVALID_ARGS',
        `oldString is not unique in ${rel} (${count} matches) — pass replaceAll or add surrounding context`,
      );
    }
    const updated = replaceAll ? content.split(oldStr).join(newStr) : content.replace(oldStr, newStr);
    await fs.writeFile(file, updated, 'utf8');
    const n = replaceAll ? count : 1;
    return `edited ${rel} (${n} replacement${n === 1 ? '' : 's'})`;
  }

  private async listFiles(args: Record<string, unknown>): Promise<string> {
    const rel = optString(args, 'path') ?? '.';
    const dir = safeResolve(this.root, rel);
    let entries: import('node:fs').Dirent[];
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch (e) {
      throw new ToolError('NOT_FOUND', `cannot list ${rel}: ${(e as Error).message}`);
    }
    const names = entries
      .filter((d) => !IGNORED_DIRS.has(d.name))
      .map((d) => (d.isDirectory() ? d.name + '/' : d.name))
      .sort();
    return names.length ? names.join('\n') : '(empty)';
  }

  private async searchFiles(args: Record<string, unknown>): Promise<string> {
    const query =
      optString(args, 'query') ?? optString(args, 'pattern') ?? optString(args, 'regex');
    if (!query) throw new ToolError('INVALID_ARGS', `'query' (string) is required`);
    const start = safeResolve(this.root, optString(args, 'path') ?? '.');
    const hits: string[] = [];
    await this.walk(start, query.toLowerCase(), hits);
    return hits.length ? truncate(hits.join('\n'), READ_LIMIT) : 'no matches';
  }

  private async walk(dir: string, queryLower: string, hits: string[]): Promise<void> {
    if (hits.length >= MAX_HITS) return;
    let entries: import('node:fs').Dirent[];
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      if (hits.length >= MAX_HITS) return;
      if (IGNORED_DIRS.has(e.name)) continue;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        await this.walk(full, queryLower, hits);
      } else if (e.isFile()) {
        let buf: Buffer;
        try {
          buf = await fs.readFile(full);
        } catch {
          continue;
        }
        if (buf.length > MAX_FILE_BYTES || isBinary(buf)) continue;
        const lines = buf.toString('utf8').split('\n');
        for (let i = 0; i < lines.length; i++) {
          const line = lines[i];
          if (line !== undefined && line.toLowerCase().includes(queryLower)) {
            hits.push(`${path.relative(this.root, full)}:${i + 1}: ${line.trim().slice(0, 200)}`);
            if (hits.length >= MAX_HITS) return;
          }
        }
      }
    }
  }

  private async gitStatus(): Promise<string> {
    const { out } = await runCommand('git status --porcelain=v1 -b', this.root, this.timeoutMs);
    return truncate(out.trim() || '(clean)', READ_LIMIT);
  }

  private async gitDiff(args: Record<string, unknown>): Promise<string> {
    const rel = optString(args, 'path');
    let cmd = 'git diff';
    if (rel) {
      safeResolve(this.root, rel); // validate it stays in the workspace
      cmd = `git diff -- ${shellQuote(rel)}`;
    }
    const { out } = await runCommand(cmd, this.root, this.timeoutMs);
    return truncate(out.trim() || '(no changes)', DIFF_LIMIT);
  }

  private async gitCommit(args: Record<string, unknown>): Promise<string> {
    const message = reqString(args, 'message');
    await this.ensureAllowed('gitCommit', `git commit -m ${message}`);
    await runCommand('git add -A', this.root, this.timeoutMs);
    const { out, code } = await runCommand(
      `git commit -m ${shellQuote(message)}`,
      this.root,
      this.timeoutMs,
    );
    return `${truncate(out, SHELL_LIMIT)}\n[exit code ${code ?? 'null'}]`;
  }

  private async shell(args: Record<string, unknown>): Promise<string> {
    const command = reqString(args, 'command');
    await this.ensureAllowed('shell', command);
    const { out, code } = await runCommand(command, this.root, this.timeoutMs);
    const body = truncate(out, SHELL_LIMIT);
    return code && code !== 0 ? `${body}\n[exit code ${code}]` : body;
  }

  private async buildProject(args: Record<string, unknown>): Promise<string> {
    const command = optString(args, 'command') ?? (await detectBuild(this.root));
    if (!command) {
      throw new ToolError('INVALID_ARGS', 'no build command provided and none could be detected');
    }
    await this.ensureAllowed('buildProject', command);
    const { out, code } = await runCommand(command, this.root, this.buildTimeoutMs);
    return `$ ${command}\n${truncate(out, SHELL_LIMIT)}\n[exit code ${code ?? 'null'}]`;
  }

  private async runTests(args: Record<string, unknown>): Promise<string> {
    const command = optString(args, 'command') ?? (await detectTest(this.root));
    if (!command) {
      throw new ToolError('INVALID_ARGS', 'no test command provided and none could be detected');
    }
    await this.ensureAllowed('runTests', command);
    const { out, code } = await runCommand(command, this.root, this.buildTimeoutMs);
    return `$ ${command}\n${truncate(out, SHELL_LIMIT)}\n[exit code ${code ?? 'null'}]`;
  }

  private async ensureAllowed(tool: string, command: string): Promise<void> {
    const allow = this.perms.allowCommand ? await this.perms.allowCommand(tool, command) : true;
    if (!allow) throw new ToolError('FORBIDDEN', `command not permitted by client policy: ${command}`);
  }
}

// ── helpers ───────────────────────────────────────────────────────────────

function asRecord(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
}

function reqString(args: Record<string, unknown>, key: string): string {
  const v = args[key];
  if (typeof v !== 'string') throw new ToolError('INVALID_ARGS', `'${key}' (string) is required`);
  return v;
}

function optString(args: Record<string, unknown>, key: string): string | undefined {
  const v = args[key];
  return typeof v === 'string' ? v : undefined;
}

function truncate(s: string, max: number): string {
  return s.length > max ? `${s.slice(0, max)}\n…[truncated ${s.length - max} chars]` : s;
}

function shellQuote(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`;
}

function isBinary(buf: Buffer): boolean {
  const n = Math.min(buf.length, 8000);
  for (let i = 0; i < n; i++) if (buf[i] === 0) return true;
  return false;
}

async function exists(p: string): Promise<boolean> {
  try {
    await fs.access(p);
    return true;
  } catch {
    return false;
  }
}

async function detectBuild(root: string): Promise<string | null> {
  if (await exists(path.join(root, 'pom.xml'))) return 'mvn -q -DskipTests compile';
  if (
    (await exists(path.join(root, 'build.gradle'))) ||
    (await exists(path.join(root, 'build.gradle.kts')))
  ) {
    return './gradlew compileJava -x test';
  }
  if (await exists(path.join(root, 'package.json'))) return 'npm run build';
  if (await exists(path.join(root, 'Cargo.toml'))) return 'cargo build';
  if (await exists(path.join(root, 'go.mod'))) return 'go build ./...';
  if (
    (await exists(path.join(root, 'pyproject.toml'))) ||
    (await exists(path.join(root, 'setup.py')))
  ) {
    return 'python -m compileall -q .';
  }
  return null;
}

async function detectTest(root: string): Promise<string | null> {
  if (await exists(path.join(root, 'pom.xml'))) return 'mvn -q test';
  if (
    (await exists(path.join(root, 'build.gradle'))) ||
    (await exists(path.join(root, 'build.gradle.kts')))
  ) {
    return './gradlew test';
  }
  if (await exists(path.join(root, 'package.json'))) return 'npm test';
  if (await exists(path.join(root, 'Cargo.toml'))) return 'cargo test';
  if (await exists(path.join(root, 'go.mod'))) return 'go test ./...';
  if (
    (await exists(path.join(root, 'pyproject.toml'))) ||
    (await exists(path.join(root, 'setup.py')))
  ) {
    return 'pytest -q';
  }
  return null;
}

function runCommand(
  command: string,
  cwd: string,
  timeoutMs: number,
): Promise<{ code: number | null; out: string }> {
  return new Promise((resolve) => {
    const child = spawn(command, { cwd, shell: true });
    let out = '';
    let killed = false;
    const onData = (d: Buffer): void => {
      out += d.toString();
      if (out.length > 200_000) {
        out = out.slice(0, 200_000);
        killed = true;
        child.kill('SIGKILL');
      }
    };
    child.stdout?.on('data', onData);
    child.stderr?.on('data', onData);
    const timer = setTimeout(() => {
      killed = true;
      child.kill('SIGKILL');
      out += `\n…[timed out after ${timeoutMs}ms]`;
    }, timeoutMs);
    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({ code: killed ? (code ?? 137) : code, out });
    });
    child.on('error', (err) => {
      clearTimeout(timer);
      resolve({ code: null, out: `${out}\n[spawn error] ${err.message}` });
    });
  });
}
