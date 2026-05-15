import {ReactNode} from 'react';
import {Link} from 'react-router-dom';
import {Chevron} from '@/signal/icons/icons';

// ─────────── Feed (scrollable phone body) ───────────

export function Feed({children, padBottom = 110}: {children: ReactNode; padBottom?: number}) {
  return (
    <div className='signal-feed' style={{paddingBottom: padBottom}}>
      {children}
    </div>
  );
}

// ─────────── Card ───────────

export function Card({
  children,
  to,
  className = '',
  style,
}: {
  children: ReactNode;
  to?: string;
  className?: string;
  style?: React.CSSProperties;
}) {
  const klass = `signal-card${to ? ' signal-card-link' : ''} ${className}`.trim();
  if (to) {
    return (
      <Link to={to} className={klass} style={style}>
        {children}
      </Link>
    );
  }
  return (
    <div className={klass} style={style}>
      {children}
    </div>
  );
}

// ─────────── Row ───────────

export type RowDot = 'mint' | 'coral' | 'signal' | 'lavender' | 'ocean' | 'live' | 'mute';
export type RowTrailing = 'good' | 'warn' | 'fail' | 'default';

export function Row({
  dot = 'mute',
  title,
  meta,
  trailing,
  trailingLabel,
  trailingKind = 'default',
  to,
  showChev,
}: {
  dot?: RowDot;
  title: ReactNode;
  meta?: ReactNode;
  trailing?: ReactNode;
  trailingLabel?: ReactNode;
  trailingKind?: RowTrailing;
  to?: string;
  showChev?: boolean;
}) {
  const inner = (
    <>
      <div className={`pill-dot ${dot === 'mute' ? '' : dot}`} />
      <div>
        <div className='row-title'>{title}</div>
        {meta && <div className='row-meta'>{meta}</div>}
      </div>
      {showChev ? (
        <Chevron className='chev' />
      ) : trailing != null ? (
        <div className={`trailing ${trailingKind !== 'default' ? trailingKind : ''}`}>
          {trailing}
          {trailingLabel && <span className='lbl'>{trailingLabel}</span>}
        </div>
      ) : null}
    </>
  );
  if (to) {
    return (
      <Link to={to} className='signal-row'>
        {inner}
      </Link>
    );
  }
  return <div className='signal-row'>{inner}</div>;
}

// ─────────── Chip ───────────

type ChipKind = 'default' | 'active' | 'signal' | 'mint' | 'coral' | 'lavender';

export function Chip({
  children,
  kind = 'default',
  onClick,
}: {
  children: ReactNode;
  kind?: ChipKind;
  onClick?: () => void;
}) {
  const klass = `signal-chip ${kind !== 'default' ? kind : ''}`.trim();
  return (
    <button type='button' className={klass} onClick={onClick}>
      {children}
    </button>
  );
}

// ─────────── StatusPill ───────────

export function StatusPill({
  kind,
  children,
}: {
  kind?: 'live' | 'shipped' | 'needs' | 'failed';
  children: ReactNode;
}) {
  const klass = `signal-status-pill ${kind ?? ''}`.trim();
  return <span className={klass}>{children}</span>;
}

// ─────────── Button ───────────

type BtnKind = 'primary' | 'secondary' | 'mint' | 'coral' | 'ghost';

export function Button({
  children,
  to,
  onClick,
  kind = 'primary',
  block = false,
  sm = false,
  className = '',
  style,
  disabled = false,
  type = 'button',
}: {
  children: ReactNode;
  to?: string;
  onClick?: () => void;
  kind?: BtnKind;
  block?: boolean;
  sm?: boolean;
  className?: string;
  style?: React.CSSProperties;
  disabled?: boolean;
  type?: 'button' | 'submit' | 'reset';
}) {
  const klass = `signal-btn ${kind} ${block ? 'block' : ''} ${sm ? 'sm' : ''}${
    disabled ? ' is-disabled' : ''
  } ${className}`.trim();
  if (to && !disabled) {
    return (
      <Link to={to} className={klass} style={style}>
        {children}
      </Link>
    );
  }
  return (
    <button
      type={type}
      className={klass}
      onClick={onClick}
      style={style}
      disabled={disabled}
      aria-disabled={disabled || undefined}
    >
      {children}
    </button>
  );
}

// ─────────── KvList ───────────

export function KvList({pairs}: {pairs: Array<{k: ReactNode; v: ReactNode}>}) {
  return (
    <div className='signal-kv-list'>
      {pairs.map((p, i) => (
        <div key={i} className='kv'>
          <span className='k'>{p.k}</span>
          <span className='v'>{p.v}</span>
        </div>
      ))}
    </div>
  );
}

// ─────────── TabsStrip ───────────

export function TabsStrip({
  items,
}: {
  items: Array<{label: ReactNode; to?: string; active?: boolean; onClick?: () => void}>;
}) {
  return (
    <div className='signal-tabs-strip'>
      {items.map((it, i) =>
        it.to ? (
          <Link key={i} to={it.to} className={it.active ? 'active' : ''}>
            {it.label}
          </Link>
        ) : (
          <button
            key={i}
            type='button'
            className={it.active ? 'active' : ''}
            onClick={it.onClick}
          >
            {it.label}
          </button>
        ),
      )}
    </div>
  );
}
