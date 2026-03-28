import { cn } from '@/lib/utils';

interface GlassPanelProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode;
}

export default function GlassPanel({ children, className, ...props }: GlassPanelProps) {
  return (
    <div
      className={cn(
        'border rounded-sm',
        'bg-[var(--mc-bg-1)] border-[var(--mc-border)]',
        'shadow-mc-inset',
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
}
