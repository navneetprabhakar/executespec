import * as vscode from 'vscode';

import { makeClient } from './client';
import { RunsTreeProvider } from './runsTree';
import { errorMessage } from './util';
import { submitSpec, watchRunInteractive } from './watch';

export function activate(context: vscode.ExtensionContext): void {
  const output = vscode.window.createOutputChannel('ExecuteSpec');
  const runs = new RunsTreeProvider(() => makeClient(context));

  context.subscriptions.push(
    output,
    vscode.window.registerTreeDataProvider('executespecRuns', runs),
    vscode.commands.registerCommand('executespec.signIn', () => signIn(context, runs)),
    vscode.commands.registerCommand('executespec.signOut', async () => {
      await makeClient(context).tokens.clear();
      runs.refresh();
      vscode.window.showInformationMessage('ExecuteSpec: signed out.');
    }),
    vscode.commands.registerCommand('executespec.refreshRuns', () => runs.refresh()),
    vscode.commands.registerCommand('executespec.submitSpec', () =>
      submitSpec(context, output, runs),
    ),
    vscode.commands.registerCommand('executespec.watchRun', (item?: { runId?: string }) =>
      watchRunInteractive(context, output, item?.runId, runs),
    ),
  );
}

export function deactivate(): void {
  // nothing to clean up — the OutputChannel + tree provider are in context.subscriptions
}

async function signIn(context: vscode.ExtensionContext, runs: RunsTreeProvider): Promise<void> {
  const token = await vscode.window.showInputBox({
    prompt: 'ExecuteSpec Personal Access Token (es_pat_…)',
    password: true,
    ignoreFocusOut: true,
    placeHolder: 'es_pat_…',
  });
  if (!token) return;
  const client = makeClient(context);
  await client.tokens.set(token);
  try {
    const me = await client.api.me();
    runs.refresh();
    vscode.window.showInformationMessage(`ExecuteSpec: signed in as ${me.email}`);
  } catch (e) {
    await client.tokens.clear();
    vscode.window.showErrorMessage(`ExecuteSpec sign-in failed: ${errorMessage(e)}`);
  }
}
