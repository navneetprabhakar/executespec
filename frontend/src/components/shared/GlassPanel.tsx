import { cn } from '@/lib/utils';

interface GlassPanelProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode;
}

export default function GlassPanel({ children, className, ...props }: GlassPanelProps) {
  return (
    <div
      className={cn(
        'border rounded-md',
        'bg-[var(--gray-100)] border-[var(--border-default)]',
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
}
