import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/**
 * Local persistence for the clients (design §7). The CLI uses {@link FileStateBackend}; the VS Code
 * extension supplies a `globalState`/`workspaceState`-backed implementation of the same
 * {@link StateBackend} interface. Two scopes:
 *
 *  - **global** `<configDir>/state.json` — CLI defaults (default project, default provider).
 *  - **per-directory** `<dir>/.executespec/project.json` — the project a working tree is linked to.
 *
 * Conversation history (`chats/`) is layered on top later; this module is the link + defaults layer.
 */

/** Per-directory project link, written at `<dir>/.executespec/project.json` by `link`. */
export interface ProjectLink {
  projectId: string;
  linkedAt: string;
  defaultProvider?: string;
}

/** Global CLI defaults, at `<configDir>/state.json`. */
export interface GlobalState {
  defaultProjectId?: string;
  defaultProvider?: string;
}

export interface StateBackend {
  readState(): Promise<GlobalState>;
  /** Shallow-merge `patch` into the persisted state; returns the merged result. */
  writeState(patch: Partial<GlobalState>): Promise<GlobalState>;
  readLink(dir: string): Promise<ProjectLink | null>;
  writeLink(dir: string, link: ProjectLink): Promise<void>;
  /** Remove a directory's link; returns true if one existed. */
  removeLink(dir: string): Promise<boolean>;
}

/** The CLI config dir — `$EXECUTESPEC_CONFIG_DIR` or `~/.config/executespec` (matches the token store). */
export function defaultConfigDir(): string {
  return process.env.EXECUTESPEC_CONFIG_DIR ?? path.join(os.homedir(), '.config', 'executespec');
}

/** The link file for a working directory. */
export function projectLinkPath(dir: string): string {
  return path.join(dir, '.executespec', 'project.json');
}

/** File-backed {@link StateBackend} for the CLI. */
export class FileStateBackend implements StateBackend {
  private readonly stateFile: string;

  constructor(configDir: string = defaultConfigDir()) {
    this.stateFile = path.join(configDir, 'state.json');
  }

  async readState(): Promise<GlobalState> {
    return (await readJson<GlobalState>(this.stateFile)) ?? {};
  }

  async writeState(patch: Partial<GlobalState>): Promise<GlobalState> {
    const merged = { ...(await this.readState()), ...patch };
    await writeJson(this.stateFile, merged, 0o600);
    return merged;
  }

  async readLink(dir: string): Promise<ProjectLink | null> {
    return readJson<ProjectLink>(projectLinkPath(dir));
  }

  async writeLink(dir: string, link: ProjectLink): Promise<void> {
    await writeJson(projectLinkPath(dir), link, 0o644);
  }

  async removeLink(dir: string): Promise<boolean> {
    try {
      await fs.rm(projectLinkPath(dir));
      return true;
    } catch {
      return false;
    }
  }
}

/**
 * Resolve which project a command should target, in precedence order:
 *   explicit flag  >  this directory's link  >  the global default.
 * Returns undefined when none applies (a project-less run).
 */
export async function resolveProjectId(
  backend: StateBackend,
  dir: string,
  explicit?: string | null,
): Promise<string | undefined> {
  if (explicit && explicit.trim()) return explicit.trim();
  const link = await backend.readLink(dir);
  if (link?.projectId) return link.projectId;
  return (await backend.readState()).defaultProjectId;
}

async function readJson<T>(file: string): Promise<T | null> {
  try {
    return JSON.parse(await fs.readFile(file, 'utf8')) as T;
  } catch {
    return null;
  }
}

async function writeJson(file: string, value: unknown, mode: number): Promise<void> {
  await fs.mkdir(path.dirname(file), { recursive: true, mode: 0o700 });
  await fs.writeFile(file, JSON.stringify(value, null, 2) + '\n', { mode });
}
