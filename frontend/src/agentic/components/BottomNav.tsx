/**
 * Agentic v2 bottom nav — 4 slots, iOS UITabBar pattern.
 *
 * Differs from v3 BottomNav in two ways:
 *   - Slot semantics: Home / Activity / Catalog / You (was Home / Runs /
 *     Insights / You). The catalog (Library + Migrations) gets a
 *     first-class slot because it's the fastest path from "I have an
 *     idea" → "agent already wrote 80% of this".
 *   - Visual treatment: glass surface that's 2px taller than v3,
 *     animated pill indicator above the active glyph + filled glyph
 *     on active.
 */
import {useLayoutEffect, useRef, useState} from 'react';
import {NavLink, useLocation} from 'react-router-dom';
import {Home, Activity, Library, User, type LucideIcon} from 'lucide-react';
import {useIsPhone} from '@/hooks/useIsMobile';
import {useAuthStore} from '@/stores/useAuthStore';

const HIDDEN_PREFIXES = [
  '/a/login',
  '/a/register',
  '/a/forgot-password',
  '/a/reset-password',
  '/a/verify-email',
];

const SLOTS: {to: string; label: string; Icon: LucideIcon}[] = [
  {to: '/a/home', label: 'Home', Icon: Home},
  {to: '/a/activity', label: 'Activity', Icon: Activity},
  {to: '/a/library', label: 'Catalog', Icon: Library},
  {to: '/a/settings', label: 'You', Icon: User},
];

export default function BottomNav() {
  const isPhone = useIsPhone();
  const user = useAuthStore((s) => s.user);
  const location = useLocation();
  const navRef = useRef<HTMLElement | null>(null);
  const slotRefs = useRef<Map<string, HTMLAnchorElement | null>>(new Map());
  const [pill, setPill] = useState<{left: number; width: number} | null>(null);

  const activeKey = (() => {
    for (const s of SLOTS) {
      if (location.pathname === s.to || location.pathname.startsWith(s.to + '/')) {
        return s.to;
      }
    }
    if (location.pathname.startsWith('/a/projects/')) return '/a/home';
    if (location.pathname.startsWith('/a/runs/')) return '/a/activity';
    if (location.pathname.startsWith('/a/migrations')) return '/a/library';
    return null;
  })();

  useLayoutEffect(() => {
    if (!activeKey) {
      setPill(null);
      return;
    }
    const nav = navRef.current;
    const slot = slotRefs.current.get(activeKey);
    if (!nav || !slot) return;
    const measure = () => {
      const nr = nav.getBoundingClientRect();
      const sr = slot.getBoundingClientRect();
      const center = sr.left - nr.left + sr.width / 2;
      setPill({left: center - 16, width: 32});
    };
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [activeKey, location.pathname]);

  if (!isPhone) return null;
  if (!user) return null;
  if (HIDDEN_PREFIXES.some((p) => location.pathname.startsWith(p))) return null;

  return (
    <nav
      ref={navRef}
      aria-label='Primary navigation'
      style={{
        position: 'fixed',
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: 40,
        display: 'flex',
        alignItems: 'stretch',
        background: 'var(--a-glass-strong)',
        backdropFilter: 'blur(18px) saturate(180%)',
        WebkitBackdropFilter: 'blur(18px) saturate(180%)',
        borderTop: '1px solid var(--a-hairline)',
        paddingBottom: 'env(safe-area-inset-bottom, 0px)',
        height: 'calc(var(--a-bottom) + env(safe-area-inset-bottom, 0px))',
      }}
    >
      {pill && (
        <span
          aria-hidden='true'
          style={{
            position: 'absolute',
            top: 4,
            left: pill.left,
            width: pill.width,
            height: 3,
            borderRadius: 9999,
            background: 'var(--a-brand)',
            transition:
              'left 260ms var(--a-spring), width 260ms var(--a-spring)',
          }}
        />
      )}
      {SLOTS.map(({to, label, Icon}) => (
        <NavLink
          key={to}
          ref={(el) => {
            if (el) slotRefs.current.set(to, el);
            else slotRefs.current.delete(to);
          }}
          to={to}
          end={to === '/a/home'}
          className='a-press'
          aria-current={activeKey === to ? 'page' : undefined}
          style={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 3,
            padding: '10px 4px 6px',
            textDecoration: 'none',
            color: activeKey === to ? 'var(--a-brand)' : 'var(--a-text-muted)',
            fontSize: 10.5,
            fontWeight: activeKey === to ? 600 : 500,
            letterSpacing: '0.02em',
            fontFamily: 'inherit',
          }}
        >
          <Icon
            size={22}
            aria-hidden='true'
            strokeWidth={activeKey === to ? 2.4 : 1.9}
            fill={activeKey === to ? 'currentColor' : 'none'}
            fillOpacity={activeKey === to ? 0.15 : 0}
          />
          <span>{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
