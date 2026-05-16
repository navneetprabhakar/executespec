import {ReactNode} from 'react';

/**
 * Tiny markdown renderer — handles the subset of GFM that LLM replies actually
 * use: headings, paragraphs, bold/italic, inline code, fenced code blocks,
 * unordered + ordered lists, blockquotes, links. No tables, no images, no
 * footnotes. Keeps the bundle ~3KB vs ~80KB for react-markdown + remark-gfm.
 *
 * Usage: <Markdown>{content}</Markdown>
 */
export function Markdown({children}: {children: string}) {
  const blocks = parseBlocks(children || '');
  return <div className='signal-md'>{blocks.map((b, i) => renderBlock(b, i))}</div>;
}

type Block =
  | {kind: 'h'; level: 1 | 2 | 3 | 4 | 5 | 6; text: string}
  | {kind: 'p'; text: string}
  | {kind: 'code'; lang: string; content: string}
  | {kind: 'ul'; items: string[]}
  | {kind: 'ol'; items: string[]}
  | {kind: 'quote'; text: string}
  | {kind: 'hr'};

function parseBlocks(src: string): Block[] {
  const lines = src.replace(/\r\n/g, '\n').split('\n');
  const blocks: Block[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    // Fenced code block.
    const fence = line.match(/^```(\w*)\s*$/);
    if (fence) {
      const lang = fence[1] || '';
      i++;
      const buf: string[] = [];
      while (i < lines.length && !/^```\s*$/.test(lines[i])) {
        buf.push(lines[i]);
        i++;
      }
      if (i < lines.length) i++; // skip closing ```
      blocks.push({kind: 'code', lang, content: buf.join('\n')});
      continue;
    }
    // Heading.
    const h = line.match(/^(#{1,6})\s+(.*)$/);
    if (h) {
      blocks.push({kind: 'h', level: h[1].length as 1, text: h[2].trim()});
      i++;
      continue;
    }
    // Horizontal rule.
    if (/^---+\s*$/.test(line)) {
      blocks.push({kind: 'hr'});
      i++;
      continue;
    }
    // Blockquote.
    if (/^>\s?/.test(line)) {
      const buf: string[] = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) {
        buf.push(lines[i].replace(/^>\s?/, ''));
        i++;
      }
      blocks.push({kind: 'quote', text: buf.join('\n')});
      continue;
    }
    // Unordered list.
    if (/^\s*[-*+]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*[-*+]\s+/, ''));
        i++;
      }
      blocks.push({kind: 'ul', items});
      continue;
    }
    // Ordered list.
    if (/^\s*\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*\d+\.\s+/, ''));
        i++;
      }
      blocks.push({kind: 'ol', items});
      continue;
    }
    // Empty line — skip.
    if (/^\s*$/.test(line)) {
      i++;
      continue;
    }
    // Paragraph — accumulate until empty line / block boundary.
    const buf: string[] = [];
    while (
      i < lines.length &&
      !/^\s*$/.test(lines[i]) &&
      !/^```/.test(lines[i]) &&
      !/^#{1,6}\s/.test(lines[i]) &&
      !/^>\s?/.test(lines[i]) &&
      !/^\s*[-*+]\s+/.test(lines[i]) &&
      !/^\s*\d+\.\s+/.test(lines[i]) &&
      !/^---+\s*$/.test(lines[i])
    ) {
      buf.push(lines[i]);
      i++;
    }
    if (buf.length > 0) blocks.push({kind: 'p', text: buf.join(' ').trim()});
  }
  return blocks;
}

function renderInline(text: string): ReactNode[] {
  // Tokenise inline: code, bold, italic, link.
  // We do this with a single regex that captures each pattern with a label.
  const out: ReactNode[] = [];
  const tokens = [];
  // Match in order: code (`x`), bold (**x**), italic (*x*  or _x_), link [t](u).
  const re = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*]+\*|_[^_]+_)|(\[[^\]]+\]\([^)]+\))/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) tokens.push({type: 'text', value: text.slice(last, m.index)});
    if (m[1]) tokens.push({type: 'code', value: m[1].slice(1, -1)});
    else if (m[2]) tokens.push({type: 'bold', value: m[2].slice(2, -2)});
    else if (m[3]) tokens.push({type: 'italic', value: m[3].slice(1, -1)});
    else if (m[4]) {
      const lm = m[4].match(/\[([^\]]+)\]\(([^)]+)\)/);
      if (lm) tokens.push({type: 'link', text: lm[1], href: lm[2]});
    }
    last = m.index + m[0].length;
  }
  if (last < text.length) tokens.push({type: 'text', value: text.slice(last)});
  return tokens.map((t, i) => {
    switch (t.type) {
      case 'code':
        return (
          <code key={i} className='signal-md-code'>
            {t.value}
          </code>
        );
      case 'bold':
        return <strong key={i}>{t.value}</strong>;
      case 'italic':
        return <em key={i}>{t.value}</em>;
      case 'link':
        return (
          <a
            key={i}
            href={t.href}
            target='_blank'
            rel='noopener noreferrer'
            className='signal-md-link'
          >
            {t.text}
          </a>
        );
      default:
        return <span key={i}>{t.value}</span>;
    }
  });
}

function renderBlock(b: Block, i: number): ReactNode {
  switch (b.kind) {
    case 'h': {
      const Tag = `h${Math.min(b.level + 2, 6)}` as unknown as 'h3';
      return (
        <Tag key={i} className={`signal-md-h signal-md-h${b.level}`}>
          {renderInline(b.text)}
        </Tag>
      );
    }
    case 'p':
      return (
        <p key={i} className='signal-md-p'>
          {renderInline(b.text)}
        </p>
      );
    case 'code':
      return (
        <pre key={i} className='signal-md-codeblock'>
          {b.lang && <div className='signal-md-codelang'>{b.lang}</div>}
          <code>{b.content}</code>
        </pre>
      );
    case 'ul':
      return (
        <ul key={i} className='signal-md-list'>
          {b.items.map((it, j) => (
            <li key={j}>{renderInline(it)}</li>
          ))}
        </ul>
      );
    case 'ol':
      return (
        <ol key={i} className='signal-md-list'>
          {b.items.map((it, j) => (
            <li key={j}>{renderInline(it)}</li>
          ))}
        </ol>
      );
    case 'quote':
      return (
        <blockquote key={i} className='signal-md-quote'>
          {renderInline(b.text)}
        </blockquote>
      );
    case 'hr':
      return <hr key={i} className='signal-md-hr' />;
  }
}
