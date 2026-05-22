import {useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {fileIconFor, FolderClosedIcon, FolderOpenIcon} from '@/ide/IdeIcons';

// ───────── status helpers ─────────
export function statusKind(status?: string | null): 'ok' | 'warn' | 'bad' {
  const s = (status || '').toUpperCase();
  if (s === 'COMPLETE' || s === 'COMPLETED' || s === 'MERGED') return 'ok';
  if (s === 'FAILED' || s === 'ERROR' || s === 'CANCELLED') return 'bad';
  return 'warn';
}

export function IdeBadge({
  kind,
  children,
}: {
  kind: 'ok' | 'warn' | 'bad';
  children: React.ReactNode;
}) {
  return <span className={`ide-badge ${kind}`}>{children}</span>;
}

// ───────── lightweight syntax tokenizer ─────────
type Seg = {t: 'plain' | 'kw' | 'str' | 'com' | 'num'; v: string};

const KEYWORDS = new Set(
  (
    'const let var function return if else for while do switch case break continue class extends ' +
    'import export from default new this typeof instanceof void await async yield try catch finally throw ' +
    'public private protected static final void int long double boolean String interface enum package ' +
    'def elif lambda None True False and or not in is with as pass raise type struct func go fn impl pub use mut'
  ).split(' '),
);

// Tokenise a single line into typed segments. Order matters: comments + strings
// win over keyword/number scanning so we don't mis-highlight inside them.
function tokenizeLine(line: string): Seg[] {
  const segs: Seg[] = [];
  let i = 0;
  const n = line.length;
  let buf = '';
  const flush = () => {
    if (!buf) return;
    // keyword / number scan over the plain buffer
    const parts = buf.split(/(\b)/);
    for (const p of parts) {
      if (KEYWORDS.has(p)) segs.push({t: 'kw', v: p});
      else if (/^\d[\d_.]*$/.test(p)) segs.push({t: 'num', v: p});
      else if (p) segs.push({t: 'plain', v: p});
    }
    buf = '';
  };
  while (i < n) {
    const c = line[i];
    const two = line.slice(i, i + 2);
    if (two === '//' || (c === '#' && !line.slice(0, i).includes('"'))) {
      flush();
      segs.push({t: 'com', v: line.slice(i)});
      break;
    }
    if (c === '"' || c === "'" || c === '`') {
      flush();
      let j = i + 1;
      while (j < n && line[j] !== c) {
        if (line[j] === '\\') j++;
        j++;
      }
      segs.push({t: 'str', v: line.slice(i, Math.min(j + 1, n))});
      i = j + 1;
      continue;
    }
    buf += c;
    i++;
  }
  flush();
  return segs;
}

function cls(t: Seg['t']): string {
  return t === 'plain' ? '' : `tok-${t}`;
}

// ───────── code viewer ─────────
export type DiffLine = {sign: ' ' | '+' | '-'; text: string};

export function IdeCodeViewer({
  filename,
  content,
  diff,
}: {
  filename: string;
  content?: string;
  diff?: DiffLine[] | null;
}) {
  const [mode, setMode] = useState<'content' | 'diff'>(diff && diff.length ? 'diff' : 'content');
  const [wrap, setWrap] = useState(false);
  const [copied, setCopied] = useState(false);
  const hasDiff = !!(diff && diff.length);

  const contentLines = useMemo(() => (content ?? '').replace(/\n$/, '').split('\n'), [content]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(content ?? (diff ?? []).map((d) => d.text).join('\n'));
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    } catch {
      /* clipboard blocked */
    }
  }

  const rows =
    mode === 'diff' && hasDiff
      ? (diff ?? []).map((d, idx) => ({
          n: idx + 1,
          text: d.text,
          klass: d.sign === '+' ? 'add' : d.sign === '-' ? 'del' : '',
        }))
      : contentLines.map((text, idx) => ({n: idx + 1, text, klass: ''}));

  return (
    <div className='ide-code'>
      <div className='ide-code-toolbar'>
        <span className='ide-mono'>{filename || 'untitled'}</span>
        <span style={{flex: 1}} />
        {hasDiff && (
          <>
            <button
              className='ide-status btn'
              style={{color: mode === 'content' ? 'var(--ide-accent)' : undefined}}
              onClick={() => setMode('content')}
            >
              Content
            </button>
            <button
              className='ide-status btn'
              style={{color: mode === 'diff' ? 'var(--ide-accent)' : undefined}}
              onClick={() => setMode('diff')}
            >
              Diff
            </button>
          </>
        )}
        <button className='ide-status btn' onClick={() => setWrap((w) => !w)}>
          {wrap ? 'No wrap' : 'Wrap'}
        </button>
        <button className='ide-status btn' onClick={copy}>
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <div className='ide-code-scroll'>
        <table className='ide-code-table'>
          <tbody>
            {rows.map((r) => (
              <tr className={`ide-code-row ${r.klass}`} key={r.n}>
                <td className='ide-code-gutter'>{r.n}</td>
                <td
                  className='ide-code-line'
                  style={wrap ? {whiteSpace: 'pre-wrap', wordBreak: 'break-word'} : undefined}
                >
                  {tokenizeLine(r.text).map((s, k) => (
                    <span key={k} className={cls(s.t)}>
                      {s.v}
                    </span>
                  ))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ───────── command palette (Cmd/Ctrl-K) ─────────
export type PaletteCmd = {id: string; label: string; hint?: string; run: () => void};

export function IdeCommandPalette({
  open,
  onClose,
  commands,
}: {
  open: boolean;
  onClose: () => void;
  commands: PaletteCmd[];
}) {
  const [q, setQ] = useState('');
  const [sel, setSel] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      setQ('');
      setSel(0);
      setTimeout(() => inputRef.current?.focus(), 10);
    }
  }, [open]);

  const filtered = useMemo(() => {
    const t = q.trim().toLowerCase();
    return t ? commands.filter((c) => c.label.toLowerCase().includes(t)) : commands;
  }, [q, commands]);

  if (!open) return null;
  return (
    <div className='ide-palette-backdrop' onClick={onClose}>
      <div className='ide-palette' onClick={(e) => e.stopPropagation()}>
        <input
          ref={inputRef}
          placeholder='Go to or run a command…'
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setSel(0);
          }}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') setSel((s) => Math.min(s + 1, filtered.length - 1));
            else if (e.key === 'ArrowUp') setSel((s) => Math.max(s - 1, 0));
            else if (e.key === 'Enter') {
              filtered[sel]?.run();
              onClose();
            } else if (e.key === 'Escape') onClose();
          }}
        />
        <div className='ide-palette-list'>
          {filtered.length === 0 && <div className='ide-palette-item'>No matches</div>}
          {filtered.map((c, idx) => (
            <div
              key={c.id}
              className={`ide-palette-item${idx === sel ? ' active' : ''}`}
              onMouseEnter={() => setSel(idx)}
              onClick={() => {
                c.run();
                onClose();
              }}
            >
              {c.label}
              {c.hint && <span className='ide-palette-kbd'>{c.hint}</span>}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// Hook: register Cmd/Ctrl-K to open the palette.
export function usePaletteHotkey(setOpen: (v: boolean) => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [setOpen]);
}

// ───────── file-type icons (by extension) ─────────
// Both icons now delegate to the per-language SVG set in IdeIcons. The
// signatures are unchanged so IdeFileTree / IdeCodeViewer callers stay put.
export function FileIcon({name}: {name: string}) {
  const Icon = fileIconFor(name);
  return <Icon className='ide-ficon' size={14} />;
}

export function FolderIcon({open = false}: {open?: boolean}) {
  return open ? (
    <FolderOpenIcon className='ide-ficon' size={14} />
  ) : (
    <FolderClosedIcon className='ide-ficon' size={14} />
  );
}

// ───────── folder tree (built from flat paths) ─────────
type TreeNode = {name: string; path: string; dir: boolean; children: TreeNode[]};
function buildTree(paths: string[]): TreeNode {
  const root: TreeNode = {name: '', path: '', dir: true, children: []};
  for (const p of paths) {
    const parts = p.split('/').filter(Boolean);
    let cur = root;
    parts.forEach((part, i) => {
      const isFile = i === parts.length - 1;
      let child = cur.children.find((c) => c.name === part && c.dir === !isFile);
      if (!child) {
        child = {name: part, path: parts.slice(0, i + 1).join('/'), dir: !isFile, children: []};
        cur.children.push(child);
      }
      cur = child;
    });
  }
  const sortRec = (n: TreeNode) => {
    n.children.sort((a, b) => (a.dir !== b.dir ? (a.dir ? -1 : 1) : a.name.localeCompare(b.name)));
    n.children.forEach(sortRec);
  };
  sortRec(root);
  return root;
}

export function IdeFileTree({
  files,
  activePath,
  onOpen,
}: {
  files: string[];
  activePath: string | null;
  onOpen: (p: string) => void;
}) {
  const tree = useMemo(() => buildTree(files), [files]);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const toggle = (p: string) =>
    setCollapsed((s) => {
      const x = new Set(s);
      if (x.has(p)) x.delete(p);
      else x.add(p);
      return x;
    });

  const render = (nodes: TreeNode[], depth: number): React.ReactNode =>
    nodes.map((n) =>
      n.dir ? (
        <div key={n.path}>
          <button
            className='ide-tree-item'
            style={{paddingLeft: 8 + depth * 12}}
            onClick={() => toggle(n.path)}
          >
            <span className='ide-tree-chev'>{collapsed.has(n.path) ? '▸' : '▾'}</span>
            <FolderIcon open={!collapsed.has(n.path)} />
            {n.name}
          </button>
          {!collapsed.has(n.path) && render(n.children, depth + 1)}
        </div>
      ) : (
        <button
          key={n.path}
          className={`ide-tree-item${activePath === n.path ? ' active' : ''}`}
          style={{paddingLeft: 8 + depth * 12 + 14}}
          onClick={() => onOpen(n.path)}
          title={n.path}
        >
          <FileIcon name={n.name} />
          {n.name}
        </button>
      ),
    );

  return <div>{render(tree.children, 0)}</div>;
}

// ───────── draggable resize handle (col-resize) ─────────
export function ResizeHandle({onDelta}: {onDelta: (dx: number) => void}) {
  const last = useRef(0);
  const onDown = (e: React.MouseEvent) => {
    last.current = e.clientX;
    const move = (ev: MouseEvent) => {
      onDelta(ev.clientX - last.current);
      last.current = ev.clientX;
    };
    const up = () => {
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    e.preventDefault();
  };
  return (
    <div className='ide-resize' onMouseDown={onDown} role='separator' aria-orientation='vertical' />
  );
}

// ───────── loading spinner ─────────
export function IdeLoader({label = 'Loading…', pad}: {label?: string; pad?: number}) {
  return (
    <div className='ide-loader' style={pad != null ? {padding: pad} : undefined}>
      <span className='ide-spinner' aria-hidden='true' />
      {label}
    </div>
  );
}

// Re-export navigate convenience for surfaces that need it.
export {useNavigate};
