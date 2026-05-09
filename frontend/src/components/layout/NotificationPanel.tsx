import {useState, useEffect} from 'react';
import {Check, CheckCheck, Loader2} from 'lucide-react';
import {
  getNotifications,
  markNotificationRead,
  markAllNotificationsRead,
  type NotificationItem,
} from '@/lib/api';
import {useRunStore} from '@/stores/useRunStore';
import {useNavigate} from 'react-router-dom';
import {cn} from '@/lib/utils';

interface Props {
  onClose: () => void;
  onRead: () => void;
  onReadAll: () => void;
}

export default function NotificationPanel({onClose, onRead, onReadAll}: Props) {
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(true);
  const setActiveRunId = useRunStore((s) => s.setActiveRunId);
  const navigate = useNavigate();

  useEffect(() => {
    getNotifications()
      .then((res) => setItems(res.content))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  // Extract runId from link like "/runs/abc123"
  const extractRunId = (link: string | null): string | null => {
    if (!link) return null;
    const m = link.match(/\/runs\/([^/]+)/);
    return m ? m[1] : null;
  };

  const handleClick = async (n: NotificationItem) => {
    if (!n.read) {
      markNotificationRead(n.id).catch(() => {});
      setItems((prev) => prev.map((i) => (i.id === n.id ? {...i, read: true} : i)));
      onRead();
    }
    const runId = extractRunId(n.link);
    if (runId) {
      setActiveRunId(runId);
      onClose();
    } else if (n.link) {
      navigate(n.link);
      onClose();
    }
  };

  const handleReadAll = async () => {
    markAllNotificationsRead().catch(() => {});
    setItems((prev) => prev.map((i) => ({...i, read: true})));
    onReadAll();
  };

  const typeIcon = (type: string) => {
    if (type.includes('COMPLETE')) return 'text-accent-green';
    if (type.includes('FAILED')) return 'text-accent-red';
    return 'text-secondary';
  };

  return (
    <div
      className={cn(
        // Mobile (< sm 640px): pin to viewport, right-anchored with 8px gutter,
        // width = viewport - 16px (both gutters), below the 56px (h-14) header.
        //
        // Previously the panel used `absolute right-0 top-full` with
        // `w-[calc(100vw-1rem)]`, which anchored the panel to the tiny bell
        // button. `right-0` = right edge of the bell (not viewport). On a 390px
        // screen the 374px-wide panel then extended ~16px past the LEFT edge of
        // the viewport. `fixed` detaches from the bell. `right-2` + the calc()
        // width makes the panel span the viewport with symmetric gutters.
        'fixed right-2 top-14 mt-1 w-[calc(100vw-1rem)] max-w-sm',
        // Desktop: revert to the original dropdown-from-bell behaviour.
        'sm:absolute sm:right-0 sm:top-full sm:mt-1 sm:w-80',
        'max-h-96 rounded-lg border border-[var(--border-default)] bg-[var(--geist-bg)] shadow-lg overflow-hidden z-50',
      )}
    >
      <div className='flex items-center justify-between px-3 py-2 border-b border-[var(--border-default)]'>
        <span className='text-xs font-semibold text-primary'>Notifications</span>
        <button
          onClick={handleReadAll}
          className='text-[10px] text-muted hover:text-primary flex items-center gap-1'
        >
          <CheckCheck className='h-3 w-3' /> Mark all read
        </button>
      </div>
      <div className='overflow-y-auto max-h-80'>
        {loading && (
          <div className='flex items-center justify-center py-8'>
            <Loader2 className='h-4 w-4 animate-spin text-muted' />
          </div>
        )}
        {!loading && items.length === 0 && (
          // U-45 fix (2026-05-09) — actionable empty state instead of a
          // dead-end "No notifications yet". Tells the user what would
          // populate this list so they know what to expect, not just
          // what they don't have right now.
          <div className='py-8 px-4 text-center'>
            <p className='text-xs font-medium text-secondary'>You're all caught up</p>
            <p className='text-[11px] text-muted mt-1'>
              Run completions, plan-approval gates, PR merges, and budget alerts will land here.
            </p>
          </div>
        )}
        {items.map((n) => (
          <button
            key={n.id}
            onClick={() => handleClick(n)}
            className={cn(
              'w-full text-left px-3 py-2.5 border-b border-[var(--border-default)] hover:bg-[var(--gray-200)] transition-colors',
              !n.read && 'bg-accent-blue/5',
            )}
          >
            <div className='flex items-start gap-2'>
              {!n.read && (
                <span className='mt-1.5 h-1.5 w-1.5 rounded-full bg-accent-blue flex-shrink-0' />
              )}
              <div className='min-w-0 flex-1'>
                <div className={cn('text-xs font-medium truncate', typeIcon(n.type))}>
                  {n.title}
                </div>
                <div className='text-[11px] text-muted mt-0.5 line-clamp-2'>{n.body}</div>
                <div className='text-[10px] text-muted mt-1'>{formatRelative(n.createdAt)}</div>
              </div>
              {!n.read && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    markNotificationRead(n.id).catch(() => {});
                    setItems((prev) => prev.map((i) => (i.id === n.id ? {...i, read: true} : i)));
                    onRead();
                  }}
                  className='mt-0.5 text-muted hover:text-primary'
                  title='Mark read'
                >
                  <Check className='h-3 w-3' />
                </button>
              )}
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}

function formatRelative(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}
