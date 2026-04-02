import { NavLink, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { useEffect, useMemo, useState } from 'react';
import {
  LayoutDashboard, BarChart3, DollarSign, Plug, Settings, FolderKanban, ClipboardList,
  Receipt, Calculator, Rocket, ArrowUpCircle, X, ChevronLeft,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { NAV_ITEMS } from '@/lib/constants';
import { useAuthStore, selectIsOrgAdmin, selectIsOrgMember } from '@/stores/useAuthStore';
import { useTierPermissions } from '@/hooks/useTierPermissions';
import { UsageBadge } from '@/components/billing/UsageBadge';
import { useRunStore } from '@/stores/useRunStore';
import { useUiStore } from '@/stores/useUiStore';
import { useIsMobile } from '@/hooks/useIsMobile';
import * as api from '@/lib/api';
import type { ProjectDto } from '@/lib/api';
import { fetchBillingStatus } from '@/lib/billing-api';

const ICON_MAP: Record<string, React.ElementType> = {
  LayoutDashboard, BarChart3, DollarSign, Plug, ClipboardList, Settings,
  Receipt, Calculator, Rocket,
};

export default function Sidebar() {
  const isMobile = useIsMobile();
  const mobileSidebarOpen = useUiStore((s) => s.mobileSidebarOpen);
  const setMobileSidebarOpen = useUiStore((s) => s.setMobileSidebarOpen);
  const sidebarExpanded = useUiStore((s) => s.sidebarExpanded);
  const setSidebarExpanded = useUiStore((s) => s.setSidebarExpanded);

  const isAdmin = useAuthStore(selectIsOrgAdmin);
  const isOrgMember = useAuthStore(selectIsOrgMember);
  const permissions = useTierPermissions();
  const navigate = useNavigate();

  const projectFilter = useRunStore((s) => s.projectFilter);
  const setProjectFilter = useRunStore((s) => s.setProjectFilter);

  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [tier, setTier] = useState<string>('');

  useEffect(() => {
    if (!isOrgMember) return;
    api.getProjects()
      .then((p) => setProjects(p.filter((proj) => proj.status === 'ACTIVE')))
      .catch(() => setProjects([]));
  }, [isOrgMember]);

  useEffect(() => {
    fetchBillingStatus().then((s) => setTier(s.plan?.tier ?? 'FREE')).catch(() => {});
  }, []);

  useEffect(() => {
    if (isMobile && mobileSidebarOpen) {
      document.body.classList.add('overflow-hidden');
      return () => document.body.classList.remove('overflow-hidden');
    }
  }, [isMobile, mobileSidebarOpen]);

  useEffect(() => {
    if (!isMobile && mobileSidebarOpen) setMobileSidebarOpen(false);
  }, [isMobile, mobileSidebarOpen, setMobileSidebarOpen]);

  const visibleItems = useMemo(
    () => NAV_ITEMS.filter((item) => {
      if (item.adminOnly && !isAdmin) return false;
      if (item.requiredPermission && permissions) {
        return !!permissions[item.requiredPermission as keyof typeof permissions];
      }
      if (item.requiredPermission && !permissions) return false;
      return true;
    }),
    [isAdmin, permissions],
  );

  const handleProjectClick = (projectId: string) => {
    if (projectFilter === projectId) {
      setProjectFilter(null);
    } else {
      setProjectFilter(projectId);
    }
    navigate('/');
    if (isMobile) setMobileSidebarOpen(false);
  };

  const expanded = isMobile ? true : sidebarExpanded !== false;

  const closeMobileDrawer = () => setMobileSidebarOpen(false);

  const sidebarContent = (
    <>
      {/* Logo */}
      <div className="flex items-center h-14 px-4 border-b border-[var(--border-default)] flex-shrink-0">
        <div className="h-6 w-6 rounded-md bg-[var(--text-primary)] flex items-center justify-center flex-shrink-0">
          <span className="text-[var(--geist-bg)] text-xs font-bold">ES</span>
        </div>
        {expanded && (
          <span className="ml-2.5 text-sm font-semibold text-primary whitespace-nowrap">
            ExecuteSpec
          </span>
        )}
        {isMobile && (
          <button
            onClick={closeMobileDrawer}
            className="ml-auto min-h-[44px] min-w-[44px] flex items-center justify-center -mr-2 text-muted hover:text-primary"
          >
            <X className="h-5 w-5" />
          </button>
        )}
        {!isMobile && (
          <button
            onClick={() => setSidebarExpanded(!sidebarExpanded)}
            className="ml-auto text-muted hover:text-primary transition-colors"
          >
            <ChevronLeft className={cn('h-4 w-4 transition-transform', !expanded && 'rotate-180')} />
          </button>
        )}
      </div>

      {/* Nav items */}
      <nav className="flex-1 py-2 space-y-0.5 overflow-y-auto px-2">
        {visibleItems.map((item) => {
          const Icon = ICON_MAP[item.icon] ?? LayoutDashboard;
          return (
            <NavLink
              key={item.path}
              to={item.path}
              end={item.path === '/'}
              onClick={isMobile ? closeMobileDrawer : undefined}
              className={({ isActive }) =>
                cn(
                  'flex items-center h-9 px-2.5 gap-2.5 rounded-md transition-colors',
                  'hover:bg-[var(--gray-200)] group',
                  isActive
                    ? 'bg-[var(--gray-200)] text-primary'
                    : 'text-secondary'
                )
              }
            >
              {({ isActive }) => (
                <>
                  <Icon
                    className={cn(
                      'h-4 w-4 flex-shrink-0 transition-colors',
                      isActive ? 'text-primary' : 'text-muted group-hover:text-secondary'
                    )}
                  />
                  {expanded && (
                    <span className="text-sm whitespace-nowrap overflow-hidden">
                      {item.label}
                    </span>
                  )}
                </>
              )}
            </NavLink>
          );
        })}

        {/* Projects section */}
        {isOrgMember && projects.length > 0 && expanded && (
          <>
            <div className="px-2.5 pt-5 pb-1.5">
              <span className="text-[10px] font-semibold uppercase tracking-widest text-muted whitespace-nowrap">
                Projects
              </span>
            </div>
            {projects.map((project) => {
              const isActive = projectFilter === project.id;
              return (
                <button
                  key={project.id}
                  onClick={() => handleProjectClick(project.id)}
                  className={cn(
                    'flex items-center h-8 w-full px-2.5 gap-2.5 rounded-md transition-colors text-left',
                    'hover:bg-[var(--gray-200)] group',
                    isActive
                      ? 'bg-[var(--gray-200)] text-primary'
                      : 'text-secondary'
                  )}
                >
                  <FolderKanban
                    className={cn(
                      'h-3.5 w-3.5 flex-shrink-0 transition-colors',
                      isActive ? 'text-primary' : 'text-muted group-hover:text-secondary'
                    )}
                  />
                  <span className="text-sm whitespace-nowrap overflow-hidden">
                    {project.name}
                  </span>
                </button>
              );
            })}
          </>
        )}
      </nav>

      {/* Upgrade + Usage */}
      {expanded && (
        <div className="px-3 py-2 border-t border-[var(--border-default)] space-y-2">
          {tier && tier !== 'ENTERPRISE' && (
            <button
              onClick={() => { navigate('/billing'); if (isMobile) closeMobileDrawer(); }}
              className="flex items-center gap-2 w-full px-2 py-1.5 rounded-md text-xs font-medium bg-[var(--gray-200)] text-secondary hover:text-primary hover:bg-[var(--gray-300)] transition-colors"
            >
              <ArrowUpCircle className="h-3.5 w-3.5 flex-shrink-0" />
              <span>Upgrade{tier === 'FREE' ? ' to Pro' : ''}</span>
            </button>
          )}
          <UsageBadge />
        </div>
      )}

    </>
  );

  // ── Mobile: slide-out drawer ──────────────────────────────────────────

  if (isMobile) {
    return (
      <AnimatePresence>
        {mobileSidebarOpen && (
          <>
            <motion.div
              key="sidebar-backdrop"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.2 }}
              className="fixed inset-0 z-40 bg-[var(--gray-200)]"
              onClick={closeMobileDrawer}
            />
            <motion.aside
              key="sidebar-drawer"
              initial={{ x: -280 }}
              animate={{ x: 0 }}
              exit={{ x: -280 }}
              transition={{ duration: 0.25, ease: 'easeOut' }}
              className="fixed top-0 left-0 bottom-0 z-50 w-[280px] flex flex-col bg-[var(--geist-bg)] border-r border-[var(--border-default)] overflow-hidden"
              style={{ paddingTop: 'env(safe-area-inset-top, 0px)', paddingBottom: 'env(safe-area-inset-bottom, 0px)' }}
            >
              {sidebarContent}
            </motion.aside>
          </>
        )}
      </AnimatePresence>
    );
  }

  // ── Desktop: fixed-width sidebar with toggle ──────────────────────────

  return (
    <aside
      className={cn(
        'relative flex flex-col h-full bg-[var(--geist-bg)] border-r border-[var(--border-default)] overflow-hidden z-20 flex-shrink-0 transition-[width] duration-200',
        expanded ? 'w-[240px]' : 'w-16'
      )}
    >
      {sidebarContent}
    </aside>
  );
}
