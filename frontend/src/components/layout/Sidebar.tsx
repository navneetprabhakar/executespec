import { NavLink, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useEffect, useMemo, useState } from 'react';
import {
  LayoutDashboard, BarChart3, DollarSign, Plug, Settings, FolderKanban,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { NAV_ITEMS } from '@/lib/constants';
import { useAuthStore, selectIsOrgAdmin, selectIsOrgMember } from '@/stores/useAuthStore';
import { useTierPermissions } from '@/hooks/useTierPermissions';
import { useRunStore } from '@/stores/useRunStore';
import * as api from '@/lib/api';
import type { ProjectDto } from '@/lib/api';

const ICON_MAP: Record<string, React.ElementType> = {
  LayoutDashboard, BarChart3, DollarSign, Plug, Settings,
};

export default function Sidebar() {
  const [expanded, setExpanded] = useState(false);
  const isAdmin = useAuthStore(selectIsOrgAdmin);
  const isOrgMember = useAuthStore(selectIsOrgMember);
  const permissions = useTierPermissions();
  const navigate = useNavigate();

  const projectFilter = useRunStore((s) => s.projectFilter);
  const setProjectFilter = useRunStore((s) => s.setProjectFilter);

  const [projects, setProjects] = useState<ProjectDto[]>([]);

  useEffect(() => {
    if (!isOrgMember) return;
    api.getProjects()
      .then((p) => setProjects(p.filter((proj) => proj.status === 'ACTIVE')))
      .catch(() => setProjects([]));
  }, [isOrgMember]);

  const visibleItems = useMemo(
    () => NAV_ITEMS.filter((item) => {
      if (item.adminOnly && !isAdmin) return false;
      // Hide nav items that require a tier permission the user doesn't have
      if (item.requiredPermission && permissions) {
        return !!permissions[item.requiredPermission as keyof typeof permissions];
      }
      // If permissions haven't loaded yet, hide permission-gated items by default
      if (item.requiredPermission && !permissions) return false;
      return true;
    }),
    [isAdmin, permissions],
  );

  const handleProjectClick = (projectId: string) => {
    // Toggle: if already selected, clear the filter
    if (projectFilter === projectId) {
      setProjectFilter(null);
    } else {
      setProjectFilter(projectId);
    }
    navigate('/');
  };

  return (
    <motion.aside
      animate={{ width: expanded ? 220 : 64 }}
      transition={{ duration: 0.2, ease: 'easeInOut' }}
      onMouseEnter={() => setExpanded(true)}
      onMouseLeave={() => setExpanded(false)}
      className="relative flex flex-col h-full bg-surface-1 border-r border-white/[0.06] overflow-hidden z-20 flex-shrink-0"
    >
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

        {/* Projects section — visible only for org members with projects */}
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
    </motion.aside>
  );
}
