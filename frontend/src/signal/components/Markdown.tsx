import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type {Components} from 'react-markdown';
import {isValidElement, type ReactNode} from 'react';

/**
 * Markdown renderer for chat / assistant surfaces (SpecChat, ProjectChatPage,
 * ResultPage chat, ProjectArchitecturePage). Full GitHub-Flavored Markdown via
 * react-markdown + remark-gfm — tables, nested lists, task lists, strikethrough,
 * and autolinks all render correctly. Styled to the Signal palette through the
 * `.signal-md*` classes in signal.css.
 *
 * Replaces a hand-rolled GFM-subset parser (2026-05-21): the old parser silently
 * dropped tables / nested lists / task lists / strikethrough, which LLM replies
 * lean on heavily, so they rendered as garbled paragraphs.
 *
 * Usage: <Markdown>{content}</Markdown>
 */
function nodeText(node: ReactNode): string {
  if (node == null || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(nodeText).join('');
  if (isValidElement<{children?: ReactNode}>(node)) return nodeText(node.props.children);
  return '';
}

function codeLanguage(node: ReactNode): string {
  if (Array.isArray(node)) {
    for (const item of node) {
      const found = codeLanguage(item);
      if (found) return found;
    }
    return '';
  }
  if (!isValidElement<{className?: string; children?: ReactNode}>(node)) return '';
  const match = /language-([A-Za-z0-9_+.-]+)/.exec(node.props.className || '');
  return match?.[1] || codeLanguage(node.props.children);
}

function lineCount(text: string): number {
  if (!text.trim()) return 0;
  return text.replace(/\n$/, '').split('\n').length;
}

function collapsedComponents(): Components {
  return {
    ...components,
    pre: ({children}) => {
      const text = nodeText(children);
      const language = codeLanguage(children);
      const lines = lineCount(text);
      const label = [
        language ? language.toUpperCase() : 'Code block',
        lines ? `${lines} line${lines === 1 ? '' : 's'}` : null,
      ]
        .filter(Boolean)
        .join(' · ');
      return (
        <details className='signal-md-codefold'>
          <summary>{label || 'Code block'}</summary>
          <pre className='signal-md-codeblock'>{children}</pre>
        </details>
      );
    },
  };
}

const components: Components = {
  h1: ({children}) => <h1 className='signal-md-h signal-md-h1'>{children}</h1>,
  h2: ({children}) => <h2 className='signal-md-h signal-md-h2'>{children}</h2>,
  h3: ({children}) => <h3 className='signal-md-h signal-md-h3'>{children}</h3>,
  h4: ({children}) => <h4 className='signal-md-h signal-md-h4'>{children}</h4>,
  h5: ({children}) => <h5 className='signal-md-h signal-md-h5'>{children}</h5>,
  h6: ({children}) => <h6 className='signal-md-h signal-md-h6'>{children}</h6>,
  p: ({children}) => <p className='signal-md-p'>{children}</p>,
  ul: ({children}) => <ul className='signal-md-list'>{children}</ul>,
  ol: ({children}) => <ol className='signal-md-list'>{children}</ol>,
  blockquote: ({children}) => <blockquote className='signal-md-quote'>{children}</blockquote>,
  hr: () => <hr className='signal-md-hr' />,
  a: ({href, children}) => (
    <a href={href} target='_blank' rel='noopener noreferrer' className='signal-md-link'>
      {children}
    </a>
  ),
  // react-markdown v8 passes `inline` to the code renderer. Inline code gets the
  // pill style; fenced blocks keep their language class and are wrapped by `pre`.
  code: ({inline, className, children}) =>
    inline ? (
      <code className='signal-md-code'>{children}</code>
    ) : (
      <code className={className}>{children}</code>
    ),
  pre: ({children}) => <pre className='signal-md-codeblock'>{children}</pre>,
  table: ({children}) => (
    <div className='signal-md-tablewrap'>
      <table className='signal-md-table'>{children}</table>
    </div>
  ),
};

export function Markdown({
  children,
  collapseCodeBlocks = false,
}: {
  children: string;
  collapseCodeBlocks?: boolean;
}) {
  return (
    <div className='signal-md'>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={collapseCodeBlocks ? collapsedComponents() : components}
      >
        {children || ''}
      </ReactMarkdown>
    </div>
  );
}
