import { useMemo } from 'react';
import { cn } from '@/lib/utils';

/**
 * Renders a unified-diff string (from `git diff` / GitHub's patch API) with
 * GitHub-style colouring: green-tinted add lines, red-tinted delete lines,
 * file headers as soft section breaks, hunk headers as muted separators.
 *
 * Deliberately minimal — no syntax highlighting inside additions/deletions,
 * just background colour + a gutter symbol. Fits the rest of the project-view
 * aesthetic (compact, dark-themed, Tailwind tokens).
 */
export default function DiffView({
  diff,
  maxHeightClass = 'max-h-80',
}: {
  diff: string;
  maxHeightClass?: string;
}) {
  const blocks = useMemo(() => parseDiff(diff), [diff]);

  if (blocks.length === 0) {
    return <div className="text-[11px] text-muted">No diff content.</div>;
  }

  return (
    <div className={cn(
      'rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] overflow-auto font-mono text-[11px] leading-snug',
      maxHeightClass,
    )}>
      {blocks.map((b, i) => (
        <FileBlock key={i} block={b} />
      ))}
    </div>
  );
}

function FileBlock({ block }: { block: FileBlockT }) {
  return (
    <div className="border-b border-[var(--border-default)] last:border-b-0">
      <div className="sticky top-0 z-[1] flex items-center gap-2 bg-[var(--gray-300)] px-2 py-1 text-[10px] border-b border-[var(--border-default)]">
        <span className="text-muted uppercase tracking-wide">{block.fileStatus}</span>
        <span className="text-secondary truncate">{block.filePath}</span>
        {(block.additions > 0 || block.deletions > 0) && (
          <span className="ml-auto text-[10px] font-mono flex items-center gap-2">
            {block.additions > 0 && <span className="text-status-success">+{block.additions}</span>}
            {block.deletions > 0 && <span className="text-accent-red">−{block.deletions}</span>}
          </span>
        )}
      </div>
      {block.hunks.map((h, hi) => (
        <div key={hi}>
          <div className="bg-[var(--gray-100)] px-2 py-0.5 text-[10px] text-muted select-none">
            {h.header}
          </div>
          <table className="w-full border-collapse">
            <tbody>
              {h.lines.map((line, li) => <DiffLine key={li} line={line} />)}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  );
}

function DiffLine({ line }: { line: Line }) {
  const { kind, oldNo, newNo, content } = line;
  const rowBg =
    kind === 'add' ? 'bg-status-success/10'
    : kind === 'del' ? 'bg-accent-red/10'
    : '';
  const marker =
    kind === 'add' ? <span className="text-status-success">+</span>
    : kind === 'del' ? <span className="text-accent-red">−</span>
    : <span className="text-muted"> </span>;
  const textColor =
    kind === 'add' ? 'text-primary'
    : kind === 'del' ? 'text-primary'
    : 'text-secondary';
  return (
    <tr className={rowBg}>
      <td className="w-9 px-1.5 text-right text-[10px] text-muted tabular-nums select-none border-r border-[var(--border-default)]/50">
        {oldNo ?? ''}
      </td>
      <td className="w-9 px-1.5 text-right text-[10px] text-muted tabular-nums select-none border-r border-[var(--border-default)]/50">
        {newNo ?? ''}
      </td>
      <td className="w-4 text-center select-none">{marker}</td>
      <td className={cn('pr-2 whitespace-pre', textColor)}>{content}</td>
    </tr>
  );
}

// ── Parsing ────────────────────────────────────────────────────────────────

type LineKind = 'add' | 'del' | 'ctx';
type Line = {
  kind: LineKind;
  oldNo: number | null;
  newNo: number | null;
  content: string;
};
type Hunk = { header: string; lines: Line[] };
type FileBlockT = {
  filePath: string;
  fileStatus: string; // ADDED | DELETED | MODIFIED | RENAMED
  additions: number;
  deletions: number;
  hunks: Hunk[];
};

function parseDiff(raw: string): FileBlockT[] {
  const out: FileBlockT[] = [];
  if (!raw) return out;
  const lines = raw.split('\n');
  let i = 0;
  let current: FileBlockT | null = null;
  let oldNo = 0;
  let newNo = 0;

  const pushCurrent = () => {
    if (current) out.push(current);
  };

  while (i < lines.length) {
    const line = lines[i];

    if (line.startsWith('diff --git ')) {
      pushCurrent();
      current = {
        filePath: extractFilePath(line) ?? 'unknown',
        fileStatus: 'MODIFIED',
        additions: 0,
        deletions: 0,
        hunks: [],
      };
      i++;
      continue;
    }

    if (!current) { i++; continue; }

    if (line.startsWith('new file mode')) { current.fileStatus = 'ADDED'; i++; continue; }
    if (line.startsWith('deleted file mode')) { current.fileStatus = 'DELETED'; i++; continue; }
    if (line.startsWith('rename from') || line.startsWith('rename to')) { current.fileStatus = 'RENAMED'; i++; continue; }
    if (line.startsWith('index ') || line.startsWith('--- ') || line.startsWith('+++ ') || line.startsWith('Binary files')) { i++; continue; }

    if (line.startsWith('@@')) {
      const hunk: Hunk = { header: line, lines: [] };
      const m = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
      oldNo = m ? parseInt(m[1], 10) : 0;
      newNo = m ? parseInt(m[2], 10) : 0;
      i++;
      while (i < lines.length && !lines[i].startsWith('diff --git ') && !lines[i].startsWith('@@')) {
        const l = lines[i];
        if (l.startsWith('\\ No newline at end of file')) { i++; continue; }
        if (l.startsWith('+')) {
          hunk.lines.push({ kind: 'add', oldNo: null, newNo, content: l.slice(1) });
          newNo++;
          current.additions++;
        } else if (l.startsWith('-')) {
          hunk.lines.push({ kind: 'del', oldNo, newNo: null, content: l.slice(1) });
          oldNo++;
          current.deletions++;
        } else {
          const stripped = l.startsWith(' ') ? l.slice(1) : l;
          hunk.lines.push({ kind: 'ctx', oldNo, newNo, content: stripped });
          oldNo++;
          newNo++;
        }
        i++;
      }
      current.hunks.push(hunk);
      continue;
    }
    i++;
  }
  pushCurrent();
  return out;
}

function extractFilePath(header: string): string | null {
  // `diff --git a/path/to/file b/path/to/file`
  const m = /^diff --git a\/(.+?) b\/(.+?)$/.exec(header);
  if (!m) return null;
  return m[2] !== m[1] ? `${m[1]} → ${m[2]}` : m[1];
}
