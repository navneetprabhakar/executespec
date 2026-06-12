import {Link} from 'react-router-dom';

/**
 * C3 (UI/UX review 2026-06-11) — shared breadcrumb strip for nested surfaces
 * (project sub-tabs, run detail, discovery detail pages). Desktop renders the
 * full trail ("Projects › my-app › Chat"); mobile (<600px, via the
 * .ide-crumbs CSS in ide.css) collapses to a single "‹ parent" back link.
 *
 * The last item is the current page (no link). Keep trails ≤4 items.
 */
export interface Crumb {
  label: string;
  /** Route to link to. Omit on the final (current) crumb. */
  to?: string;
}

export default function Crumbs({items}: {items: Crumb[]}) {
  if (items.length === 0) return null;
  const parent = items.length > 1 ? items[items.length - 2] : null;
  return (
    <nav className='ide-crumbs' aria-label='Breadcrumb'>
      {/* Mobile: single back link to the parent (hidden on desktop via CSS). */}
      {parent?.to && (
        <Link className='ide-crumbs-back' to={parent.to}>
          ‹ {parent.label}
        </Link>
      )}
      {/* Desktop trail (hidden on mobile via CSS). */}
      <ol className='ide-crumbs-trail'>
        {items.map((c, i) => {
          const last = i === items.length - 1;
          return (
            <li key={`${c.label}-${i}`}>
              {c.to && !last ? (
                <Link to={c.to}>{c.label}</Link>
              ) : (
                <span aria-current={last ? 'page' : undefined}>{c.label}</span>
              )}
              {!last && <span aria-hidden='true'> › </span>}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
