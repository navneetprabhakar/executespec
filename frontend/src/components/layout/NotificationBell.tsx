import { useState, useEffect, useRef } from 'react';
import { Bell } from 'lucide-react';
import { getUnreadCount } from '@/lib/api';
import NotificationPanel from './NotificationPanel';

export default function NotificationBell() {
  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let mounted = true;
    const poll = () => {
      getUnreadCount()
        .then((res) => { if (mounted) setUnread(res.count); })
        .catch(() => {});
    };
    poll();
    const id = setInterval(poll, 30_000);
    return () => { mounted = false; clearInterval(id); };
  }, []);

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((o) => !o)}
        className="relative flex items-center justify-center h-8 w-8 rounded-md text-muted hover:text-primary hover:bg-[var(--gray-200)] transition-colors"
        aria-label="Notifications"
      >
        <Bell className="h-4 w-4" />
        {unread > 0 && (
          <span className="absolute -top-0.5 -right-0.5 flex items-center justify-center h-4 min-w-[16px] px-1 rounded-full bg-accent-blue text-[10px] font-bold text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>
      {open && <NotificationPanel onClose={() => setOpen(false)} onRead={() => setUnread((n) => Math.max(0, n - 1))} onReadAll={() => setUnread(0)} />}
    </div>
  );
}
