import { Outlet, useLocation } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import Sidebar from './Sidebar';
import Header from './Header';
import CommandPalette from './CommandPalette';
import { useKeyboardShortcuts } from '@/hooks/useKeyboardShortcuts';
import Toast from '@/components/shared/Toast';
import { useUiStore } from '@/stores/useUiStore';
import ChangePasswordModal from '@/components/auth/ChangePasswordModal';

export default function AppLayout() {
  useKeyboardShortcuts();
  const location = useLocation();
  const toasts = useUiStore((s) => s.toasts);

  return (
    <div className="flex h-screen overflow-hidden gradient-mesh">
      <Sidebar />
      <div className="flex flex-col flex-1 overflow-hidden">
        <Header />
        <main className="flex-1 overflow-hidden">
          <AnimatePresence mode="wait">
            <motion.div
              key={location.pathname}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -6 }}
              transition={{ duration: 0.2, ease: 'easeOut' }}
              className="h-full"
            >
              <Outlet />
            </motion.div>
          </AnimatePresence>
        </main>
      </div>

      <CommandPalette />

      {/* Force password change modal (blocks all routes until resolved) */}
      <ChangePasswordModal />

      {/* Toast container */}
      <div className="fixed bottom-4 right-4 z-50 space-y-2">
        <AnimatePresence>
          {toasts.map((t) => (
            <Toast key={t.id} toast={t} />
          ))}
        </AnimatePresence>
      </div>
    </div>
  );
}
