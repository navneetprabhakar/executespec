import {ReactNode} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import {ArrowLeft, HomeIcon, Check, Plus, Chat, More} from '@/signal/icons/icons';

// ─────────── TopBar ───────────

interface TopBarProps {
  title: string;
  sub?: string;
  back?: boolean;
  backTo?: string;
  actions?: ReactNode;
  avatar?: string;
}

export function TopBar({title, sub, back, backTo, actions, avatar}: TopBarProps) {
  const nav = useNavigate();
  const onBack = () => {
    if (backTo) nav(backTo);
    else nav(-1);
  };
  return (
    <div className='signal-topbar'>
      {back && (
        <button className='signal-back-btn' onClick={onBack} aria-label='Go back'>
          <ArrowLeft size={16} />
        </button>
      )}
      <h1>
        {title}
        {sub && <span className='sub'>{sub}</span>}
      </h1>
      {actions}
      {avatar && (
        <Link to='/signal/account' className='signal-avatar'>
          {avatar}
        </Link>
      )}
    </div>
  );
}

// ─────────── TabBar (bottom) ───────────

interface TabBarProps {
  active?: 'home' | 'runs' | 'chat' | 'more';
}

export function TabBar({active}: TabBarProps) {
  const tabs = [
    {id: 'home' as const, to: '/signal/home', label: 'Today', icon: <HomeIcon />},
    {id: 'runs' as const, to: '/signal/runs', label: 'Runs', icon: <Check />},
    {id: 'fab' as const, to: '/signal/compose', label: 'New', icon: <Plus />},
    {id: 'chat' as const, to: '/signal/projects/checkout-api/chat', label: 'Chat', icon: <Chat />},
    {id: 'more' as const, to: '/signal/more', label: 'More', icon: <More />},
  ];
  return (
    <nav className='signal-tabbar'>
      {tabs.map((t) => (
        <Link
          key={t.id}
          to={t.to}
          className={`signal-tab ${t.id === 'fab' ? 'fab' : ''} ${active === t.id ? 'active' : ''}`}
        >
          {t.icon}
          <span className='tab-label'>{t.label}</span>
        </Link>
      ))}
    </nav>
  );
}

// ─────────── SectionHeader ───────────

export function SectionHeader({children, action}: {children: ReactNode; action?: ReactNode}) {
  return (
    <div className='signal-section-h'>
      <span>{children}</span>
      {action && <span className='see-all'>{action}</span>}
    </div>
  );
}

// ─────────── IconButton ───────────

export function IconButton({
  children,
  to,
  onClick,
  badge,
  ariaLabel,
}: {
  children: ReactNode;
  to?: string;
  onClick?: () => void;
  badge?: boolean;
  ariaLabel?: string;
}) {
  if (to) {
    return (
      <Link to={to} className='signal-icon-btn' aria-label={ariaLabel}>
        {children}
        {badge && <span className='badge' />}
      </Link>
    );
  }
  return (
    <button className='signal-icon-btn' onClick={onClick} aria-label={ariaLabel}>
      {children}
      {badge && <span className='badge' />}
    </button>
  );
}
