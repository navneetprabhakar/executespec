import { Rocket } from 'lucide-react';

interface EmptyStateProps {
  title?: string;
  description?: string;
  action?: React.ReactNode;
}

export default function EmptyState({
  title = 'Nothing here yet',
  description = 'Get started by creating something new.',
  action,
}: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center py-20 gap-4 text-center">
      <div className="rounded-md bg-[var(--gray-200)] p-4 border border-[var(--border-default)]">
        <Rocket className="h-8 w-8 text-muted" />
      </div>
      <div>
        <p className="text-base font-semibold text-primary">{title}</p>
        <p className="text-sm text-muted mt-1">{description}</p>
      </div>
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}
