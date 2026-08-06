import {useState, type ReactNode} from 'react';
import {ChevronDown} from 'lucide-react';

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
          <ChevronDown size='var(--ide-icon)' strokeWidth={1.75} aria-hidden='true' />
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
      <div
        className='ide-rtabs ide-local-tabs'
        style={{marginBottom: 16, borderRadius: 'var(--ide-r-control)'}}
      >
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

/**
 * A result banner is state, and state is a 3px leading edge plus a tint — it was a
 * full card with both its border AND its text repainted, which is the loudest thing
 * on the surface for what is usually a one-line "Synced." confirmation.
 */
export function ProjectBanner({err, msg}: {err?: string; msg?: string}) {
  if (!err && !msg) return null;
  return (
    <div
      className={`ide-note ${err ? 'bad' : 'ok'}`}
      role={err ? 'alert' : 'status'}
      style={{marginBottom: 'var(--ide-s3)'}}
    >
      {err || msg}
    </div>
  );
}

/**
 * Field and meta labels. These were 12px/700 tracked out to 0.1–0.14em uppercase —
 * the exact treatment ide.css's own `.ide-h2` note rejects ("hierarchy comes from
 * weight and colour, not from tracking out 11px capitals"). They predated it.
 */
export function ProjectMetaLabel({children}: {children: ReactNode}) {
  return <div className='ide-project-fieldlabel'>{children}</div>;
}

export function ProjectField({label, children}: {label: string; children: ReactNode}) {
  return (
    <div className='ide-project-field'>
      <div className='ide-project-fieldlabel'>{label}</div>
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
      <div className='signal-md' style={{fontSize: 13}}>
        <Markdown>{visible}</Markdown>
      </div>
    </div>
  );
}
