import * as vscode from 'vscode';
import type { ClientBundle, RunSummary } from '@executespec/core';

/** Sidebar list of the signed-in user's runs. Click a run to watch it. */
export class RunsTreeProvider implements vscode.TreeDataProvider<RunNode> {
  private readonly emitter = new vscode.EventEmitter<RunNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  constructor(private readonly clientFactory: () => ClientBundle) {}

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: RunNode): vscode.TreeItem {
    return element;
  }

  async getChildren(): Promise<RunNode[]> {
    const client = this.clientFactory();
    if (!(await client.tokens.get())) {
      const node = new RunNode('Sign in to ExecuteSpec…');
      node.command = { command: 'executespec.signIn', title: 'Sign In' };
      return [node];
    }
    try {
      const page = await client.api.listRuns({ scope: 'mine', size: 30 });
      if (page.content.length === 0) return [new RunNode('No runs yet — Submit a Spec')];
      return page.content.map((r) => RunNode.fromRun(r));
    } catch {
      return [new RunNode('Failed to load runs (check host / token)')];
    }
  }
}

class RunNode extends vscode.TreeItem {
  runId?: string;

  constructor(label: string) {
    super(label, vscode.TreeItemCollapsibleState.None);
  }

  static fromRun(r: RunSummary): RunNode {
    const spec = (r.userRequest ?? '').replace(/\s+/g, ' ').slice(0, 60);
    const node = new RunNode(spec || r.runId);
    node.runId = r.runId;
    node.description = r.status;
    node.tooltip = `${r.runId}\n${r.status}`;
    node.contextValue = 'executespecRun';
    node.command = {
      command: 'executespec.watchRun',
      title: 'Watch Run',
      arguments: [{ runId: r.runId }],
    };
    return node;
  }
}
