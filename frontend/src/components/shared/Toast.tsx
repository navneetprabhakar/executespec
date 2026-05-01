import {motion} from 'framer-motion';
import {X, CheckCircle, AlertCircle, Info} from 'lucide-react';
import type {Toast as ToastType} from '@/stores/useUiStore';
import {useUiStore} from '@/stores/useUiStore';
import {cn} from '@/lib/utils';

const ICONS = {success: CheckCircle, error: AlertCircle, info: Info};
const COLORS = {
  success: 'border-accent-blue/30 text-accent-blue',
  error: 'border-accent-red/30 text-accent-red',
  info: 'border-[var(--border-hover)] text-secondary',
};

export default function Toast({toast}: {toast: ToastType}) {
  const {removeToast} = useUiStore();
  const Icon = ICONS[toast.type];

  return (
    <motion.div
      initial={{opacity: 0, x: 40}}
      animate={{opacity: 1, x: 0}}
      exit={{opacity: 0, x: 40}}
      transition={{duration: 0.2}}
      className={cn(
        'flex items-start gap-3 px-4 py-3 rounded-md max-w-xs',
        'bg-[var(--gray-200)] border',
        COLORS[toast.type],
      )}
    >
      <Icon className='h-4 w-4 mt-0.5 flex-shrink-0' />
      <p className='text-sm text-primary flex-1'>{toast.message}</p>
      <button onClick={() => removeToast(toast.id)} className='text-muted hover:text-primary'>
        <X className='h-3.5 w-3.5' />
      </button>
    </motion.div>
  );
}
