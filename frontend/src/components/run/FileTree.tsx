import {useState, useMemo} from 'react';
import type {LucideIcon} from 'lucide-react';
import {
  Folder,
  FolderOpen,
  FileCode2,
  FileText,
  FileJson,
  FileType,
  Database,
  Terminal,
  Container,
  Settings,
  Image as ImageIcon,
  Lock,
  GitBranch,
  Package,
  File,
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

  const {Icon, colorClass} = node.isDirectory
    ? expanded
      ? {Icon: FolderOpen, colorClass: 'text-accent-blue/80'}
      : {Icon: Folder, colorClass: 'text-muted'}
    : getFileIcon(node.name);

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
        <Icon className={`h-3.5 w-3.5 shrink-0 ${colorClass}`} aria-hidden='true' />
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
 * Lucide-icon mapping per file extension. Returns a (component, colour-class) pair so
 * the tree can render a uniformly-sized 14px SVG with a semantic tint.
 *
 * Pre-fix this returned emoji codepoints (\u2615 for Java, \uD83D\uDFE3 for Kotlin, \uD83D\uDC39 for Go, \uD83D\uDC8E for
 * Ruby, \uD83D\uDC33 for Docker, etc). Three problems with that:
 *   1. Emoji renders OS-dependent \u2014 different size, baseline, and colour on macOS / Windows
 *      / Linux / Android, so the tree looked uneven.
 *   2. Several picks were obscure (purple circle for Kotlin? hamster for Go?) and required
 *      memorisation.
 *   3. It clashed with the rest of the UI which uses Lucide line-icons throughout.
 *
 * The replacement uses a small palette of Lucide icons (FileCode2 for source, FileText for
 * docs, FileJson for structured config, Settings for *.toml/*.ini/*.env/*.yml, Database for
 * SQL, Terminal for shell, Container for Dockerfile, Package for lockfiles, Lock for
 * key/secret-suffixed paths, Image for binary assets, GitBranch for VCS metadata) with a
 * tint class so green=stylesheet, blue=script-source, amber=config, gray=docs/unknown.
 * Same icon shape for the common cases (every code file is FileCode2) keeps scan-ability
 * high while colour gives the at-a-glance language hint.
 */
function getFileIcon(name: string): {Icon: LucideIcon; colorClass: string} {
  const lower = name.toLowerCase();
  // Filename-based matches before extension-based ones (Dockerfile, Makefile, lockfiles etc).
  if (lower === 'dockerfile' || lower.endsWith('.dockerfile') || lower.startsWith('dockerfile.')) {
    return {Icon: Container, colorClass: 'text-accent-blue/80'};
  }
  if (lower === 'makefile') return {Icon: Settings, colorClass: 'text-accent-amber'};
  if (lower === 'package-lock.json' || lower === 'yarn.lock' || lower === 'pnpm-lock.yaml')
    return {Icon: Package, colorClass: 'text-muted'};
  if (lower === '.gitignore' || lower === '.gitattributes' || lower === '.gitmodules')
    return {Icon: GitBranch, colorClass: 'text-muted'};
  if (lower === '.env' || lower.startsWith('.env.'))
    return {Icon: Lock, colorClass: 'text-accent-amber'};

  const ext = lower.split('.').pop() ?? '';
  switch (ext) {
    // Code (uniform: FileCode2). Tinted by language family so the colour does the
    // mnemonic work without needing ten different icon shapes.
    case 'ts':
    case 'tsx':
    case 'js':
    case 'jsx':
    case 'mjs':
    case 'cjs':
      return {Icon: FileCode2, colorClass: 'text-accent-blue'};
    case 'java':
    case 'kt':
    case 'kts':
    case 'scala':
    case 'groovy':
    case 'gradle':
      return {Icon: FileCode2, colorClass: 'text-accent-amber'};
    case 'py':
    case 'pyi':
    case 'rb':
    case 'php':
      return {Icon: FileCode2, colorClass: 'text-status-success'};
    case 'go':
    case 'rs':
    case 'c':
    case 'cpp':
    case 'cxx':
    case 'h':
    case 'hpp':
    case 'cs':
    case 'swift':
    case 'm':
    case 'mm':
      return {Icon: FileCode2, colorClass: 'text-status-thinking'};
    // Markup / styles
    case 'html':
    case 'htm':
    case 'xml':
    case 'svg':
    case 'vue':
    case 'svelte':
      return {Icon: FileType, colorClass: 'text-accent-amber'};
    case 'css':
    case 'scss':
    case 'sass':
    case 'less':
    case 'styl':
      return {Icon: FileType, colorClass: 'text-status-success'};
    // Structured config
    case 'json':
    case 'json5':
      return {Icon: FileJson, colorClass: 'text-accent-amber'};
    case 'yml':
    case 'yaml':
    case 'toml':
    case 'ini':
    case 'conf':
    case 'cfg':
    case 'properties':
      return {Icon: Settings, colorClass: 'text-muted'};
    // Docs
    case 'md':
    case 'mdx':
    case 'rst':
    case 'txt':
    case 'adoc':
      return {Icon: FileText, colorClass: 'text-secondary'};
    // Data
    case 'sql':
    case 'csv':
    case 'tsv':
    case 'parquet':
    case 'db':
    case 'sqlite':
      return {Icon: Database, colorClass: 'text-accent-blue/70'};
    // Shell
    case 'sh':
    case 'bash':
    case 'zsh':
    case 'fish':
    case 'ps1':
      return {Icon: Terminal, colorClass: 'text-status-success'};
    // Binary assets
    case 'png':
    case 'jpg':
    case 'jpeg':
    case 'gif':
    case 'webp':
    case 'ico':
    case 'avif':
      return {Icon: ImageIcon, colorClass: 'text-muted'};
    // Secrets
    case 'pem':
    case 'key':
    case 'crt':
    case 'cert':
      return {Icon: Lock, colorClass: 'text-accent-amber'};
    // Default
    default:
      return {Icon: File, colorClass: 'text-muted'};
  }
}
