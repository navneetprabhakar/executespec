/**
 * iOS-style grouped list.
 *
 *   <ListGroup label="Account">
 *     <ListGroup.Row title="Email" detail="me@example.com" />
 *     <ListGroup.Row title="Password" onClick={...} chevron />
 *   </ListGroup>
 *
 * Rows can take a leading icon, title + optional detail, optional trailing
 * value or control, and an optional chevron when the row is navigational.
 * Pressing a row scales it 0.99 for haptic-feel feedback.
 */
import type {ReactNode} from 'react';
import {ChevronRight} from 'lucide-react';

interface GroupProps {
  label?: string;
  footer?: ReactNode;
  children: ReactNode;
}

function ListGroup({label, footer, children}: GroupProps) {
  return (
    <section style={{marginBottom: 20}}>
      {label && <div className='a-list-group__label'>{label}</div>}
      <div className='a-list'>{children}</div>
      {footer && (
        <p
          style={{
            margin: '8px 14px 0',
            fontSize: 'var(--a-text-xs)',
            color: 'var(--a-text-subtle)',
            lineHeight: 1.4,
          }}
        >
          {footer}
        </p>
      )}
    </section>
  );
}

interface RowProps {
  /** Optional 20px leading icon (Lucide). */
  icon?: ReactNode;
  /** Optional coloured background behind the icon (iOS Settings style). */
  iconBg?: string;
  /** Primary label. */
  title: ReactNode;
  /** Secondary line under the title. */
  detail?: ReactNode;
  /** Right-side value or control. */
  trailing?: ReactNode;
  /** Show chevron > on the right (only when row is navigational). */
  chevron?: boolean;
  /** Renders the row as a tappable button (vs static div). */
  onClick?: () => void;
  /** Render as <a> link with href. */
  href?: string;
  /** Make the title text + chevron destructive-red. */
  destructive?: boolean;
}

function Row({
  icon,
  iconBg,
  title,
  detail,
  trailing,
  chevron,
  onClick,
  href,
  destructive,
}: RowProps) {
  const inner = (
    <>
      {icon != null && (
        <span
          aria-hidden='true'
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 28,
            height: 28,
            borderRadius: 8,
            background: iconBg ?? 'var(--a-surface-up)',
            color: '#fff',
            flexShrink: 0,
          }}
        >
          {icon}
        </span>
      )}
      <span
        style={{
          flex: 1,
          minWidth: 0,
          display: 'flex',
          flexDirection: 'column',
          gap: 1,
        }}
      >
        <span
          style={{
            color: destructive ? 'var(--a-fail)' : 'var(--a-text)',
            fontSize: 'var(--a-text-sm)',
            fontWeight: 500,
          }}
        >
          {title}
        </span>
        {detail != null && (
          <span
            style={{
              color: 'var(--a-text-subtle)',
              fontSize: 'var(--a-text-xs)',
            }}
          >
            {detail}
          </span>
        )}
      </span>
      {trailing != null && (
        <span
          style={{
            color: 'var(--a-text-muted)',
            fontSize: 'var(--a-text-sm)',
            flexShrink: 0,
          }}
        >
          {trailing}
        </span>
      )}
      {chevron && (
        <ChevronRight
          size={16}
          aria-hidden='true'
          className='a-list-row__chevron'
        />
      )}
    </>
  );

  if (href) {
    return (
      <a
        href={href}
        className='a-list-row a-press'
        style={{textDecoration: 'none'}}
      >
        {inner}
      </a>
    );
  }
  if (onClick) {
    return (
      <button type='button' onClick={onClick} className='a-list-row a-press'>
        {inner}
      </button>
    );
  }
  return <div className='a-list-row' style={{cursor: 'default'}}>{inner}</div>;
}

ListGroup.Row = Row;
export default ListGroup;
