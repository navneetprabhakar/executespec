import {useState, useMemo} from 'react';
import {
  Folder,
  FolderOpen,
  FileText,
  FileCode,
  FileJson,
  FileCog,
  FileType2,
  FileTerminal,
  FileImage,
  Database,
  Container,
  Globe,
  Palette,
  type LucideIcon,
} from 'lucide-react';

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

  const fileGlyph = node.isDirectory
    ? {Icon: expanded ? FolderOpen : Folder, color: 'var(--accent-blue, #5B8DEF)'}
    : getFileIcon(node.name);
  const FileIconCmp = fileGlyph.Icon;

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
        <FileIconCmp
          size={14}
          strokeWidth={1.75}
          style={{color: fileGlyph.color, flexShrink: 0}}
          aria-hidden='true'
        />
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

/**
 * R-04 fix (2026-05-09) \u2014 replaced the emoji map with lucide-react icons
 * tinted by extension. The previous emojis collided ('\uD83D\uDFE1 kt' / '\uD83D\uDFE8 js'
 * were colour-only with no language association; jsx + tsx both rendered
 * '\u269B\uFE0F'; yml/toml/sh all became '\u2699\uFE0F'). The new map uses semantic icons
 * (FileCode for source, FileJson for data, FileTerminal for shell, etc.)
 * with per-language colour tints so files are skimmable at a glance.
 */
type FileGlyph = {Icon: LucideIcon; color: string};

// Tailwind-friendly raw hex tints. Picked to read on both dark and light
// themes and to match what most editors / GitHub render.
const TINT = {
  ts: '#3178C6', // TypeScript blue
  js: '#F7DF1E', // JS yellow
  py: '#3776AB', // Python blue
  java: '#B07219',
  kt: '#A97BFF', // Kotlin purple
  rs: '#D2691E', // Rust orange
  go: '#00ADD8', // Go cyan
  rb: '#CC342D', // Ruby red
  html: '#E34C26',
  css: '#264DE4',
  json: '#A0AEC0',
  config: '#94A3B8', // YAML / TOML / INI
  shell: '#4EAA25',
  sql: '#9333EA',
  docker: '#0DB7ED',
  md: '#6B7280',
  img: '#10B981',
  fallback: 'var(--v3-text-subtle, #94A3B8)',
} as const;

function getFileIcon(name: string): FileGlyph {
  const lower = name.toLowerCase();
  if (lower === 'dockerfile' || lower.endsWith('.dockerfile')) {
    return {Icon: Container, color: TINT.docker};
  }
  const ext = lower.split('.').pop() ?? '';
  switch (ext) {
    case 'ts':
    case 'tsx':
    case 'mts':
    case 'cts':
      return {Icon: FileCode, color: TINT.ts};
    case 'js':
    case 'jsx':
    case 'mjs':
    case 'cjs':
      return {Icon: FileCode, color: TINT.js};
    case 'py':
      return {Icon: FileCode, color: TINT.py};
    case 'java':
      return {Icon: FileCode, color: TINT.java};
    case 'kt':
    case 'kts':
      return {Icon: FileCode, color: TINT.kt};
    case 'rs':
      return {Icon: FileCode, color: TINT.rs};
    case 'go':
      return {Icon: FileCode, color: TINT.go};
    case 'rb':
      return {Icon: FileCode, color: TINT.rb};
    case 'html':
    case 'htm':
      return {Icon: Globe, color: TINT.html};
    case 'css':
    case 'scss':
    case 'sass':
    case 'less':
      return {Icon: Palette, color: TINT.css};
    case 'json':
    case 'jsonc':
      return {Icon: FileJson, color: TINT.json};
    case 'yml':
    case 'yaml':
    case 'toml':
    case 'ini':
    case 'env':
    case 'properties':
      return {Icon: FileCog, color: TINT.config};
    case 'xml':
      return {Icon: FileType2, color: TINT.config};
    case 'md':
    case 'markdown':
    case 'mdx':
    case 'rst':
    case 'txt':
      return {Icon: FileText, color: TINT.md};
    case 'sql':
      return {Icon: Database, color: TINT.sql};
    case 'sh':
    case 'bash':
    case 'zsh':
    case 'fish':
    case 'ps1':
      return {Icon: FileTerminal, color: TINT.shell};
    case 'gradle':
    case 'kts': // already covered above; left for readability
      return {Icon: FileCog, color: TINT.config};
    case 'png':
    case 'jpg':
    case 'jpeg':
    case 'gif':
    case 'svg':
    case 'webp':
    case 'ico':
      return {Icon: FileImage, color: TINT.img};
    default:
      return {Icon: FileText, color: TINT.fallback};
  }
}
