import {useState, type ReactNode} from 'react';

import {Markdown} from '@/signal/components/Markdown';
import type {AgentChatBubble} from '@/ide/agentChatHistory';
import {looksLikeArtifact} from '@/ide/projectMoreUtils';

export type ProjectSubTab = {key: string; label: string};

export function ProjectRouteTabs({
  tabs,
  active,
  onSelect,
}: {
  tabs: ProjectSubTab[];
  active: string;
  onSelect: (key: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const activeTab = tabs.find((t) => t.key === active) ?? tabs[0];
  function choose(key: string) {
    onSelect(key);
    setOpen(false);
  }
  return (
    <>
      <div className='ide-local-mobile-nav'>
        <button
          type='button'
          className='ide-project-mobile-trigger'
          aria-haspopup='menu'
          aria-expanded={open}
          onClick={() => setOpen((v) => !v)}
        >
          <span>{activeTab?.label ?? 'Section'}</span>
          <span aria-hidden='true'>⌄</span>
        </button>
        {open && (
          <div className='ide-project-mobile-menu' role='menu'>
            {tabs.map((t) => (
              <button
                key={t.key}
                type='button'
                className={`ide-project-mobile-item${active === t.key ? ' active' : ''}`}
                role='menuitem'
                onClick={() => choose(t.key)}
              >
                {t.label}
              </button>
            ))}
          </div>
        )}
      </div>
      <div className='ide-rtabs ide-local-tabs' style={{marginBottom: 16, borderRadius: 4}}>
        {tabs.map((t) => (
          <button
            key={t.key}
            type='button'
            className={`ide-rtab${active === t.key ? ' active' : ''}`}
            onClick={() => onSelect(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>
    </>
  );
}

export function ProjectBanner({err, msg}: {err?: string; msg?: string}) {
  if (!err && !msg) return null;
  return (
    <div
      className='ide-card'
      style={{
        marginBottom: 12,
        fontSize: 13,
        color: err ? 'var(--ide-red)' : 'var(--ide-green)',
        borderColor: err ? 'var(--ide-red)' : 'var(--ide-green)',
      }}
    >
      {err || msg}
    </div>
  );
}

export function ProjectMetaLabel({children}: {children: ReactNode}) {
  return (
    <div
      style={{
        fontSize: 10,
        fontWeight: 700,
        color: 'var(--ide-fg-muted)',
        letterSpacing: '0.1em',
        textTransform: 'uppercase',
        marginBottom: 4,
      }}
    >
      {children}
    </div>
  );
}

export function ProjectField({label, children}: {label: string; children: ReactNode}) {
  return (
    <div style={{marginBottom: 14}}>
      <div
        style={{
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.14em',
          textTransform: 'uppercase',
          color: 'var(--ide-fg-muted)',
          marginBottom: 6,
        }}
      >
        {label}
      </div>
      {children}
    </div>
  );
}

export function ProjectChatMessageCard({message}: {message: AgentChatBubble}) {
  const artifact = message.role === 'assistant' && looksLikeArtifact(message.content);
  const [open, setOpen] = useState(!artifact);
  const visible = open ? message.content : `${message.content.slice(0, 520).trim()}...`;

  return (
    <div
      className={`ide-card ide-project-chat-message ${artifact ? 'artifact' : ''}`}
      style={{
        borderColor: message.role === 'user' ? 'var(--ide-accent)' : undefined,
      }}
    >
      <div className='ide-project-chat-message-meta'>
        <span>
          {message.role === 'user' ? 'You' : artifact ? 'Assistant artifact' : 'Assistant'}
        </span>
        {message.ts && <span> · {new Date(message.ts).toLocaleTimeString()}</span>}
        {artifact && (
          <button type='button' onClick={() => setOpen((v) => !v)}>
            {open ? 'Collapse' : 'Expand'}
          </button>
        )}
      </div>
      <div className='signal-md' style={{fontSize: 13.5}}>
        <Markdown>{visible}</Markdown>
      </div>
    </div>
  );
}
