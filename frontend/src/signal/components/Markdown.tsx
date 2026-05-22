import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type {Components} from 'react-markdown';

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

export function Markdown({children}: {children: string}) {
  return (
    <div className='signal-md'>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {children || ''}
      </ReactMarkdown>
    </div>
  );
}
