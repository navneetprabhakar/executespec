import {useMemo} from 'react';

/**
 * Lightweight code viewer + diff renderer. Avoids the 600KB Monaco bundle
 * (we already ship 500KB+) by:
 *   1. Doing per-line tokenisation with a regex-based highlighter for the
 *      ~10 languages we care about (TS/JS/TSX/JSX/Java/Python/JSON/SQL/MD).
 *   2. For unified-diff strings, rendering with green/red line backgrounds
 *      so additions/removals are visible at a glance.
 *
 * Two render modes:
 *   <CodeView path="foo.ts" content="..." />               — plain file
 *   <CodeView path="foo.ts" diff="..." />                  — unified diff
 */
export function CodeView({
  path,
  content,
  diff,
  maxHeight = 480,
}: {
  path: string;
  content?: string;
  diff?: string;
  maxHeight?: number | string;
}) {
  const lang = guessLang(path);
  const isDiff = typeof diff === 'string' && diff.length > 0;
  const body = isDiff && diff ? diff : content || '';
  const lines = useMemo(() => body.split('\n'), [body]);

  return (
    <div
      style={{
        background: 'var(--signal-card-elev)',
        border: '1px solid var(--signal-hairline)',
        borderRadius: 10,
        overflow: 'hidden',
        fontFamily: "'JetBrains Mono', ui-monospace, monospace",
        fontSize: 12.5,
        lineHeight: 1.55,
      }}
    >
      <div
        style={{
          padding: '8px 12px',
          borderBottom: '1px solid var(--signal-hairline)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          fontSize: 11,
          color: 'var(--signal-text-dim)',
        }}
      >
        <span
          style={{whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0}}
        >
          {path}
        </span>
        <span
          style={{
            textTransform: 'uppercase',
            letterSpacing: '0.1em',
            fontSize: 10,
            flexShrink: 0,
            marginLeft: 12,
          }}
        >
          {isDiff ? 'diff' : lang}
        </span>
      </div>
      <pre style={{margin: 0, padding: 0, maxHeight, overflow: 'auto'}}>
        <code style={{display: 'block'}}>
          {lines.map((line, i) => {
            let bg: string | undefined;
            let prefix = '';
            let body = line;
            if (isDiff) {
              if (line.startsWith('+') && !line.startsWith('+++')) {
                bg = 'color-mix(in srgb, var(--signal-mint) 12%, transparent)';
                prefix = '+';
                body = line.slice(1);
              } else if (line.startsWith('-') && !line.startsWith('---')) {
                bg = 'color-mix(in srgb, var(--signal-coral) 10%, transparent)';
                prefix = '-';
                body = line.slice(1);
              } else if (line.startsWith('@@')) {
                bg = 'color-mix(in srgb, var(--signal-ocean) 10%, transparent)';
              } else if (line.startsWith('+++') || line.startsWith('---')) {
                bg = 'color-mix(in srgb, var(--signal-y) 8%, transparent)';
              }
            }
            return (
              <span
                key={i}
                style={{
                  display: 'flex',
                  background: bg,
                  paddingLeft: 12,
                  paddingRight: 12,
                  minHeight: 18,
                }}
              >
                <span
                  style={{
                    display: 'inline-block',
                    width: 36,
                    color: 'var(--signal-text-mute)',
                    textAlign: 'right',
                    paddingRight: 12,
                    flexShrink: 0,
                    userSelect: 'none',
                  }}
                >
                  {i + 1}
                </span>
                {prefix && (
                  <span
                    style={{
                      display: 'inline-block',
                      width: 12,
                      color:
                        prefix === '+'
                          ? 'var(--signal-mint-text-on-light)'
                          : prefix === '-'
                            ? 'var(--signal-coral-text)'
                            : 'inherit',
                      flexShrink: 0,
                    }}
                  >
                    {prefix}
                  </span>
                )}
                <span style={{whiteSpace: 'pre', flex: 1, minWidth: 0}}>
                  {tokenize(body, lang)}
                </span>
              </span>
            );
          })}
        </code>
      </pre>
    </div>
  );
}

function guessLang(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() || '';
  const map: Record<string, string> = {
    ts: 'ts',
    tsx: 'tsx',
    js: 'js',
    jsx: 'jsx',
    mjs: 'js',
    cjs: 'js',
    java: 'java',
    py: 'python',
    rb: 'ruby',
    go: 'go',
    rs: 'rust',
    json: 'json',
    yml: 'yaml',
    yaml: 'yaml',
    toml: 'toml',
    sql: 'sql',
    sh: 'bash',
    bash: 'bash',
    zsh: 'bash',
    md: 'markdown',
    html: 'html',
    css: 'css',
    scss: 'scss',
    xml: 'xml',
    svg: 'xml',
  };
  return map[ext] || 'text';
}

// Per-language token regex + colour for highlight. Tiny — covers keywords,
// strings, numbers, comments. Returns ReactNode array.
function tokenize(line: string, lang: string): React.ReactNode {
  const langs: Record<
    string,
    {keywords: Set<string>; comment: RegExp; string: RegExp; number: RegExp}
  > = {
    ts: kw(
      'const,let,var,function,class,interface,type,import,export,from,return,if,else,for,while,switch,case,break,continue,do,async,await,new,this,super,extends,implements,public,private,protected,static,readonly,as,in,of,typeof,instanceof,null,undefined,true,false,void,never,any,unknown,Promise',
    ),
    tsx: kw(
      'const,let,var,function,class,interface,type,import,export,from,return,if,else,for,while,switch,case,break,continue,do,async,await,new,this,super,extends,implements,public,private,protected,static,readonly,as,in,of,typeof,instanceof,null,undefined,true,false,void,never,any,unknown,Promise',
    ),
    js: kw(
      'const,let,var,function,class,import,export,from,return,if,else,for,while,switch,case,break,continue,do,async,await,new,this,super,extends,typeof,instanceof,null,undefined,true,false',
    ),
    jsx: kw(
      'const,let,var,function,class,import,export,from,return,if,else,for,while,switch,case,break,continue,do,async,await,new,this,super,extends,typeof,instanceof,null,undefined,true,false',
    ),
    java: kw(
      'public,private,protected,class,interface,extends,implements,return,if,else,for,while,switch,case,break,continue,do,new,this,super,static,final,void,int,long,double,float,boolean,String,null,true,false,abstract,synchronized,throws,throw,try,catch,finally,import,package',
    ),
    python: kw(
      'def,class,return,if,else,elif,for,while,break,continue,pass,import,from,as,with,try,except,finally,raise,lambda,yield,async,await,None,True,False,not,and,or,in,is,self',
    ),
    json: {
      keywords: new Set(['true', 'false', 'null']),
      comment: /^\/\/.*$/,
      string: /"(?:\\.|[^"\\])*"/g,
      number: /\b-?\d+(?:\.\d+)?\b/g,
    },
    yaml: {
      keywords: new Set(['true', 'false', 'null', 'yes', 'no']),
      comment: /#.*$/g,
      string: /"(?:\\.|[^"\\])*"|'(?:[^']|'')*'/g,
      number: /\b-?\d+(?:\.\d+)?\b/g,
    },
    sql: kw(
      'SELECT,FROM,WHERE,JOIN,LEFT,RIGHT,INNER,OUTER,ON,AS,AND,OR,NOT,IN,LIKE,BETWEEN,IS,NULL,GROUP,BY,ORDER,HAVING,LIMIT,OFFSET,UNION,INSERT,UPDATE,DELETE,CREATE,TABLE,INDEX,VIEW,DROP,ALTER,COLUMN,PRIMARY,KEY,FOREIGN,REFERENCES,DEFAULT,NOT NULL',
    ),
    bash: {
      keywords: new Set([
        'if',
        'then',
        'else',
        'elif',
        'fi',
        'for',
        'in',
        'do',
        'done',
        'while',
        'case',
        'esac',
        'function',
        'return',
        'echo',
        'export',
        'local',
        'true',
        'false',
      ]),
      comment: /#.*$/g,
      string: /"(?:\\.|[^"\\])*"|'[^']*'/g,
      number: /\b-?\d+(?:\.\d+)?\b/g,
    },
  };
  const cfg = langs[lang];
  if (!cfg) return line;
  // Replace strings, numbers, comments, keywords. Easiest correct approach:
  // walk the string and accumulate ranges.
  const ranges: Array<{start: number; end: number; color: string}> = [];
  const push = (re: RegExp, color: string) => {
    re.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(line)) !== null) {
      ranges.push({start: m.index, end: m.index + m[0].length, color});
    }
  };
  push(cfg.comment, '#7d8590');
  push(cfg.string, '#a5d6a3');
  push(cfg.number, '#e5b977');
  // Keywords (word boundary).
  const kwRe = new RegExp('\\b(?:' + Array.from(cfg.keywords).join('|') + ')\\b', 'g');
  push(kwRe, '#5b8def');
  // Sort + de-overlap (comments dominate).
  ranges.sort((a, b) => a.start - b.start || b.end - a.end);
  const filtered: typeof ranges = [];
  let cursor = -1;
  for (const r of ranges) {
    if (r.start >= cursor) {
      filtered.push(r);
      cursor = r.end;
    }
  }
  if (filtered.length === 0) return line;
  const out: React.ReactNode[] = [];
  let pos = 0;
  filtered.forEach((r, i) => {
    if (pos < r.start) out.push(<span key={`p${i}`}>{line.slice(pos, r.start)}</span>);
    out.push(
      <span key={`k${i}`} style={{color: r.color}}>
        {line.slice(r.start, r.end)}
      </span>,
    );
    pos = r.end;
  });
  if (pos < line.length) out.push(<span key='tail'>{line.slice(pos)}</span>);
  return out;
}

function kw(csv: string): {keywords: Set<string>; comment: RegExp; string: RegExp; number: RegExp} {
  return {
    keywords: new Set(csv.split(',').map((s) => s.trim())),
    comment: /\/\/.*$|\/\*[\s\S]*?\*\//g,
    string: /"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`/g,
    number: /\b-?\d+(?:\.\d+)?\b/g,
  };
}
