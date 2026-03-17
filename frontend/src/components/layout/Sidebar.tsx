import { NavLink, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { useEffect, useMemo, useState } from 'react';
import {
  LayoutDashboard, BarChart3, DollarSign, Plug, Settings, FolderKanban, ClipboardList,
  Receipt, Calculator, Rocket, ArrowUpCircle, X,
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
  const [hoverExpanded, setHoverExpanded] = useState(false);
  const isMobile = useIsMobile();
  const mobileSidebarOpen = useUiStore((s) => s.mobileSidebarOpen);
  const setMobileSidebarOpen = useUiStore((s) => s.setMobileSidebarOpen);

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

  // Lock body scroll when mobile drawer is open
  useEffect(() => {
    if (isMobile && mobileSidebarOpen) {
      document.body.classList.add('overflow-hidden');
      return () => document.body.classList.remove('overflow-hidden');
    }
  }, [isMobile, mobileSidebarOpen]);

  // Close mobile drawer when switching to desktop
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

  // On mobile, labels are always visible; on desktop, controlled by hover
  const expanded = isMobile ? true : hoverExpanded;

  const closeMobileDrawer = () => setMobileSidebarOpen(false);

  // ── Shared sidebar content ─────────────────────────────────────────────

  const sidebarContent = (
    <>
      {/* Logo */}
      <div className="flex items-center h-12 px-4 border-b border-white/[0.06] flex-shrink-0">
        <div className="h-6 w-6 rounded-md bg-accent-violet flex items-center justify-center flex-shrink-0">
          <span className="text-white text-xs font-bold">ES</span>
        </div>
        <motion.span
          animate={{ opacity: expanded ? 1 : 0, x: expanded ? 0 : -8 }}
          transition={{ duration: 0.15 }}
          className="ml-2.5 text-sm font-semibold text-white whitespace-nowrap"
        >
          ExecuteSpec
        </motion.span>
        {/* Mobile close button */}
        {isMobile && (
          <button
            onClick={closeMobileDrawer}
            className="ml-auto min-h-[44px] min-w-[44px] flex items-center justify-center -mr-2 text-zinc-400 hover:text-white"
          >
            <X className="h-5 w-5" />
          </button>
        )}
      </div>

      {/* Nav items */}
      <nav className="flex-1 py-3 space-y-0.5 overflow-y-auto">
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
                  'relative flex items-center h-10 px-4 gap-3 rounded-none transition-colors',
                  'hover:bg-white/[0.04] group',
                  isActive && 'text-white bg-white/[0.05]'
                )
              }
            >
              {({ isActive }) => (
                <>
                  {isActive && (
                    <span className="absolute left-0 top-1 bottom-1 w-0.5 rounded-r bg-accent-violet" />
                  )}
                  <Icon
                    className={cn(
                      'h-4 w-4 flex-shrink-0 transition-colors',
                      isActive ? 'text-accent-violet' : 'text-zinc-500 group-hover:text-zinc-300'
                    )}
                  />
                  <motion.span
                    animate={{ opacity: expanded ? 1 : 0 }}
                    transition={{ duration: 0.1 }}
                    className="text-sm whitespace-nowrap overflow-hidden"
                  >
                    {item.label}
                  </motion.span>
                </>
              )}
            </NavLink>
          );
        })}

        {/* Projects section */}
        {isOrgMember && projects.length > 0 && (
          <>
            <div className="px-4 pt-4 pb-1">
              <motion.span
                animate={{ opacity: expanded ? 1 : 0 }}
                transition={{ duration: 0.1 }}
                className="text-[10px] font-semibold uppercase tracking-wider text-zinc-600 whitespace-nowrap"
              >
                Projects
              </motion.span>
            </div>
            {projects.map((project) => {
              const isActive = projectFilter === project.id;
              return (
                <button
                  key={project.id}
                  onClick={() => handleProjectClick(project.id)}
                  className={cn(
                    'relative flex items-center h-9 w-full px-4 gap-3 rounded-none transition-colors text-left',
                    'hover:bg-white/[0.04] group',
                    isActive && 'text-white bg-white/[0.05]'
                  )}
                >
                  {isActive && (
                    <span className="absolute left-0 top-1 bottom-1 w-0.5 rounded-r bg-accent-violet" />
                  )}
                  <FolderKanban
                    className={cn(
                      'h-3.5 w-3.5 flex-shrink-0 transition-colors',
                      isActive ? 'text-accent-violet' : 'text-zinc-500 group-hover:text-zinc-300'
                    )}
                  />
                  <motion.span
                    animate={{ opacity: expanded ? 1 : 0 }}
                    transition={{ duration: 0.1 }}
                    className={cn(
                      'text-sm whitespace-nowrap overflow-hidden',
                      isActive ? 'text-white' : 'text-zinc-400'
                    )}
                  >
                    {project.name}
                  </motion.span>
                </button>
              );
            })}
          </>
        )}
      </nav>

      {/* Upgrade button + Usage badge */}
      <motion.div
        animate={{ opacity: expanded ? 1 : 0, height: expanded ? 'auto' : 0 }}
        transition={{ duration: 0.1 }}
        className="px-3 py-2 border-t border-white/[0.06] overflow-hidden space-y-2"
      >
        {tier && tier !== 'ENTERPRISE' && (
          <button
            onClick={() => { navigate('/billing'); if (isMobile) closeMobileDrawer(); }}
            className="flex items-center gap-2 w-full px-2 py-1.5 rounded-md text-xs font-medium bg-accent-violet/15 text-accent-violet hover:bg-accent-violet/25 transition-colors"
          >
            <ArrowUpCircle className="h-3.5 w-3.5 flex-shrink-0" />
            <span>Upgrade{tier === 'FREE' ? ' to Pro' : ''}</span>
          </button>
        )}
        <UsageBadge />
      </motion.div>

      {/* Bottom health dot */}
      <div className="px-4 py-3 border-t border-white/[0.06] flex items-center gap-3">
        <span className="relative flex h-2 w-2 flex-shrink-0">
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-emerald opacity-75" />
          <span className="relative inline-flex rounded-full h-2 w-2 bg-accent-emerald" />
        </span>
        <motion.span
          animate={{ opacity: expanded ? 1 : 0 }}
          transition={{ duration: 0.1 }}
          className="text-xs text-zinc-500 whitespace-nowrap"
        >
          System healthy
        </motion.span>
      </div>
    </>
  );

  // ── Mobile: slide-out drawer overlay ───────────────────────────────────

  if (isMobile) {
    return (
      <AnimatePresence>
        {mobileSidebarOpen && (
          <>
            {/* Backdrop */}
            <motion.div
              key="sidebar-backdrop"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.2 }}
              className="fixed inset-0 z-40 bg-black/60"
              onClick={closeMobileDrawer}
            />
            {/* Drawer */}
            <motion.aside
              key="sidebar-drawer"
              initial={{ x: -280 }}
              animate={{ x: 0 }}
              exit={{ x: -280 }}
              transition={{ duration: 0.25, ease: 'easeOut' }}
              className="fixed top-0 left-0 bottom-0 z-50 w-[280px] flex flex-col bg-surface-1 border-r border-white/[0.06] overflow-hidden"
              style={{ paddingTop: 'env(safe-area-inset-top, 0px)', paddingBottom: 'env(safe-area-inset-bottom, 0px)' }}
            >
              {sidebarContent}
            </motion.aside>
          </>
        )}
      </AnimatePresence>
    );
  }

  // ── Desktop: hover-expand sidebar (unchanged behavior) ─────────────────

  return (
    <motion.aside
      animate={{ width: expanded ? 220 : 64 }}
      transition={{ duration: 0.2, ease: 'easeInOut' }}
      onMouseEnter={() => setHoverExpanded(true)}
      onMouseLeave={() => setHoverExpanded(false)}
      className="relative flex flex-col h-full bg-surface-1 border-r border-white/[0.06] overflow-hidden z-20 flex-shrink-0"
    >
      {sidebarContent}
    </motion.aside>
  );
}
