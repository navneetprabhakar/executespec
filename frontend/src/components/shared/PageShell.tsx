import { ReactNode } from 'react';
import { cn } from '@/lib/utils';

/**
 * v2.5.13 UX overhaul — every page ends up with the same responsive chrome:
 *   - max-w for readability (6xl is a good default; pages that need wider can override)
 *   - gutter padding that scales 16 → 24 → 32 (mobile → tablet → desktop)
 *   - consistent vertical rhythm (space-y-6 between sections)
 *   - title area with optional description + right-side action slot
 *
 * Use <PageShell> instead of one-off `<div className="p-4 sm:p-6 ...">`.
 * Eliminates dozens of copies of the same ad-hoc layout across the codebase.
 */

export type PageShellWidth = 'sm' | 'md' | 'lg' | 'xl' | '2xl' | '3xl' | '4xl' | '5xl' | '6xl' | 'full';

const WIDTH_CLASS: Record<PageShellWidth, string> = {
  sm:   'max-w-sm',
  md:   'max-w-md',
  lg:   'max-w-lg',
  xl:   'max-w-xl',
  '2xl': 'max-w-2xl',
  '3xl': 'max-w-3xl',
  '4xl': 'max-w-4xl',
  '5xl': 'max-w-5xl',
  '6xl': 'max-w-6xl',
  full: 'max-w-none',
};

interface PageShellProps {
  /** Page title — rendered as h1. Omit for chrome-less screens (e.g. the split run view). */
  title?: ReactNode;
  /** Sub-title / description under the title. */
  description?: ReactNode;
  /** Right-side actions (buttons, filters, toggles). Wraps below title on mobile. */
  actions?: ReactNode;
  /** Maximum content width. Defaults to 6xl (1152px). */
  maxWidth?: PageShellWidth;
  /** Remove the top padding (useful when a sub-tab bar should sit flush against the site header). */
  flush?: boolean;
  /** Disable the internal vertical gap between children. */
  noStack?: boolean;
  children: ReactNode;
  className?: string;
}

export default function PageShell({
  title,
  description,
  actions,
  maxWidth = '6xl',
  flush = false,
  noStack = false,
  children,
  className,
}: PageShellProps) {
  return (
    <div className={cn('h-full overflow-y-auto', className)}>
      <div
        className={cn(
          'mx-auto w-full page-gutter',
          WIDTH_CLASS[maxWidth],
          flush ? 'py-0' : 'py-4 sm:py-6 lg:py-8',
          !noStack && 'space-y-4 sm:space-y-6',
        )}
      >
        {(title || description || actions) && (
          <header className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
            <div className="min-w-0 flex-1">
              {title && (
                <h1 className="text-xl sm:text-2xl font-semibold text-primary tracking-tight truncate">
                  {title}
                </h1>
              )}
              {description && (
                <p className="mt-1 text-sm text-muted">{description}</p>
              )}
            </div>
            {actions && (
              <div className="flex flex-wrap items-center gap-2 sm:flex-shrink-0">
                {actions}
              </div>
            )}
          </header>
        )}
        {children}
      </div>
    </div>
  );
}

/**
 * Section — a titled block of content inside a PageShell.
 * Gives every page consistent section headers with optional actions + description.
 */
interface SectionProps {
  title?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  /** Render as a card (adds border + background + padding). Defaults to true. */
  card?: boolean;
}

export function Section({
  title,
  description,
  actions,
  children,
  className,
  card = true,
}: SectionProps) {
  return (
    <section
      className={cn(
        card && 'es-card p-4 sm:p-5',
        className,
      )}
    >
      {(title || actions) && (
        <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between sm:gap-3 mb-3 sm:mb-4">
          <div className="min-w-0">
            {title && (
              <h2 className="text-sm font-semibold text-primary uppercase tracking-wider">
                {title}
              </h2>
            )}
            {description && (
              <p className="mt-0.5 text-xs text-muted">{description}</p>
            )}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-2 flex-shrink-0">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}
