import {ReactNode} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import {ArrowLeft} from '@/signal/icons/icons';

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

export function SectionHeader({children, action}: {children: ReactNode; action?: ReactNode}) {
  return (
    <div className='signal-section-h'>
      <span>{children}</span>
      {action && <span className='see-all'>{action}</span>}
    </div>
  );
}
