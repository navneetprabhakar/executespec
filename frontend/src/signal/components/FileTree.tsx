import {useMemo, useState} from 'react';

/**
 * Folder-tree file browser. Replaces the flat list that used to sit in the
 * Result page Code tab — that version showed a long ladder of monospaced
 * paths and gave the user no sense of project structure. This component:
 *
 *   1. Groups files into directories (collapsible nodes).
 *   2. Surfaces a per-file-type icon so the user spots `pom.xml` / `package.json`
 *      / source vs config / test files at a glance.
 *   3. Keeps the sidebar narrow (no monospace paths bleeding into the right
 *      pane); the full path is shown on hover as a `title` attribute.
 *   4. Auto-expands all folders on first render so users don't have to drill
 *      into a 3-level tree just to find the one Java file the run touched.
 *
 * Renders inside the existing `.signal-split-runs > .split-list` grid cell;
 * no new layout rule needed.
 */

export interface FileEntry {
  path: string;
  status?: 'ADDED' | 'MODIFIED' | 'DELETED' | string;
}

interface DirNode {
  name: string;
  fullPath: string;
  children: Array<DirNode | FileEntry>;
  isDir: true;
}

function isDir(n: DirNode | FileEntry): n is DirNode {
  return (n as DirNode).isDir === true;
}

/** Group a flat list of file paths into a nested directory structure. */
function buildTree(files: FileEntry[]): DirNode {
  const root: DirNode = {name: '', fullPath: '', children: [], isDir: true};
  // Sort so deterministic + folders-before-files ordering renders right.
  const sorted = [...files].sort((a, b) => a.path.localeCompare(b.path));
  for (const f of sorted) {
    const segs = f.path.split('/').filter(Boolean);
    let cursor: DirNode = root;
    for (let i = 0; i < segs.length - 1; i += 1) {
      const part = segs[i];
      const existing = cursor.children.find((c): c is DirNode => isDir(c) && c.name === part);
      if (existing) {
        cursor = existing;
      } else {
        const next: DirNode = {
          name: part,
          fullPath: segs.slice(0, i + 1).join('/'),
          children: [],
          isDir: true,
        };
        cursor.children.push(next);
        cursor = next;
      }
    }
    cursor.children.push(f);
  }
  // Sort children: dirs first, then files alphabetically. Already alpha
  // from the sorted source so we just need to partition.
  const partition = (n: DirNode): void => {
    const dirs = n.children.filter(isDir);
    const filz = n.children.filter((c) => !isDir(c));
    n.children = [...dirs, ...filz];
    dirs.forEach(partition);
  };
  partition(root);
  return root;
}

/** Pick an emoji-ish glyph by file extension. Kept light — one chevron per
 *  ext, no Material/Lucide chunk pulled in. */
function iconFor(filename: string): string {
  const lower = filename.toLowerCase();
  if (lower.endsWith('.md') || lower === 'readme.md' || lower === 'readme') return '📄';
  if (lower.endsWith('.json')) return '⚙';
  if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return '⚙';
  if (lower.endsWith('.xml')) return '⚙';
  if (lower.endsWith('.toml')) return '⚙';
  if (lower === 'dockerfile' || lower.endsWith('.dockerfile')) return '🐳';
  if (lower === '.gitignore' || lower === '.gitattributes' || lower === '.dockerignore') return '⊘';
  if (lower.endsWith('.test.ts') || lower.endsWith('.test.tsx') || lower.endsWith('.test.js'))
    return '✓';
  if (lower.endsWith('test.java') || lower.endsWith('tests.java')) return '✓';
  if (lower.endsWith('.spec.ts') || lower.endsWith('.spec.js')) return '✓';
  if (lower.endsWith('.java')) return '☕';
  if (lower.endsWith('.kt') || lower.endsWith('.kts')) return '◇';
  if (lower.endsWith('.py')) return '🐍';
  if (lower.endsWith('.ts') || lower.endsWith('.tsx')) return '◷';
  if (lower.endsWith('.js') || lower.endsWith('.jsx') || lower.endsWith('.mjs')) return '◷';
  if (lower.endsWith('.go')) return '◷';
  if (lower.endsWith('.rs')) return '◷';
  if (lower.endsWith('.rb')) return '◷';
  if (lower.endsWith('.css') || lower.endsWith('.scss') || lower.endsWith('.less')) return '◧';
  if (lower.endsWith('.html') || lower.endsWith('.htm')) return '◧';
  if (
    lower.endsWith('.svg') ||
    lower.endsWith('.png') ||
    lower.endsWith('.jpg') ||
    lower.endsWith('.jpeg') ||
    lower.endsWith('.gif')
  )
    return '◨';
  if (lower.endsWith('.sh') || lower.endsWith('.bash')) return '$';
  if (lower.endsWith('.sql')) return '⊟';
  return '·';
}

/** Status dot colour matches the previous flat-list design. */
function statusDotKind(status?: string): 'mint' | 'signal' | 'coral' | 'lavender' {
  const s = (status || 'MODIFIED').toUpperCase();
  if (s === 'ADDED') return 'mint';
  if (s === 'MODIFIED') return 'signal';
  if (s === 'DELETED') return 'coral';
  return 'lavender';
}

interface FileTreeProps {
  files: FileEntry[];
  selectedPath?: string | null;
  onSelect: (path: string) => void;
}

export function FileTree({files, selectedPath, onSelect}: FileTreeProps) {
  const tree = useMemo(() => buildTree(files), [files]);
  // Track collapsed-by-default; we start every folder open so the user sees
  // the whole structure on entry — small repos benefit and large repos can
  // collapse what they don't need.
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  function toggle(path: string) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }

  function renderNode(node: DirNode | FileEntry, depth: number): React.ReactNode {
    const pad = 6 + depth * 12;
    if (isDir(node)) {
      const isCollapsed = collapsed.has(node.fullPath);
      return (
        <div key={`d:${node.fullPath}`}>
          <div
            className='signal-file-tree-node signal-file-tree-dir'
            style={{paddingLeft: pad}}
            onClick={() => toggle(node.fullPath)}
            role='button'
            tabIndex={0}
            aria-expanded={!isCollapsed}
            title={node.fullPath}
          >
            <span className='signal-file-tree-caret' aria-hidden='true'>
              {isCollapsed ? '▸' : '▾'}
            </span>
            <span className='signal-file-tree-folder-icon' aria-hidden='true'>
              📁
            </span>
            <span className='signal-file-tree-name'>{node.name}</span>
            <span className='signal-file-tree-count' aria-hidden='true'>
              {countFiles(node)}
            </span>
          </div>
          {!isCollapsed && node.children.map((c) => renderNode(c, depth + 1))}
        </div>
      );
    }
    const f = node;
    const filename = f.path.split('/').pop() || f.path;
    const selected = selectedPath === f.path;
    return (
      <div
        key={`f:${f.path}`}
        className={`signal-file-tree-node signal-file-tree-file${selected ? ' is-selected' : ''}`}
        style={{paddingLeft: pad}}
        onClick={() => onSelect(f.path)}
        role='button'
        tabIndex={0}
        aria-current={selected ? 'true' : undefined}
        title={f.path}
      >
        <span className='signal-file-tree-caret' aria-hidden='true' />
        <span className='signal-file-tree-file-icon' aria-hidden='true'>
          {iconFor(filename)}
        </span>
        <span className='signal-file-tree-name'>{filename}</span>
        <span
          className={`signal-file-tree-status ${statusDotKind(f.status)}`}
          aria-label={f.status?.toLowerCase()}
        />
      </div>
    );
  }

  function countFiles(node: DirNode): number {
    let total = 0;
    for (const c of node.children) {
      if (isDir(c)) total += countFiles(c);
      else total += 1;
    }
    return total;
  }

  if (files.length === 0) {
    return (
      <div style={{fontSize: 13, color: 'var(--signal-text-dim)', padding: 12}}>
        No files touched by this run.
      </div>
    );
  }

  return (
    <div className='signal-file-tree' role='tree' aria-label='Files touched by this run'>
      {tree.children.map((c) => renderNode(c, 0))}
    </div>
  );
}
