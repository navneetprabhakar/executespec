import * as vscode from 'vscode';
import {
  RunEventType,
  streamRunEvents,
  type ApprovalRequest,
  type ClientBundle,
  type RunEvent,
  type RunRequest,
} from '@executespec/core';

import { defaultProvider, makeClient } from './client';
import type { RunsTreeProvider } from './runsTree';
import { errorMessage, isTerminal, renderEvent } from './util';

export async function submitSpec(
  context: vscode.ExtensionContext,
  output: vscode.OutputChannel,
  provider: RunsTreeProvider,
): Promise<void> {
  const client = makeClient(context);
  if (!(await client.tokens.get())) {
    vscode.window.showWarningMessage('ExecuteSpec: sign in first (ExecuteSpec: Sign In).');
    return;
  }
  const spec = await getSpec();
  if (!spec) return;
  const repo = await vscode.window.showInputBox({
    prompt: 'Repo URL (optional — leave blank for a fresh repo)',
    ignoreFocusOut: true,
  });

  const req: RunRequest = {
    userRequest: spec,
    source: 'REST',
    repoUrl: repo || undefined,
    aiProvider: defaultProvider() as RunRequest['aiProvider'],
  };
  let runId: string;
  try {
    const summary = await client.api.submitRun(req);
    runId = summary.runId;
    output.show(true);
    output.appendLine(`\n=== Run ${summary.runId} (${summary.status}) ===`);
  } catch (e) {
    vscode.window.showErrorMessage(`ExecuteSpec submit failed: ${errorMessage(e)}`);
    return;
  }
  provider.refresh();
  await watchRun(client, runId, output, provider);
}

export async function watchRunInteractive(
  context: vscode.ExtensionContext,
  output: vscode.OutputChannel,
  runId: string | undefined,
  provider: RunsTreeProvider,
): Promise<void> {
  const client = makeClient(context);
  const id = runId ?? (await vscode.window.showInputBox({ prompt: 'Run ID', ignoreFocusOut: true }));
  if (!id) return;
  output.show(true);
  output.appendLine(`\n=== Watching ${id} ===`);
  await watchRun(client, id, output, provider);
}

async function watchRun(
  client: ClientBundle,
  runId: string,
  output: vscode.OutputChannel,
  provider: RunsTreeProvider,
): Promise<void> {
  const ac = new AbortController();
  try {
    for await (const ev of streamRunEvents(client.api, runId, { signal: ac.signal })) {
      const line = renderEvent(ev);
      if (line) output.appendLine(line);
      if (ev.type === RunEventType.GATE_OPENED) {
        await handleGate(client, runId, ev, output);
      }
      if (isTerminal(ev)) {
        ac.abort();
        break;
      }
    }
  } catch (e) {
    if (!ac.signal.aborted) output.appendLine(`stream error: ${errorMessage(e)}`);
  }
  provider.refresh();
  try {
    const d = await client.api.getRun(runId);
    output.appendLine(`\n${d.runId} ${d.status}  verifiedScore=${d.verifiedScore ?? 'n/a'}`);
    const pr = d.scmPrUrl ?? d.githubPrUrl;
    if (pr) {
      const pick = await vscode.window.showInformationMessage(`Run ${d.status}.`, 'Open PR');
      if (pick === 'Open PR') void vscode.env.openExternal(vscode.Uri.parse(pr));
    }
  } catch {
    // best-effort final fetch
  }
}

async function handleGate(
  client: ClientBundle,
  runId: string,
  ev: RunEvent,
  output: vscode.OutputChannel,
): Promise<void> {
  // payload: "PLAN" or "PLAN|<credits>|<usd>"
  const parts = (ev.payload ?? '').split('|');
  const credits = parts[1];
  const usd = parts[2];
  const detail =
    `Approve plan?${credits ? ` ${credits} credit(s)` : ''}` + `${usd ? ` ~$${usd}` : ''}`;
  const choice = await vscode.window.showInformationMessage(
    detail,
    { modal: true },
    'Approve',
    'Reject',
  );
  if (choice === 'Approve') {
    const body: ApprovalRequest = {
      approved: true,
      creditsConfirmed: credits ? Number(credits) : null,
      executionMode: 'REALTIME',
    };
    await client.api.approve(runId, body);
    output.appendLine('Approved.');
  } else if (choice === 'Reject') {
    await client.api.approve(runId, { approved: false });
    output.appendLine('Rejected.');
  }
  // Dismissed → leave the gate open; the user can approve later from the Runs view.
}

async function getSpec(): Promise<string | undefined> {
  const editor = vscode.window.activeTextEditor;
  const selection =
    editor && !editor.selection.isEmpty ? editor.document.getText(editor.selection) : '';
  if (selection.trim()) return selection.trim();
  return vscode.window.showInputBox({
    prompt: 'Describe what to build (the spec)',
    ignoreFocusOut: true,
  });
}
