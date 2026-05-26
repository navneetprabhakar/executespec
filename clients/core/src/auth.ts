import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/**
 * Where the client's PAT lives. The CLI uses {@link FileTokenStore}; the VS Code extension
 * supplies its own implementation backed by `SecretStorage`. The token is the `es_pat_…`
 * credential — the only secret the client holds.
 */
export interface TokenStore {
  get(): Promise<string | null>;
  set(token: string): Promise<void>;
  clear(): Promise<void>;
}

/** In-memory store — for tests and the extension when it injects a token per session. */
export class MemoryTokenStore implements TokenStore {
  private token: string | null;

  constructor(initial?: string | null) {
    this.token = initial ?? null;
  }

  async get(): Promise<string | null> {
    return this.token;
  }

  async set(token: string): Promise<void> {
    this.token = token.trim() || null;
  }

  async clear(): Promise<void> {
    this.token = null;
  }
}

/**
 * Stores the PAT at `~/.config/executespec/token` (override the dir with
 * `EXECUTESPEC_CONFIG_DIR`). `EXECUTESPEC_TOKEN` in the environment always wins, so CI can inject
 * a token without touching disk. Written `0600`.
 */
export class FileTokenStore implements TokenStore {
  private readonly file: string;

  constructor(configDir?: string) {
    const dir =
      configDir ??
      process.env.EXECUTESPEC_CONFIG_DIR ??
      path.join(os.homedir(), '.config', 'executespec');
    this.file = path.join(dir, 'token');
  }

  async get(): Promise<string | null> {
    const env = process.env.EXECUTESPEC_TOKEN;
    if (env && env.trim()) return env.trim();
    try {
      const t = await fs.readFile(this.file, 'utf8');
      return t.trim() || null;
    } catch {
      return null;
    }
  }

  async set(token: string): Promise<void> {
    await fs.mkdir(path.dirname(this.file), { recursive: true, mode: 0o700 });
    await fs.writeFile(this.file, token.trim() + '\n', { mode: 0o600 });
  }

  async clear(): Promise<void> {
    try {
      await fs.rm(this.file);
    } catch {
      // already gone — fine
    }
  }
}
