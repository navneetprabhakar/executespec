import { randomBytes } from 'node:crypto';

import * as vscode from 'vscode';

import { makeClient } from './client';
import { RunsTreeProvider } from './runsTree';
import { errorMessage } from './util';
import { cancelRunInteractive, submitSpec, watchRunInteractive } from './watch';

/** One-shot coordination between {@link signIn} and the `vscode://…/auth` redirect handler. */
let pendingAuth: { state: string; resolve: (token: string | null) => void } | null = null;

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
    vscode.commands.registerCommand('executespec.cancelRun', (item?: { runId?: string }) =>
      cancelRunInteractive(context, item?.runId, runs),
    ),
    // Catches the browser redirect from the web /cli-login page:
    // vscode://executespec.executespec-vscode/auth?token=…&state=…
    vscode.window.registerUriHandler({
      handleUri(uri: vscode.Uri) {
        if (uri.path !== '/auth') return;
        const q = new URLSearchParams(uri.query);
        const token = q.get('token');
        const state = q.get('state');
        if (pendingAuth && token && state === pendingAuth.state) {
          const resolve = pendingAuth.resolve;
          pendingAuth = null;
          resolve(token);
        }
      },
    }),
  );
}

export function deactivate(): void {
  // nothing to clean up — the OutputChannel + tree provider are in context.subscriptions
}

async function signIn(context: vscode.ExtensionContext, runs: RunsTreeProvider): Promise<void> {
  const client = makeClient(context);
  const host = (
    vscode.workspace.getConfiguration('executespec').get<string>('host') || 'https://executespec.ai'
  ).replace(/\/+$/, '');

  // gcloud-style: open the browser to /cli-login?mode=vscode, which mints a PAT after the user
  // authorizes and redirects to vscode://…/auth?token=…&state=… (caught by the UriHandler above).
  const state = randomBytes(24).toString('base64url');
  const browserToken = new Promise<string | null>((resolve) => {
    pendingAuth = { state, resolve };
    setTimeout(() => {
      if (pendingAuth?.state === state) {
        pendingAuth = null;
        resolve(null);
      }
    }, 300_000);
  });

  const url = `${host}/cli-login?mode=vscode&state=${encodeURIComponent(state)}`;
  await vscode.env.openExternal(vscode.Uri.parse(url));

  let token = await vscode.window.withProgress<string | null>(
    {
      location: vscode.ProgressLocation.Notification,
      title: 'ExecuteSpec: complete sign-in in your browser…',
      cancellable: true,
    },
    (_progress, cancel) =>
      Promise.race([
        browserToken,
        new Promise<string | null>((resolve) =>
          cancel.onCancellationRequested(() => resolve(null)),
        ),
      ]),
  );

  // Fallback: paste a PAT manually (headless, or the browser round-trip didn't complete).
  if (!token) {
    token =
      (await vscode.window.showInputBox({
        prompt: 'Paste an ExecuteSpec Personal Access Token (es_pat_…), or press Esc to cancel',
        password: true,
        ignoreFocusOut: true,
        placeHolder: 'es_pat_…',
      })) ?? null;
  }
  if (!token) return;

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
