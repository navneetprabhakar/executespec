import {useState, useMemo} from 'react';

/**
 * `status` (V169-companion 2026-05-02) — git-derived per-file change marker so the file tree
 * can show at a glance which paths the worker added vs modified. Multi-repo aware: backend
 * runs `git status --porcelain` per `repos/<name>/` subdir. Optional so legacy callers passing
 * paths without status keep rendering as "unchanged".
 */
type FileChangeStatus = 'ADDED' | 'MODIFIED' | 'UNCHANGED';

interface FileEntry {
  path: string;
  isDirectory: boolean;
  size?: number;
  status?: FileChangeStatus;
}

interface FileTreeProps {
  files: FileEntry[];
  onSelectFile: (path: string) => void;
  selectedFile?: string;
}

interface TreeNode {
  name: string;
  path: string;
  isDirectory: boolean;
  children: TreeNode[];
  size?: number;
  status?: FileChangeStatus;
  /**
   * Aggregated child status — ADDED if any descendant is ADDED, else MODIFIED if any descendant
   * is MODIFIED, else undefined. Lets a directory node surface a small dot when any of its
   * subtree changed without having to expand it.
   */
  childChangedStatus?: FileChangeStatus;
}

export function FileTree({files, onSelectFile, selectedFile}: FileTreeProps) {
  const tree = useMemo(() => buildTree(files), [files]);

  return (
    <div className='text-sm font-mono'>
      {tree.map((node) => (
        <TreeNodeView
          key={node.path}
          node={node}
          depth={0}
          onSelectFile={onSelectFile}
          selectedFile={selectedFile}
        />
      ))}
    </div>
  );
}

function TreeNodeView({
  node,
  depth,
  onSelectFile,
  selectedFile,
}: {
  node: TreeNode;
  depth: number;
  onSelectFile: (path: string) => void;
  selectedFile?: string;
}) {
  const [expanded, setExpanded] = useState(depth < 2); // auto-expand first 2 levels

  const icon = node.isDirectory ? (expanded ? '\u{1F4C2}' : '\u{1F4C1}') : getFileIcon(node.name);

  // V169-companion 2026-05-02 — leaf nodes show an A/M chip for added/modified files.
  // Directory nodes show a small colored dot when any descendant has changed (so the user can
  // see deltas without expanding).
  const leafBadge = !node.isDirectory ? renderStatusBadge(node.status) : null;
  const dirDot =
    node.isDirectory && node.childChangedStatus ? renderStatusDot(node.childChangedStatus) : null;

  return (
    <div>
      <div
        className={`flex items-center gap-1.5 py-0.5 px-2 cursor-pointer rounded hover:bg-[var(--gray-200)]
          ${selectedFile === node.path ? 'bg-accent-blue/10 text-accent-blue' : 'text-secondary'}`}
        style={{paddingLeft: `${depth * 16 + 8}px`}}
        onClick={() => {
          if (node.isDirectory) {
            setExpanded(!expanded);
          } else {
            onSelectFile(node.path);
          }
        }}
      >
        <span className='text-xs'>{icon}</span>
        <span className='truncate flex-1'>{node.name}</span>
        {dirDot}
        {leafBadge}
      </div>
      {node.isDirectory &&
        expanded &&
        node.children.map((child) => (
          <TreeNodeView
            key={child.path}
            node={child}
            depth={depth + 1}
            onSelectFile={onSelectFile}
            selectedFile={selectedFile}
          />
        ))}
    </div>
  );
}

function buildTree(files: FileEntry[]): TreeNode[] {
  const root: TreeNode[] = [];

  const sorted = [...files].sort((a, b) => {
    if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
    return a.path.localeCompare(b.path);
  });

  for (const file of sorted) {
    const parts = file.path.split('/').filter(Boolean);
    let current = root;

    for (let i = 0; i < parts.length; i++) {
      const name = parts[i];
      const path = parts.slice(0, i + 1).join('/');
      const isLast = i === parts.length - 1;

      let existing = current.find((n) => n.name === name);
      if (!existing) {
        existing = {
          name,
          path,
          isDirectory: isLast ? file.isDirectory : true,
          children: [],
          size: isLast ? file.size : undefined,
          status: isLast ? file.status : undefined,
        };
        current.push(existing);
      }
      current = existing.children;
    }
  }

  // Bubble leaf statuses up to ancestor directories so collapsed dirs surface a dot when
  // anything inside them changed. ADDED dominates MODIFIED — if a dir has both, we show ADDED.
  function aggregate(nodes: TreeNode[]): FileChangeStatus | undefined {
    let agg: FileChangeStatus | undefined;
    for (const n of nodes) {
      const childAgg = n.isDirectory ? aggregate(n.children) : n.status;
      if (n.isDirectory) n.childChangedStatus = childAgg;
      if (childAgg === 'ADDED') agg = 'ADDED';
      else if (childAgg === 'MODIFIED' && agg !== 'ADDED') agg = 'MODIFIED';
    }
    return agg;
  }
  aggregate(root);

  return root;
}

/** Inline pill on leaf rows: green "A" for added, amber "M" for modified, nothing for unchanged. */
function renderStatusBadge(status: FileChangeStatus | undefined) {
  if (!status || status === 'UNCHANGED') return null;
  const base = 'text-[9px] font-bold leading-none px-1 py-[1px] rounded-sm flex-shrink-0 border';
  if (status === 'ADDED') {
    return (
      <span
        className={`${base} text-status-success border-status-success/40 bg-status-success/10`}
        title='Added by this run'
      >
        A
      </span>
    );
  }
  return (
    <span
      className={`${base} text-accent-amber border-accent-amber/40 bg-accent-amber/10`}
      title='Modified by this run'
    >
      M
    </span>
  );
}

/** Tiny colored dot on collapsed directory rows so the user can see deltas without expanding. */
function renderStatusDot(status: FileChangeStatus) {
  const cls =
    status === 'ADDED'
      ? 'bg-status-success'
      : status === 'MODIFIED'
        ? 'bg-accent-amber'
        : 'bg-muted';
  return (
    <span
      className={`inline-block h-1.5 w-1.5 rounded-full flex-shrink-0 ${cls}`}
      title={
        status === 'ADDED'
          ? 'Contains files added by this run'
          : 'Contains files modified by this run'
      }
    />
  );
}

function getFileIcon(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase();
  const icons: Record<string, string> = {
    java: '\u2615',
    kt: '\u{1F7E3}',
    py: '\u{1F40D}',
    js: '\u{1F7E8}',
    jsx: '\u269B\uFE0F',
    ts: '\u{1F537}',
    tsx: '\u269B\uFE0F',
    html: '\u{1F310}',
    css: '\u{1F3A8}',
    json: '\u{1F4CB}',
    yml: '\u2699\uFE0F',
    yaml: '\u2699\uFE0F',
    xml: '\u{1F4C4}',
    md: '\u{1F4DD}',
    sql: '\u{1F5C4}\uFE0F',
    sh: '\u{1F5A5}\uFE0F',
    dockerfile: '\u{1F433}',
    toml: '\u2699\uFE0F',
    rs: '\u{1F980}',
    go: '\u{1F439}',
    rb: '\u{1F48E}',
    gradle: '\u{1F418}',
  };
  return icons[ext || ''] || '\u{1F4C4}';
}
