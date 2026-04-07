import { cn } from '@/lib/utils';
import { useIsMobile } from '@/hooks/useIsMobile';

interface Column<T> {
  key: string;
  header: string;
  render: (item: T) => React.ReactNode;
  /** Whether to show this column in mobile card view. Default: true */
  mobileVisible?: boolean;
  className?: string;
}

interface ResponsiveTableProps<T> {
  columns: Column<T>[];
  data: T[];
  keyExtractor: (item: T) => string;
  emptyMessage?: string;
  className?: string;
  onRowClick?: (item: T) => void;
}

export default function ResponsiveTable<T>({
  columns,
  data,
  keyExtractor,
  emptyMessage = 'No data to display',
  className,
  onRowClick,
}: ResponsiveTableProps<T>) {
  const isMobile = useIsMobile();

  if (data.length === 0) {
    return (
      <div
        className={cn(
          'flex items-center justify-center py-12 text-sm text-[var(--text-secondary)]',
          className,
        )}
      >
        {emptyMessage}
      </div>
    );
  }

  // Mobile: stacked cards
  if (isMobile) {
    const visibleColumns = columns.filter(c => c.mobileVisible !== false);
    return (
      <div className={cn('flex flex-col gap-3', className)}>
        {data.map(item => (
          <div
            key={keyExtractor(item)}
            className={cn(
              'border rounded-md p-4 bg-[var(--gray-100)] border-[var(--border-default)]',
              onRowClick && 'cursor-pointer active:bg-[var(--gray-200)] transition-colors',
            )}
            onClick={onRowClick ? () => onRowClick(item) : undefined}
          >
            <div className="flex flex-col gap-2">
              {visibleColumns.map(col => (
                <div key={col.key} className="flex items-baseline justify-between gap-2">
                  <span className="text-xs font-medium text-[var(--text-secondary)] shrink-0">
                    {col.header}
                  </span>
                  <span className={cn('text-sm text-[var(--text-primary)] text-right', col.className)}>
                    {col.render(item)}
                  </span>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    );
  }

  // Desktop: standard table
  return (
    <div className={cn('overflow-x-auto', className)}>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-[var(--border-default)]">
            {columns.map(col => (
              <th
                key={col.key}
                className={cn(
                  'px-4 py-3 text-left text-xs font-medium text-[var(--text-secondary)] uppercase tracking-wider',
                  col.className,
                )}
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map(item => (
            <tr
              key={keyExtractor(item)}
              className={cn(
                'border-b border-[var(--border-default)] last:border-b-0',
                'hover:bg-[var(--gray-200)] transition-colors',
                onRowClick && 'cursor-pointer',
              )}
              onClick={onRowClick ? () => onRowClick(item) : undefined}
            >
              {columns.map(col => (
                <td
                  key={col.key}
                  className={cn('px-4 py-3 text-[var(--text-primary)]', col.className)}
                >
                  {col.render(item)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
