import {useMemo, useState} from 'react';

import {Markdown} from '@/signal/components/Markdown';
import {parseArchDoc} from '@/ide/projectMoreUtils';

export function ArchDocView({md}: {md: string}) {
  const {preamble, sections} = useMemo(() => parseArchDoc(md), [md]);
  const large = md.length > 40_000 || sections.length > 10;
  const [open, setOpen] = useState<Set<number>>(
    () => new Set(large ? [0] : sections.map((_, i) => i)),
  );
  const toggle = (i: number) =>
    setOpen((s) => {
      const n = new Set(s);
      if (n.has(i)) n.delete(i);
      else n.add(i);
      return n;
    });
  const allOpen = sections.length > 0 && open.size === sections.length;

  if (sections.length === 0) {
    return (
      <div className='signal-md' style={{fontSize: 13.5, lineHeight: 1.6}}>
        <Markdown>{md}</Markdown>
      </div>
    );
  }

  return (
    <div>
      {preamble.trim() && (
        <div className='signal-md' style={{fontSize: 13.5, lineHeight: 1.6, marginBottom: 8}}>
          <Markdown>{preamble}</Markdown>
        </div>
      )}
      {sections.length > 1 && (
        <div style={{display: 'flex', justifyContent: 'flex-end', marginBottom: 6}}>
          <button
            type='button'
            className='ide-btn ghost'
            style={{padding: '3px 10px', fontSize: 12}}
            onClick={() => setOpen(allOpen ? new Set() : new Set(sections.map((_, i) => i)))}
          >
            {allOpen ? 'Collapse all' : 'Expand all'}
          </button>
        </div>
      )}
      {sections.map((s, i) => {
        const isOpen = open.has(i);
        return (
          <div
            key={i}
            className='ide-card'
            style={{padding: 0, marginBottom: 8, overflow: 'hidden'}}
          >
            <button
              type='button'
              onClick={() => toggle(i)}
              aria-expanded={isOpen}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                width: '100%',
                padding: '10px 14px',
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                textAlign: 'left',
                color: 'var(--ide-fg-bright)',
                fontWeight: 600,
                fontSize: 13.5,
              }}
            >
              <span className='ide-tree-chev' style={{color: 'var(--ide-fg-muted)'}}>
                {isOpen ? 'v' : '>'}
              </span>
              <span>{s.heading}</span>
            </button>
            {isOpen && (
              <div
                className='signal-md'
                style={{padding: '0 14px 12px', fontSize: 13.5, lineHeight: 1.6}}
              >
                <Markdown>{s.body}</Markdown>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
