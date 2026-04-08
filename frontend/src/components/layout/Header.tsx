import { Link } from 'react-router-dom';
import { Menu, Search, ArrowUpRight } from 'lucide-react';
import { useUiStore } from '@/stores/useUiStore';
import { useRunStore } from '@/stores/useRunStore';
import { useAuthStore } from '@/stores/useAuthStore';
import { ACTIVE_STATUSES } from '@/lib/constants';
import { cn } from '@/lib/utils';
import UserMenu from '@/components/auth/UserMenu';
import ViewModeToggle from '@/components/shared/ViewModeToggle';
import NotificationBell from './NotificationBell';

export default function Header() {
  const { setCommandPaletteOpen, mobileSidebarOpen, setMobileSidebarOpen } = useUiStore();
  const runs = useRunStore((s) => s.runs);
  const user = useAuthStore((s) => s.user);
  const activeCount = runs.filter((r) => ACTIVE_STATUSES.has(r.status)).length;

  return (
    <header className="flex items-center h-14 px-4 gap-3 bg-[var(--geist-bg)] border-b border-[var(--border-default)] flex-shrink-0 z-10">
      {/* Mobile hamburger */}
      <button
        onClick={() => setMobileSidebarOpen(!mobileSidebarOpen)}
        className="lg:hidden min-h-[44px] min-w-[44px] flex items-center justify-center -ml-2 text-muted hover:text-primary transition-colors"
        aria-label="Toggle menu"
      >
        <Menu className="h-5 w-5" />
      </button>

      {/* Breadcrumb */}
      <div className="hidden lg:flex items-center gap-1.5 text-sm">
        <span className="text-primary font-medium">ExecuteSpec</span>
        <span className="text-muted">/</span>
        <span className="text-secondary">Runs</span>
      </div>

      {/* View mode toggle */}
      <ViewModeToggle />

      {/* Spacer */}
      <div className="flex-1" />

      {/* Command palette trigger */}
      <button
        onClick={() => setCommandPaletteOpen(true)}
        className="flex items-center gap-2 px-3 py-1.5 rounded-md bg-[var(--gray-200)] text-sm text-muted hover:text-secondary transition-colors"
      >
        <Search className="h-3.5 w-3.5" />
        <span className="hidden lg:block">Search...</span>
        <kbd className="hidden lg:block text-xs bg-[var(--gray-300)] px-1.5 py-0.5 rounded-sm font-mono text-muted">⌘K</kbd>
      </button>

      {/* Active runs count */}
      {activeCount > 0 && (
        <span className="text-xs text-secondary hidden md:inline">
          {activeCount} active
        </span>
      )}

      {/* Upgrade pill */}
      {user?.tier === 'FREE' && (
        <Link
          to="/plans"
          className="hidden sm:flex items-center gap-1 px-2.5 py-1 rounded-full bg-accent-blue/10 text-accent-blue text-xs font-medium hover:bg-accent-blue/20 transition-colors"
        >
          Upgrade
          <ArrowUpRight className="h-3 w-3" />
        </Link>
      )}

      {/* Notifications */}
      <NotificationBell />

      {/* User menu */}
      <UserMenu />
    </header>
  );
}
