import { NavLink } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useState } from 'react';
import {
  LayoutDashboard, BarChart3, DollarSign, Rocket, Plug, Settings,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { NAV_ITEMS } from '@/lib/constants';

const ICON_MAP: Record<string, React.ElementType> = {
  LayoutDashboard, BarChart3, DollarSign, Rocket, Plug, Settings,
};

export default function Sidebar() {
  const [expanded, setExpanded] = useState(false);

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
      <nav className="flex-1 py-3 space-y-0.5">
        {NAV_ITEMS.map((item) => {
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
