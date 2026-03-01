import { cn } from '@/lib/utils';

interface GlassPanelProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode;
}

export default function GlassPanel({ children, className, ...props }: GlassPanelProps) {
  return (
    <div
      className={cn(
        'backdrop-blur-xl border rounded-xl',
        'bg-white/[0.03] border-white/[0.06]',
        'shadow-[inset_0_1px_0_0_rgba(255,255,255,0.03)]',
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
}
