import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useIsMobile } from '@/hooks/useIsMobile';

interface ResponsiveModalProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  children: React.ReactNode;
  className?: string;
  /** Tailwind max-width class, e.g. 'max-w-xl'. Default: 'max-w-2xl' */
  maxWidth?: string;
}

const desktopVariants = {
  hidden: { opacity: 0, scale: 0.95 },
  visible: { opacity: 1, scale: 1 },
  exit: { opacity: 0, scale: 0.95 },
};

const mobileVariants = {
  hidden: { y: '100%' },
  visible: { y: 0 },
  exit: { y: '100%' },
};

const backdropVariants = {
  hidden: { opacity: 0 },
  visible: { opacity: 1 },
  exit: { opacity: 0 },
};

export default function ResponsiveModal({
  open,
  onClose,
  title,
  children,
  className,
  maxWidth = 'max-w-2xl',
}: ResponsiveModalProps) {
  const isMobile = useIsMobile();

  // Escape key closes
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, onClose]);

  // Body scroll lock
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  return createPortal(
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          {/* Backdrop */}
          <motion.div
            className="absolute inset-0 bg-black/50 backdrop-blur-sm"
            variants={backdropVariants}
            initial="hidden"
            animate="visible"
            exit="exit"
            transition={{ duration: 0.2 }}
            onClick={isMobile ? undefined : onClose}
          />

          {/* Panel */}
          <motion.div
            className={cn(
              'relative z-10 flex flex-col bg-[var(--gray-100)] border border-[var(--border-default)] overflow-hidden',
              isMobile
                ? 'fixed inset-0'
                : `w-full ${maxWidth} mx-4 rounded-lg shadow-xl max-h-[90vh]`,
              className,
            )}
            variants={isMobile ? mobileVariants : desktopVariants}
            initial="hidden"
            animate="visible"
            exit="exit"
            transition={{ duration: 0.2, ease: 'easeOut' }}
          >
            {/* Header */}
            {(title || true) && (
              <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border-default)] shrink-0">
                {title && (
                  <h2 className="text-lg font-semibold text-[var(--text-primary)] truncate pr-2">
                    {title}
                  </h2>
                )}
                <button
                  onClick={onClose}
                  className="ml-auto p-1.5 rounded-md text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--gray-200)] transition-colors"
                  aria-label="Close"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
            )}

            {/* Body */}
            <div className="flex-1 overflow-y-auto p-4">
              {children}
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  );
}
