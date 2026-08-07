import {CloseIcon} from '@/ide/agentIcons';

export function ConversationHistoryRow({
  title,
  meta,
  active,
  archived,
  closeDisabledReason,
  onSelect,
  onClose,
}: {
  title: string;
  meta: string;
  active: boolean;
  archived?: boolean;
  closeDisabledReason?: string | null;
  onSelect: () => void;
  onClose: () => void;
}) {
  return (
    <div className='agent-drawer-conversation'>
      <button className={active ? 'active' : ''} onClick={onSelect}>
        <span className='agent-drawer-title'>{title}</span>
        <span className='agent-drawer-meta'>
          {archived ? 'Archived · ' : ''}
          {meta}
        </span>
      </button>
      <button
        className='agent-conversation-close'
        aria-label={`Close conversation ${title}`}
        title={
          archived ? 'Conversation already closed' : closeDisabledReason || 'Close conversation'
        }
        disabled={archived || !!closeDisabledReason}
        onClick={onClose}
      >
        <CloseIcon size={14} />
      </button>
    </div>
  );
}

export function ConversationHistoryPager({
  hasEarlier,
  loading,
  error,
  onLoad,
}: {
  hasEarlier: boolean;
  loading: boolean;
  error: string | null;
  onLoad?: () => void;
}) {
  if (!hasEarlier && !error) return null;
  return (
    <div className='agent-load-earlier'>
      {/* `.agent-load-earlier` paints its children with `var(--ide-danger)`, a
          token that does not exist in this palette — so a paging failure inherited
          the surrounding colour and read as ordinary text. The failure carries its
          own stripe instead of relying on an undefined variable, and the container
          is no longer tinted when there is nothing wrong. */}
      {error && (
        <span className='ide-note bad gf-inline-note' role='alert'>
          {error}
        </span>
      )}
      <button type='button' className='ide-btn ghost' disabled={loading} onClick={onLoad}>
        {loading ? 'Loading earlier messages…' : 'Load earlier messages'}
      </button>
    </div>
  );
}
