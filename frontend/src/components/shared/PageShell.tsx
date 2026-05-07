import {ReactNode} from 'react';
import {cn} from '@/lib/utils';

/**
 * v2.5.13 UX overhaul — "Login aesthetic" rebuild.
 *
 * Every page in the app shares the same chrome:
 *   - Optional radial-gradient hero backdrop (same as AuthShell)
 *   - Eyebrow pill (uppercase mono label) + fluid h1 + optional serif-italic accent + description
 *   - Right-side actions slot that wraps below the title on mobile
 *   - Responsive gutters that scale 16 -> 24 -> 32 px
 *   - Consistent vertical rhythm (space-y-4 -> space-y-6 on sm+)
 *
 * Primitives exported:
 *   - PageShell        — the outer wrapper (handles gutter + max-width)
 *   - PageHeader       — eyebrow + h1 + description + actions (works inside PageShell)
 *   - Section          — titled card block (replaces one-off `rounded-sm border bg-[var(--gray-100)] p-4`)
 *   - Eyebrow          — uppercase mono pill used by PageHeader + section headers
 *   - StatGrid         — responsive metric tiles row
 *   - Stat             — single metric tile with label + value (+ optional trend / accent)
 */

// ── Page widths ────────────────────────────────────────────────────────────
export type PageShellWidth =
  | 'sm'
  | 'md'
  | 'lg'
  | 'xl'
  | '2xl'
  | '3xl'
  | '4xl'
  | '5xl'
  | '6xl'
  | 'full';

const WIDTH_CLASS: Record<PageShellWidth, string> = {
  sm: 'max-w-sm',
  md: 'max-w-md',
  lg: 'max-w-lg',
  xl: 'max-w-xl',
  '2xl': 'max-w-2xl',
  '3xl': 'max-w-3xl',
  '4xl': 'max-w-4xl',
  '5xl': 'max-w-5xl',
  '6xl': 'max-w-6xl',
  full: 'max-w-none',
};

// ── Eyebrow pill ───────────────────────────────────────────────────────────
type EyebrowTone = 'brand' | 'neutral' | 'success' | 'warn' | 'danger' | 'purple' | 'warm';

const EYEBROW_TONE: Record<EyebrowTone, {color: string; bg: string; border: string}> = {
  brand: {
    color: 'var(--accent-blue)',
    bg: 'color-mix(in srgb, var(--accent-blue) 12%, transparent)',
    border: 'color-mix(in srgb, var(--accent-blue) 40%, transparent)',
  },
  neutral: {color: 'var(--text-secondary)', bg: 'var(--gray-200)', border: 'var(--border-default)'},
  success: {
    color: 'var(--status-success)',
    bg: 'color-mix(in srgb, var(--status-success) 12%, transparent)',
    border: 'color-mix(in srgb, var(--status-success) 40%, transparent)',
  },
  warn: {
    color: 'var(--status-waiting)',
    bg: 'color-mix(in srgb, var(--status-waiting) 12%, transparent)',
    border: 'color-mix(in srgb, var(--status-waiting) 40%, transparent)',
  },
  danger: {
    color: 'var(--status-error)',
    bg: 'color-mix(in srgb, var(--status-error) 12%, transparent)',
    border: 'color-mix(in srgb, var(--status-error) 40%, transparent)',
  },
  purple: {
    color: 'var(--accent-harness)',
    bg: 'color-mix(in srgb, var(--accent-harness) 12%, transparent)',
    border: 'color-mix(in srgb, var(--accent-harness) 40%, transparent)',
  },
  warm: {color: '#fb923c', bg: 'rgba(251, 146, 60, 0.12)', border: 'rgba(251, 146, 60, 0.40)'},
};

export function Eyebrow({
  tone = 'brand',
  children,
  className,
}: {
  tone?: EyebrowTone;
  children: ReactNode;
  className?: string;
}) {
  const t = EYEBROW_TONE[tone];
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full font-mono text-[10.5px] font-medium uppercase tracking-[0.08em] border',
        className,
      )}
      style={{color: t.color, background: t.bg, borderColor: t.border}}
    >
      {children}
    </span>
  );
}

// ── Page header ────────────────────────────────────────────────────────────
interface PageHeaderProps {
  eyebrow?: ReactNode;
  eyebrowTone?: EyebrowTone;
  title: ReactNode;
  /** Word or phrase inside the title to render in italic serif (login aesthetic). */
  accent?: string;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
}

export function PageHeader({
  eyebrow,
  eyebrowTone = 'brand',
  title,
  accent,
  description,
  actions,
  className,
}: PageHeaderProps) {
  const renderTitle = () => {
    if (!accent || typeof title !== 'string' || !title.includes(accent)) {
      return title;
    }
    const parts = title.split(accent);
    return (
      <>
        {parts[0]}
        <span style={{color: 'var(--accent-blue-text, var(--accent-blue))'}}>{accent}</span>
        {parts.slice(1).join(accent)}
      </>
    );
  };

  return (
    <header
      className={cn(
        'flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:gap-6',
        className,
      )}
    >
      <div className='min-w-0 flex-1 space-y-2'>
        {eyebrow && <Eyebrow tone={eyebrowTone}>{eyebrow}</Eyebrow>}
        <h1 className='text-xl sm:text-2xl lg:text-[28px] font-semibold text-primary tracking-[-0.02em] leading-[1.15]'>
          {renderTitle()}
        </h1>
        {description && <p className='text-sm text-secondary max-w-2xl'>{description}</p>}
      </div>
      {actions && (
        <div className='flex flex-wrap items-center gap-2 sm:flex-shrink-0'>{actions}</div>
      )}
    </header>
  );
}

// ── Gradient backdrop (optional — same radial mix used in AuthShell) ─────
export function HeroBackdrop({className}: {className?: string}) {
  return (
    <div
      aria-hidden
      className={cn('pointer-events-none absolute inset-0 -z-10', className)}
      style={{
        background:
          'radial-gradient(ellipse 70% 50% at 15% 0%, color-mix(in srgb, var(--accent-blue) 14%, transparent), transparent 65%),' +
          'radial-gradient(ellipse 60% 45% at 92% 15%, color-mix(in srgb, var(--accent-harness) 12%, transparent), transparent 65%)',
      }}
    />
  );
}

// ── Page shell ─────────────────────────────────────────────────────────────
interface PageShellProps {
  /** Page title — rendered as h1 through PageHeader. Omit for chrome-less screens. */
  title?: ReactNode;
  /** Word or phrase inside the title to render in italic serif. */
  accent?: string;
  /** Sub-title / description under the title. */
  description?: ReactNode;
  /** Eyebrow pill above the title. */
  eyebrow?: ReactNode;
  eyebrowTone?: EyebrowTone;
  /** Right-side actions (buttons, filters, toggles). */
  actions?: ReactNode;
  /** Maximum content width. Defaults to 6xl (1152px). */
  maxWidth?: PageShellWidth;
  /** Render the radial-gradient hero backdrop behind the header. */
  hero?: boolean;
  /** Remove the top padding (useful when a sub-tab bar sits flush against the site header). */
  flush?: boolean;
  /** Disable the internal vertical gap between children. */
  noStack?: boolean;
  children: ReactNode;
  className?: string;
}

export default function PageShell({
  title,
  accent,
  description,
  eyebrow,
  eyebrowTone = 'brand',
  actions,
  maxWidth = '6xl',
  hero = false,
  flush = false,
  noStack = false,
  children,
  className,
}: PageShellProps) {
  const hasHeader = !!(title || description || eyebrow || actions);
  return (
    <div className={cn('relative h-full overflow-y-auto', className)}>
      {hero && <HeroBackdrop />}
      <div
        className={cn(
          'relative mx-auto w-full page-gutter',
          WIDTH_CLASS[maxWidth],
          flush ? 'py-0' : 'py-5 sm:py-7 lg:py-10',
          !noStack && 'space-y-5 sm:space-y-7',
        )}
      >
        {hasHeader && (
          <PageHeader
            eyebrow={eyebrow}
            eyebrowTone={eyebrowTone}
            title={title as ReactNode}
            accent={accent}
            description={description}
            actions={actions}
          />
        )}
        {children}
      </div>
    </div>
  );
}

// ── Section card ───────────────────────────────────────────────────────────
interface SectionProps {
  title?: ReactNode;
  eyebrow?: ReactNode;
  eyebrowTone?: EyebrowTone;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  /** Render as a card (adds border + background + padding). Defaults to true. */
  card?: boolean;
  /** Compact padding variant for dense sections. */
  dense?: boolean;
}

export function Section({
  title,
  eyebrow,
  eyebrowTone = 'brand',
  description,
  actions,
  children,
  className,
  card = true,
  dense = false,
}: SectionProps) {
  return (
    <section
      className={cn(
        card && [
          'rounded-lg border border-[var(--border-default)] bg-[var(--gray-100)]',
          dense ? 'p-3 sm:p-4' : 'p-4 sm:p-5',
        ],
        className,
      )}
    >
      {(title || eyebrow || actions || description) && (
        <div className='flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between sm:gap-4 mb-3 sm:mb-4'>
          <div className='min-w-0 space-y-1'>
            {eyebrow && <Eyebrow tone={eyebrowTone}>{eyebrow}</Eyebrow>}
            {title && (
              <h2 className='text-[13px] sm:text-sm font-semibold text-primary uppercase tracking-[0.06em]'>
                {title}
              </h2>
            )}
            {description && <p className='text-xs text-muted max-w-xl'>{description}</p>}
          </div>
          {actions && (
            <div className='flex flex-wrap items-center gap-2 flex-shrink-0'>{actions}</div>
          )}
        </div>
      )}
      {children}
    </section>
  );
}

// ── Stats ──────────────────────────────────────────────────────────────────
export function StatGrid({
  cols = 4,
  children,
  className,
}: {
  cols?: 2 | 3 | 4 | 6;
  children: ReactNode;
  className?: string;
}) {
  const gridClass =
    cols === 2
      ? 'grid-cols-1 sm:grid-cols-2'
      : cols === 3
        ? 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-3'
        : cols === 4
          ? 'grid-cols-2 sm:grid-cols-2 lg:grid-cols-4'
          : 'grid-cols-2 sm:grid-cols-3 lg:grid-cols-6';
  return <div className={cn('grid gap-3 sm:gap-4', gridClass, className)}>{children}</div>;
}

interface StatProps {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  tone?: 'neutral' | 'brand' | 'success' | 'warn' | 'danger' | 'purple';
  pulse?: boolean;
}

const STAT_TONE: Record<NonNullable<StatProps['tone']>, string> = {
  neutral: 'var(--text-primary)',
  brand: 'var(--accent-blue)',
  success: 'var(--status-success)',
  warn: 'var(--status-waiting)',
  danger: 'var(--status-error)',
  purple: 'var(--accent-harness)',
};

export function Stat({label, value, hint, tone = 'neutral', pulse = false}: StatProps) {
  return (
    <div className='relative rounded-lg border border-[var(--border-default)] bg-[var(--gray-100)] p-3 sm:p-4 overflow-hidden'>
      <p className='font-mono text-[10.5px] uppercase tracking-[0.08em] text-muted'>{label}</p>
      <p
        className={cn(
          'mt-1 text-2xl sm:text-[28px] font-semibold tracking-[-0.02em] font-mono tabular-nums leading-none',
          pulse && 'animate-pulse',
        )}
        style={{color: STAT_TONE[tone]}}
      >
        {value}
      </p>
      {hint && <p className='mt-1.5 text-[11px] text-muted'>{hint}</p>}
    </div>
  );
}
