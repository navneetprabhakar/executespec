import * as vscode from 'vscode';
import { createClient, type ClientBundle } from '@executespec/core';

import { SecretTokenStore } from './secretTokenStore';

/** Build a core client bound to the configured host + the SecretStorage-backed token. */
export function makeClient(context: vscode.ExtensionContext): ClientBundle {
  const host = vscode.workspace.getConfiguration('executespec').get<string>('host') || undefined;
  return createClient({ host, tokens: new SecretTokenStore(context.secrets) });
}

export function defaultProvider(): string | undefined {
  return (
    vscode.workspace.getConfiguration('executespec').get<string>('defaultProvider') || undefined
  );
}
