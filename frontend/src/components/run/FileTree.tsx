import { useState, useMemo } from 'react';

interface FileEntry {
  path: string;
  isDirectory: boolean;
  size?: number;
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
}

export function FileTree({ files, onSelectFile, selectedFile }: FileTreeProps) {
  const tree = useMemo(() => buildTree(files), [files]);

  return (
    <div className="text-sm font-mono">
      {tree.map(node => (
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
  node, depth, onSelectFile, selectedFile
}: {
  node: TreeNode; depth: number; onSelectFile: (path: string) => void; selectedFile?: string;
}) {
  const [expanded, setExpanded] = useState(depth < 2); // auto-expand first 2 levels

  const icon = node.isDirectory
    ? (expanded ? '\u{1F4C2}' : '\u{1F4C1}')
    : getFileIcon(node.name);

  return (
    <div>
      <div
        className={`flex items-center gap-1.5 py-0.5 px-2 cursor-pointer rounded hover:bg-white/[0.06]
          ${selectedFile === node.path ? 'bg-accent-violet/10 text-accent-violet' : 'text-zinc-400'}`}
        style={{ paddingLeft: `${depth * 16 + 8}px` }}
        onClick={() => {
          if (node.isDirectory) {
            setExpanded(!expanded);
          } else {
            onSelectFile(node.path);
          }
        }}
      >
        <span className="text-xs">{icon}</span>
        <span className="truncate">{node.name}</span>
      </div>
      {node.isDirectory && expanded && node.children.map(child => (
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

      let existing = current.find(n => n.name === name);
      if (!existing) {
        existing = {
          name,
          path,
          isDirectory: isLast ? file.isDirectory : true,
          children: [],
          size: isLast ? file.size : undefined,
        };
        current.push(existing);
      }
      current = existing.children;
    }
  }

  return root;
}

function getFileIcon(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase();
  const icons: Record<string, string> = {
    java: '\u2615', kt: '\u{1F7E3}', py: '\u{1F40D}', js: '\u{1F7E8}', jsx: '\u269B\uFE0F', ts: '\u{1F537}', tsx: '\u269B\uFE0F',
    html: '\u{1F310}', css: '\u{1F3A8}', json: '\u{1F4CB}', yml: '\u2699\uFE0F', yaml: '\u2699\uFE0F', xml: '\u{1F4C4}',
    md: '\u{1F4DD}', sql: '\u{1F5C4}\uFE0F', sh: '\u{1F5A5}\uFE0F', dockerfile: '\u{1F433}', toml: '\u2699\uFE0F',
    rs: '\u{1F980}', go: '\u{1F439}', rb: '\u{1F48E}', gradle: '\u{1F418}',
  };
  return icons[ext || ''] || '\u{1F4C4}';
}
