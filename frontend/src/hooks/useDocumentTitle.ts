import {useEffect} from 'react';

/**
 * C2 (UI/UX review 2026-06-11) — per-surface browser-tab titles. The shells
 * (IdeWorkspace / ConsoleShell) set a route-derived default for every route;
 * detail surfaces call this with a richer name once their data loads (e.g.
 * `useDocumentTitle(run?.runTitle ?? null)`), which wins over the default.
 *
 * Pass null/undefined while data is loading — the hook leaves the current
 * title untouched until a real value arrives, so there's no "null · ExecuteSpec"
 * flash. Title format: "<thing> · ExecuteSpec".
 */
export function useDocumentTitle(title: string | null | undefined) {
  useEffect(() => {
    if (!title) return;
    const prev = document.title;
    document.title = `${title} · ExecuteSpec`;
    return () => {
      // Restore on unmount so a surface that stops rendering (e.g. modal
      // detail) doesn't leave a stale title; the next surface's effect or the
      // shell default overwrites this immediately on navigation.
      document.title = prev;
    };
  }, [title]);
}

/** Shell-level fallback: map a pathname to a default title. Exhaustive over
 *  App.tsx's protected routes; detail surfaces override via useDocumentTitle. */
export function routeTitle(pathname: string): string {
  const exact: Record<string, string> = {
    '/home': 'Home',
    '/runs': 'All runs',
    '/compose': 'New spec',
    '/projects': 'Projects',
    '/account': 'Account',
    '/settings': 'Account',
    '/billing': 'Billing',
    '/plans': 'Plans',
    '/integrations': 'Integrations',
    '/api-keys': 'API keys',
    '/org-members': 'Org members',
    '/org/settings': 'Org settings',
    '/insights': 'Insights',
    '/library': 'Library',
    '/migrations': 'Migrations',
    '/templates': 'Spec templates',
    '/audit': 'Audit log',
    '/deployments': 'Deployments',
    '/notifications': 'Notifications',
    '/search': 'Search',
    '/more': 'More',
    '/admin': 'Admin',
    '/benchmark': 'Benchmarks',
  };
  if (exact[pathname]) return exact[pathname];
  if (pathname.startsWith('/plan')) return 'Plan approval';
  if (pathname.startsWith('/live')) return 'Live run';
  if (pathname.startsWith('/result') || pathname.startsWith('/runs/')) return 'Run detail';
  if (pathname.startsWith('/projects/')) return 'Project';
  if (pathname.startsWith('/library/')) return 'Library';
  if (pathname.startsWith('/migrations/')) return 'Migrations';
  if (pathname.startsWith('/admin')) return 'Admin';
  return 'ExecuteSpec';
}
