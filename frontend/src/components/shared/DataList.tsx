/**
 * 2026-05-14 (mobile-first redesign, Track B) — DataList primitive.
 *
 * Best-in-class mobile apps (Linear, Stripe, GitHub Mobile) never render a
 * desktop-density `<table>` on phones — they collapse to a card list where
 * each row stacks its columns vertically and exposes only the columns
 * worth seeing. This primitive does the same: takes a single `columns +
 * rows` description and renders:
 *
 *   - `<table>` at ≥ md (768 px) — the existing dense desktop layout.
 *   - `<ul>` of cards under md — each row is a tap-friendly 56-72 px card
 *     with column labels rendered above values. Rows can opt-in to a
 *     `summary` projection (one or two columns surfaced on the card
 *     header) and a `details` projection (everything else, shown below).
 *
 * Usage:
 *   <DataList
 *     columns={[
 *       {key: 'name', header: 'Name', cell: r => r.name},
 *       {key: 'email', header: 'Email', cell: r => r.email, summary: true},
 *       {key: 'role', header: 'Role', cell: r => r.role},
 *       {key: 'status', header: 'Status', cell: r => <Pill>{r.status}</Pill>, summary: true, align: 'right'},
 *     ]}
 *     rows={users}
 *     rowKey={(r) => r.id}
 *     onRowClick={(r) => navigate(`/users/${r.id}`)}
 *   />
 *
 * Columns flagged `summary: true` appear in the card header; everything
 * else stacks under it on phone. On desktop the column array drives the
 * `<thead>` / `<tbody>` directly.
 */
import {Fragment, type ReactNode} from 'react';
import {cn} from '@/lib/utils';

export interface DataListColumn<R> {
  key: string;
  header: ReactNode;
  cell: (row: R, index: number) => ReactNode;
  /** Show this column in the card header on mobile (else: in the body block). */
  summary?: boolean;
  /** Desktop table-cell alignment. Default 'left'. */
  align?: 'left' | 'right' | 'center';
  /** Hide on phones entirely (the column will only render in the desktop table). */
  hideOnPhone?: boolean;
  /** Optional extra Tailwind classes for the <th>/<td>. */
  className?: string;
  /** Desktop-only min-width hint passed through to <th>/<td> style. */
  minWidth?: string | number;
}

interface DataListProps<R> {
  columns: DataListColumn<R>[];
  rows: R[];
  rowKey: (row: R, index: number) => string | number;
  onRowClick?: (row: R, index: number) => void;
  /** ARIA label for the table / list. */
  ariaLabel?: string;
  /** Empty-state node shown when rows.length === 0. */
  empty?: ReactNode;
  /** Density of the desktop table cells. Default 'normal'. */
  dense?: boolean;
  /** Extra className for the outer wrapper. */
  className?: string;
}

export default function DataList<R>({
  columns,
  rows,
  rowKey,
  onRowClick,
  ariaLabel,
  empty,
  dense = false,
  className,
}: DataListProps<R>) {
  if (rows.length === 0 && empty) {
    return <div className={className}>{empty}</div>;
  }

  return (
    <div className={className}>
      {/* ── Mobile card list (<md) ─────────────────────────────────────── */}
      <ul
        aria-label={ariaLabel}
        className='md:hidden divide-y divide-[var(--border-default)] border border-[var(--border-default)] rounded-lg overflow-hidden'
      >
        {rows.map((row, i) => {
          const summary = columns.filter((c) => c.summary && !c.hideOnPhone);
          const details = columns.filter((c) => !c.summary && !c.hideOnPhone);
          const Tag = onRowClick ? 'button' : 'div';
          return (
            <li key={rowKey(row, i)}>
              <Tag
                type={onRowClick ? 'button' : undefined}
                onClick={onRowClick ? () => onRowClick(row, i) : undefined}
                className={cn(
                  'w-full text-left p-3 flex flex-col gap-1.5 bg-[var(--gray-100)]',
                  onRowClick && 'active:bg-[var(--gray-200)] cursor-pointer min-h-[56px]',
                )}
              >
                {summary.length > 0 && (
                  <div className='flex items-start justify-between gap-2 flex-wrap'>
                    {summary.map((c, ci) => (
                      <div
                        key={c.key}
                        className={cn(
                          'min-w-0 text-sm',
                          ci === 0 ? 'font-medium text-primary flex-1' : 'text-secondary',
                          c.align === 'right' && 'text-right',
                          c.className,
                        )}
                      >
                        {c.cell(row, i)}
                      </div>
                    ))}
                  </div>
                )}
                {details.length > 0 && (
                  <dl className='grid grid-cols-2 gap-x-3 gap-y-1 text-xs text-muted mt-1'>
                    {details.map((c) => (
                      <Fragment key={c.key}>
                        <dt className='text-[10px] uppercase tracking-wider text-muted'>
                          {c.header}
                        </dt>
                        <dd
                          className={cn(
                            'text-secondary',
                            c.align === 'right' && 'text-right',
                            c.className,
                          )}
                        >
                          {c.cell(row, i)}
                        </dd>
                      </Fragment>
                    ))}
                  </dl>
                )}
              </Tag>
            </li>
          );
        })}
      </ul>

      {/* ── Desktop table (≥md) ────────────────────────────────────────── */}
      <div className='hidden md:block overflow-x-auto'>
        <table aria-label={ariaLabel} className='w-full text-sm'>
          <thead className='bg-[var(--gray-200)] text-muted'>
            <tr>
              {columns.map((c) => (
                <th
                  key={c.key}
                  className={cn(
                    dense ? 'px-3 py-2' : 'px-4 py-3',
                    'text-left font-medium',
                    c.align === 'right' && 'text-right',
                    c.align === 'center' && 'text-center',
                    c.className,
                  )}
                  style={c.minWidth ? {minWidth: c.minWidth} : undefined}
                >
                  {c.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className='divide-y divide-[var(--border-default)]'>
            {rows.map((row, i) => {
              const RowEl = onRowClick ? 'tr' : 'tr';
              return (
                <RowEl
                  key={rowKey(row, i)}
                  onClick={onRowClick ? () => onRowClick(row, i) : undefined}
                  className={cn(onRowClick && 'cursor-pointer hover:bg-[var(--gray-200)]')}
                >
                  {columns.map((c) => (
                    <td
                      key={c.key}
                      className={cn(
                        dense ? 'px-3 py-2' : 'px-4 py-3',
                        c.align === 'right' && 'text-right',
                        c.align === 'center' && 'text-center',
                        c.className,
                      )}
                    >
                      {c.cell(row, i)}
                    </td>
                  ))}
                </RowEl>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
