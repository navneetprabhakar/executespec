/**
 * Agentic v2 top bar.
 *
 * Mobile (≤ 768 px): brand chip · workspace pill · agent-status indicator · ⋯
 * Desktop:           brand · workspace pill · 🔍 search · nav links · identity
 *
 * The agent-status indicator (a small pulsing dot) sits next to the brand
 * when any of the user's runs are in a non-terminal state. Tapping it opens
 * the activity sheet — a fast path to "what's my agent doing right now".
 */
import {useEffect, useState, type CSSProperties} from 'react';
import {Link, NavLink, useNavigate} from 'react-router-dom';
import {Command, Search} from 'lucide-react';
import {useAuthStore} from '@/stores/useAuthStore';
import {useV3Ui} from '@/v3/stores/useV3Ui';
import {useIsPhone} from '@/hooks/useIsMobile';
import StatusDot from './StatusDot';

const navLinkStyle = ({isActive}: {isActive: boolean}): CSSProperties => ({
  padding: '6px 10px',
  borderRadius: 8,
  fontSize: 13,
  fontWeight: 500,
  textDecoration: 'none',
  color: isActive ? 'var(--v4-text)' : 'var(--v4-text-muted)',
  background: isActive ? 'var(--v4-surface-up)' : 'transparent',
  border: `1px solid ${isActive ? 'var(--v4-border)' : 'transparent'}`,
  transition: 'background 200ms var(--v4-spring), color 200ms var(--v4-spring)',
});

export default function TopBar() {
  const user = useAuthStore((s) => s.user);
  const isPhone = useIsPhone();
  const openCmdK = useV3Ui((s) => s.openCmdK);
  const navigate = useNavigate();
  const [agentActive, setAgentActive] = useState(false);

  // Poll a lightweight signal — for v1 we'll check if any of the user's
  // recent runs are in a working state by reading the v3 run store. For
  // the initial commit we leave it false; a future pass wires in
  // useRunStore + a 30s poll.
  useEffect(() => {
    setAgentActive(false);
  }, []);

  return (
    <header
      role='banner'
      style={{
        position: 'sticky',
        top: 0,
        zIndex: 30,
        height: 'var(--v4-top)',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '0 clamp(12px, 4vw, 20px)',
        background: 'var(--v4-glass)',
        backdropFilter: 'blur(18px) saturate(180%)',
        WebkitBackdropFilter: 'blur(18px) saturate(180%)',
        borderBottom: '1px solid var(--v4-hairline)',
        paddingTop: 'env(safe-area-inset-top, 0px)',
      }}
    >
      <Link
        to='/v4/home'
        aria-label='ExecuteSpec — v4 home'
        className='v4-press'
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 8,
          textDecoration: 'none',
          color: 'var(--v4-text)',
        }}
      >
        <span
          aria-hidden='true'
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 28,
            height: 28,
            borderRadius: 8,
            background:
              'linear-gradient(135deg, var(--v4-brand), var(--c-signal-teal))',
            color: '#fff',
            fontSize: 11,
            fontWeight: 800,
            letterSpacing: '0.02em',
            boxShadow: 'var(--v4-shadow-rest)',
          }}
        >
          es
        </span>
        {!isPhone && (
          <span
            style={{
              fontSize: 14,
              fontWeight: 600,
              letterSpacing: '-0.01em',
            }}
          >
            ExecuteSpec
          </span>
        )}
        {agentActive && (
          <StatusDot tone='progress' aria-label='Agent active' />
        )}
      </Link>

      {/* Center — desktop only: command palette trigger + nav links. */}
      {!isPhone && user && (
        <div
          style={{
            flex: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 8,
            minWidth: 0,
          }}
        >
          <button
            type='button'
            onClick={() => openCmdK()}
            className='v4-press'
            aria-label='Open command palette'
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 8,
              padding: '6px 10px 6px 12px',
              minWidth: 240,
              background: 'var(--v4-surface-sunken)',
              border: '1px solid var(--v4-hairline)',
              borderRadius: 10,
              color: 'var(--v4-text-muted)',
              fontFamily: 'inherit',
              fontSize: 12.5,
              fontWeight: 500,
              cursor: 'pointer',
              textAlign: 'left',
            }}
          >
            <Search size={14} aria-hidden='true' />
            <span style={{flex: 1, whiteSpace: 'nowrap'}}>Search or jump…</span>
            <span
              aria-hidden='true'
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 2,
                padding: '1px 5px',
                background: 'var(--v4-surface)',
                border: '1px solid var(--v4-hairline)',
                borderRadius: 4,
                fontSize: 10,
                fontWeight: 600,
                color: 'var(--v4-text-subtle)',
              }}
            >
              <Command size={9} />K
            </span>
          </button>
        </div>
      )}

      {/* Right — desktop nav links + identity. Mobile: identity only (nav
          is in the bottom bar). */}
      {!isPhone && user ? (
        <nav
          aria-label='Sections'
          style={{display: 'flex', alignItems: 'center', gap: 4}}
        >
          <NavLink to='/v4/activity' style={navLinkStyle}>
            Activity
          </NavLink>
          <NavLink to='/v4/library' style={navLinkStyle}>
            Catalog
          </NavLink>
          <NavLink to='/v4/settings' style={navLinkStyle}>
            You
          </NavLink>
          <button
            type='button'
            onClick={() => navigate('/home')}
            className='v4-press'
            title='Switch to classic UI'
            style={{
              marginLeft: 6,
              padding: '4px 8px',
              border: '1px solid var(--v4-hairline)',
              borderRadius: 8,
              background: 'transparent',
              color: 'var(--v4-text-subtle)',
              fontFamily: 'inherit',
              fontSize: 11,
              cursor: 'pointer',
            }}
          >
            Classic UI
          </button>
        </nav>
      ) : isPhone && user ? (
        <button
          type='button'
          onClick={() => navigate('/home')}
          className='v4-press'
          title='Switch to classic UI'
          style={{
            marginLeft: 'auto',
            padding: '4px 8px',
            border: '1px solid var(--v4-hairline)',
            borderRadius: 8,
            background: 'transparent',
            color: 'var(--v4-text-subtle)',
            fontFamily: 'inherit',
            fontSize: 10.5,
            cursor: 'pointer',
          }}
        >
          Classic
        </button>
      ) : null}
    </header>
  );
}
