import type * as vscode from 'vscode';
import type { TokenStore } from '@executespec/core';

const KEY = 'executespec.pat';

/** {@link TokenStore} backed by VS Code's encrypted {@link vscode.SecretStorage}. */
export class SecretTokenStore implements TokenStore {
  constructor(private readonly secrets: vscode.SecretStorage) {}

  async get(): Promise<string | null> {
    return (await this.secrets.get(KEY)) ?? null;
  }

  async set(token: string): Promise<void> {
    await this.secrets.store(KEY, token.trim());
  }

  async clear(): Promise<void> {
    await this.secrets.delete(KEY);
  }
}
