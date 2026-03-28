import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { cn } from '@/lib/utils';

interface MarkdownContentProps {
  content: string;
  className?: string;
  /**
   * compact=true  → xs text, tighter spacing (chat bubbles)
   * compact=false → sm text, looser spacing (full-page views)
   */
  compact?: boolean;
}

/**
 * Renders markdown content with the executespec dark-theme design system.
 * Supports GFM: tables, task lists, strikethrough, autolinks.
 */
export function MarkdownContent({ content, className, compact = false }: MarkdownContentProps) {
  const prose = compact ? 'text-xs' : 'text-sm';

  return (
    <div className={cn('space-y-0', className)}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{

          // ── Headings ─────────────────────────────────────────────────────
          h1: ({ children }) => (
            <h1 className="text-sm font-bold text-zinc-100 mt-3 mb-1.5 first:mt-0 border-b border-white/[0.06] pb-1">
              {children}
            </h1>
          ),
          h2: ({ children }) => (
            <h2 className="text-sm font-semibold text-zinc-100 mt-2.5 mb-1 first:mt-0">
              {children}
            </h2>
          ),
          h3: ({ children }) => (
            <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-400 mt-2 mb-0.5 first:mt-0">
              {children}
            </h3>
          ),

          // ── Paragraph ────────────────────────────────────────────────────
          p: ({ children }) => (
            <p className={cn(prose, 'text-zinc-300 leading-relaxed mb-2 last:mb-0')}>
              {children}
            </p>
          ),

          // ── Inline text decoration ───────────────────────────────────────
          strong: ({ children }) => (
            <strong className="font-semibold text-zinc-100">{children}</strong>
          ),
          em: ({ children }) => (
            <em className="italic text-zinc-400">{children}</em>
          ),
          del: ({ children }) => (
            <del className="line-through text-zinc-500">{children}</del>
          ),

          // ── Code ─────────────────────────────────────────────────────────
          code: ({ inline, children, ...props }: any) =>
            inline ? (
              <code className="font-mono text-[11px] bg-black/40 text-accent-violet/90 px-1.5 py-0.5 rounded border border-white/[0.07] not-italic">
                {children}
              </code>
            ) : (
              <code className="font-mono text-xs text-zinc-300 leading-relaxed">
                {children}
              </code>
            ),
          pre: ({ children }) => (
            <pre className="bg-black/40 border border-white/[0.07] rounded-sm px-3 py-2.5 overflow-x-auto mb-2 last:mb-0 text-xs font-mono leading-relaxed">
              {children}
            </pre>
          ),

          // ── Lists ────────────────────────────────────────────────────────
          ul: ({ children }) => (
            <ul className="mb-2 last:mb-0 space-y-0.5 pl-0">
              {children}
            </ul>
          ),
          ol: ({ children }) => (
            <ol className="mb-2 last:mb-0 space-y-0.5 pl-0 list-none">
              {children}
            </ol>
          ),
          li: ({ children, ordered, index }: any) => (
            <li className={cn('flex items-start gap-2', prose, 'text-zinc-300 leading-relaxed')}>
              <span className="flex-shrink-0 select-none">
                {ordered ? (
                  <span className="text-accent-violet/60 font-mono text-[10px] mt-0.5 inline-block w-4 text-right">
                    {(index ?? 0) + 1}.
                  </span>
                ) : (
                  <span className="mt-[7px] h-1.5 w-1.5 rounded-full bg-accent-violet/50 inline-block" />
                )}
              </span>
              <span className="flex-1 min-w-0">{children}</span>
            </li>
          ),

          // ── Blockquote ───────────────────────────────────────────────────
          blockquote: ({ children }) => (
            <blockquote className="border-l-2 border-accent-violet/40 pl-3 my-2 text-zinc-400 italic">
              {children}
            </blockquote>
          ),

          // ── Horizontal rule ──────────────────────────────────────────────
          hr: () => <hr className="border-white/[0.08] my-3" />,

          // ── Links ────────────────────────────────────────────────────────
          a: ({ href, children }) => (
            <a
              href={href}
              className="text-accent-violet hover:text-accent-violet/70 underline underline-offset-2 transition-colors"
              target="_blank"
              rel="noopener noreferrer"
            >
              {children}
            </a>
          ),

          // ── Tables (GFM) ─────────────────────────────────────────────────
          table: ({ children }) => (
            <div className="overflow-x-auto mb-2 last:mb-0 rounded-sm border border-white/[0.07]">
              <table className="w-full text-xs border-collapse">{children}</table>
            </div>
          ),
          thead: ({ children }) => (
            <thead className="bg-white/[0.04] border-b border-white/[0.08]">{children}</thead>
          ),
          tbody: ({ children }) => <tbody className="divide-y divide-white/[0.04]">{children}</tbody>,
          tr: ({ children }) => <tr className="hover:bg-white/[0.02] transition-colors">{children}</tr>,
          th: ({ children }) => (
            <th className="text-left py-2 px-3 font-semibold text-zinc-200 text-xs whitespace-nowrap">
              {children}
            </th>
          ),
          td: ({ children }) => (
            <td className="py-2 px-3 text-zinc-400 text-xs">{children}</td>
          ),

          // ── Task list checkboxes (GFM) ────────────────────────────────────
          input: ({ checked, ...props }: any) => (
            <input
              type="checkbox"
              checked={checked}
              readOnly
              className="mr-1.5 accent-accent-violet align-middle"
              {...props}
            />
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}
