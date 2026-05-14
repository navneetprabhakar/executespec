/**
 * 2026-05-14 UX overhaul — Card primitive.
 *
 * The Apple-HIG-aligned baseline card. Provides the rest/hover/press
 * visual treatment uniformly across the app. Replaces ~15 ad-hoc card
 * implementations that each rolled their own border/radius/shadow combo
 * (P1-8 in docs/design/ux-assessment-2026-05-14.md).
 *
 * Variants:
 *   - default      = decorative card (no hover state)
 *   - interactive  = clickable card (hover-lift + active-press)
 *   - inset        = sub-card inside a card (no border, sunken surface)
 *
 * Padding presets (matches mobile-first --space-* scale):
 *   - sm = space-3 (12px) — chip-like cards
 *   - md = space-4 (16px) — default
 *   - lg = space-5 (20px) — hero / dashboard heads
 *
 * Usage:
 *   <Card variant="interactive" onClick={...}>...</Card>
 *   <Card padding="lg">...</Card>
 *   <Card as="a" href="/runs/123">...</Card>
 *
 * The `as` prop lets you render as <a>, <article>, <button>, etc. without
 * losing the styling. Required for SEO + a11y on link-cards.
 */
import {
  type CSSProperties,
  type ElementType,
  type ComponentPropsWithoutRef,
  type ReactNode,
  forwardRef,
} from 'react';

type CardVariant = 'default' | 'interactive' | 'inset';
type CardPadding = 'none' | 'sm' | 'md' | 'lg';

type CardOwnProps<T extends ElementType = 'div'> = {
  as?: T;
  variant?: CardVariant;
  padding?: CardPadding;
  children?: ReactNode;
  className?: string;
  style?: CSSProperties;
};

type CardProps<T extends ElementType = 'div'> = CardOwnProps<T> &
  Omit<ComponentPropsWithoutRef<T>, keyof CardOwnProps<T>>;

const PADDING_PX: Record<CardPadding, string> = {
  none: '0',
  sm: 'var(--space-3)',
  md: 'var(--space-4)',
  lg: 'var(--space-5)',
};

function CardImpl<T extends ElementType = 'div'>(
  {as, variant = 'default', padding = 'md', className, style, children, ...rest}: CardProps<T>,
  ref: React.Ref<Element>,
) {
  const Component = (as || 'div') as ElementType;
  const isInteractive = variant === 'interactive';
  const isInset = variant === 'inset';

  const baseClass = ['surface-card', isInteractive ? 'is-interactive' : '', className || '']
    .filter(Boolean)
    .join(' ');

  const composedStyle: CSSProperties = {
    padding: PADDING_PX[padding],
    ...(isInset
      ? {
          background: 'var(--surface-sunken)',
          border: '1px solid var(--hairline)',
          boxShadow: 'none',
        }
      : null),
    ...(style || {}),
  };

  return (
    <Component ref={ref} className={baseClass} style={composedStyle} {...rest}>
      {children}
    </Component>
  );
}

const Card = forwardRef(CardImpl) as <T extends ElementType = 'div'>(
  props: CardProps<T> & {ref?: React.Ref<Element>},
) => ReturnType<typeof CardImpl>;

export default Card;
