import { useState, useEffect } from 'react';
import { Check, CheckCheck, Loader2 } from 'lucide-react';
import { getNotifications, markNotificationRead, markAllNotificationsRead, type NotificationItem } from '@/lib/api';
import { useRunStore } from '@/stores/useRunStore';
import { useNavigate } from 'react-router-dom';
import { cn } from '@/lib/utils';

interface Props {
  onClose: () => void;
  onRead: () => void;
  onReadAll: () => void;
}

export default function NotificationPanel({ onClose, onRead, onReadAll }: Props) {
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
      setItems((prev) => prev.map((i) => (i.id === n.id ? { ...i, read: true } : i)));
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
    setItems((prev) => prev.map((i) => ({ ...i, read: true })));
    onReadAll();
  };

  const typeIcon = (type: string) => {
    if (type.includes('COMPLETE')) return 'text-accent-green';
    if (type.includes('FAILED')) return 'text-accent-red';
    return 'text-secondary';
  };

  return (
    <div className="absolute right-0 top-full mt-1 w-[calc(100vw-1rem)] sm:w-80 max-w-sm max-h-96 rounded-lg border border-[var(--border-default)] bg-[var(--geist-bg)] shadow-lg overflow-hidden z-50">
      <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--border-default)]">
        <span className="text-xs font-semibold text-primary">Notifications</span>
        <button
          onClick={handleReadAll}
          className="text-[10px] text-muted hover:text-primary flex items-center gap-1"
        >
          <CheckCheck className="h-3 w-3" /> Mark all read
        </button>
      </div>
      <div className="overflow-y-auto max-h-80">
        {loading && (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-4 w-4 animate-spin text-muted" />
          </div>
        )}
        {!loading && items.length === 0 && (
          <div className="py-8 text-center text-xs text-muted">No notifications yet</div>
        )}
        {items.map((n) => (
          <button
            key={n.id}
            onClick={() => handleClick(n)}
            className={cn(
              'w-full text-left px-3 py-2.5 border-b border-[var(--border-default)] hover:bg-[var(--gray-200)] transition-colors',
              !n.read && 'bg-accent-blue/5'
            )}
          >
            <div className="flex items-start gap-2">
              {!n.read && <span className="mt-1.5 h-1.5 w-1.5 rounded-full bg-accent-blue flex-shrink-0" />}
              <div className="min-w-0 flex-1">
                <div className={cn('text-xs font-medium truncate', typeIcon(n.type))}>{n.title}</div>
                <div className="text-[11px] text-muted mt-0.5 line-clamp-2">{n.body}</div>
                <div className="text-[10px] text-muted mt-1">{formatRelative(n.createdAt)}</div>
              </div>
              {!n.read && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    markNotificationRead(n.id).catch(() => {});
                    setItems((prev) => prev.map((i) => (i.id === n.id ? { ...i, read: true } : i)));
                    onRead();
                  }}
                  className="mt-0.5 text-muted hover:text-primary"
                  title="Mark read"
                >
                  <Check className="h-3 w-3" />
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
