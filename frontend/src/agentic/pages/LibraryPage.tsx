/**
 * Catalog — Library + Migrations merged into a single App-Store-style
 * gallery.
 *
 * Mobile: 1-col card stream. Tablet: 2-col. Desktop: 3-col grid.
 * Search input pinned under the title. Category chips. A small
 * segmented toggle at the top switches Solutions ↔ Migrations.
 *
 * Cards lead with an icon (category-colored), name, 2-line description,
 * meta row (credits estimate + files estimate). Tap → opens the v3
 * detail page (which already has the full instantiation flow).
 */
import {useEffect, useMemo, useState} from 'react';
import {Link} from 'react-router-dom';
import {Library, ArrowUpRight, Search, Wrench} from 'lucide-react';
import {listLibrary, listMigrations} from '@/lib/api';
import LargeTitle from '../components/LargeTitle';

type Mode = 'solutions' | 'migrations';

interface CatalogItem {
  slug: string;
  name: string;
  tagline?: string | null;
  description?: string | null;
  category?: string | null;
  macroGroup?: string | null;
  tier?: string | null;
  estCreditsLow?: string | null;
  estCreditsHigh?: string | null;
  estFilesLow?: number | null;
  estFilesHigh?: number | null;
  locked?: boolean;
}

function humanize(s?: string | null): string {
  if (!s) return '';
  return s
    .replace(/[_-]/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

export default function LibraryPage() {
  const [mode, setMode] = useState<Mode>('solutions');
  const [items, setItems] = useState<CatalogItem[] | null>(null);
  const [category, setCategory] = useState<string>('');
  const [search, setSearch] = useState('');

  useEffect(() => {
    let cancelled = false;
    setItems(null);
    const p =
      mode === 'solutions'
        ? listLibrary().then((rows) =>
            rows.map((w) => ({...w.solution, locked: w.locked})),
          )
        : listMigrations().then((rows) =>
            rows.map((w) => ({...w.recipe, locked: w.locked})),
          );
    p.then((list) => {
      if (!cancelled) setItems(list);
    }).catch(() => !cancelled && setItems([]));
    return () => {
      cancelled = true;
    };
  }, [mode]);

  const categories = useMemo(() => {
    if (!items) return [];
    const counts = new Map<string, number>();
    items.forEach((it) => {
      const k = it.category || it.macroGroup || 'other';
      counts.set(k, (counts.get(k) ?? 0) + 1);
    });
    return Array.from(counts.entries()).sort((a, b) => b[1] - a[1]);
  }, [items]);

  const filtered = useMemo(() => {
    if (!items) return [];
    const needle = search.trim().toLowerCase();
    return items.filter((it) => {
      if (category && (it.category || it.macroGroup) !== category) return false;
      if (needle) {
        const hay = (
          it.name +
          ' ' +
          (it.tagline ?? '') +
          ' ' +
          (it.description ?? '')
        ).toLowerCase();
        if (!hay.includes(needle)) return false;
      }
      return true;
    });
  }, [items, search, category]);

  const detailBase = mode === 'solutions' ? '/library' : '/migrations';

  return (
    <>
      <LargeTitle
        title='Catalog'
        subtitle={
          items == null
            ? 'Loading…'
            : `${items.length} ${mode === 'solutions' ? 'solution' : 'recipe'}${
                items.length === 1 ? '' : 's'
              } · ${categories.length} categor${categories.length === 1 ? 'y' : 'ies'}`
        }
        trailing={
          <div
            role='tablist'
            aria-label='Catalog type'
            style={{
              display: 'inline-flex',
              gap: 0,
              padding: 3,
              background: 'var(--a-surface-sunken)',
              border: '1px solid var(--a-hairline)',
              borderRadius: 9999,
            }}
          >
            <button
              type='button'
              role='tab'
              aria-selected={mode === 'solutions'}
              onClick={() => setMode('solutions')}
              className='a-press'
              style={{
                padding: '4px 12px',
                fontSize: 11.5,
                fontWeight: 600,
                color: mode === 'solutions' ? '#fff' : 'var(--a-text-muted)',
                background:
                  mode === 'solutions' ? 'var(--a-brand)' : 'transparent',
                border: 'none',
                borderRadius: 9999,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              Solutions
            </button>
            <button
              type='button'
              role='tab'
              aria-selected={mode === 'migrations'}
              onClick={() => setMode('migrations')}
              className='a-press'
              style={{
                padding: '4px 12px',
                fontSize: 11.5,
                fontWeight: 600,
                color: mode === 'migrations' ? '#fff' : 'var(--a-text-muted)',
                background:
                  mode === 'migrations' ? 'var(--a-brand)' : 'transparent',
                border: 'none',
                borderRadius: 9999,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              Migrations
            </button>
          </div>
        }
      />

      {/* Search */}
      <div
        style={{
          position: 'relative',
          marginBottom: 14,
        }}
      >
        <Search
          size={14}
          aria-hidden='true'
          style={{
            position: 'absolute',
            left: 12,
            top: '50%',
            transform: 'translateY(-50%)',
            color: 'var(--a-text-subtle)',
          }}
        />
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={
            mode === 'solutions'
              ? 'Search solutions…'
              : 'Search migration recipes…'
          }
          style={{
            width: '100%',
            padding: '10px 12px 10px 34px',
            background: 'var(--a-surface-sunken)',
            border: '1px solid var(--a-hairline)',
            borderRadius: 10,
            color: 'var(--a-text)',
            fontFamily: 'inherit',
            fontSize: 13.5,
            outline: 'none',
          }}
        />
      </div>

      {/* Category chips */}
      {categories.length > 0 && (
        <div
          style={{
            display: 'flex',
            gap: 8,
            flexWrap: 'wrap',
            marginBottom: 18,
          }}
        >
          <button
            type='button'
            onClick={() => setCategory('')}
            className='a-press'
            style={chipStyle(!category)}
          >
            All
          </button>
          {categories.map(([k, n]) => (
            <button
              key={k}
              type='button'
              onClick={() => setCategory(category === k ? '' : k)}
              className='a-press'
              style={chipStyle(category === k)}
            >
              {humanize(k)} <span style={{opacity: 0.6, marginLeft: 4}}>{n}</span>
            </button>
          ))}
        </div>
      )}

      {items == null ? (
        <p style={{color: 'var(--a-text-subtle)'}}>Loading catalog…</p>
      ) : filtered.length === 0 ? (
        <div
          className='a-card'
          style={{
            padding: 28,
            textAlign: 'center',
            color: 'var(--a-text-muted)',
            fontSize: 'var(--a-text-sm)',
          }}
        >
          No matches. Try a different search or category.
        </div>
      ) : (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns:
              'repeat(auto-fill, minmax(min(260px, 100%), 1fr))',
            gap: 12,
          }}
        >
          {filtered.map((it) => (
            <Link
              key={it.slug}
              to={`${detailBase}/${it.slug}`}
              className='a-card is-tappable'
              style={{
                padding: 14,
                textDecoration: 'none',
                color: 'inherit',
                display: 'flex',
                flexDirection: 'column',
                gap: 8,
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
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
                    background: 'var(--a-brand-soft)',
                    color: 'var(--a-brand)',
                    flexShrink: 0,
                  }}
                >
                  {mode === 'solutions' ? (
                    <Library size={14} />
                  ) : (
                    <Wrench size={14} />
                  )}
                </span>
                <span
                  style={{
                    fontSize: 'var(--a-text-sm)',
                    fontWeight: 600,
                    color: 'var(--a-text)',
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                  }}
                >
                  {it.name}
                </span>
                <ArrowUpRight
                  size={14}
                  aria-hidden='true'
                  style={{
                    marginLeft: 'auto',
                    color: 'var(--a-text-subtle)',
                    flexShrink: 0,
                  }}
                />
              </div>
              {it.tagline && (
                <p
                  style={{
                    margin: 0,
                    fontSize: 'var(--a-text-xs)',
                    color: 'var(--a-text-muted)',
                    lineHeight: 1.5,
                    display: '-webkit-box',
                    WebkitLineClamp: 2,
                    WebkitBoxOrient: 'vertical',
                    overflow: 'hidden',
                  }}
                >
                  {it.tagline}
                </p>
              )}
              <div
                style={{
                  display: 'flex',
                  flexWrap: 'wrap',
                  gap: 6,
                  marginTop: 'auto',
                }}
              >
                {(it.category || it.macroGroup) && (
                  <Pill>{humanize(it.category || it.macroGroup)}</Pill>
                )}
                {it.estCreditsLow && it.estCreditsHigh && (
                  <Pill>
                    ≈ {it.estCreditsLow}–{it.estCreditsHigh} credits
                  </Pill>
                )}
                {it.estFilesLow != null && it.estFilesHigh != null && (
                  <Pill>
                    {it.estFilesLow}–{it.estFilesHigh} files
                  </Pill>
                )}
              </div>
            </Link>
          ))}
        </div>
      )}
    </>
  );
}

function chipStyle(active: boolean): React.CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '5px 12px',
    fontSize: 12,
    fontWeight: 500,
    borderRadius: 9999,
    border: `1px solid ${active ? 'var(--a-brand)' : 'var(--a-border)'}`,
    background: active ? 'var(--a-brand-soft)' : 'transparent',
    color: active ? 'var(--a-brand)' : 'var(--a-text-muted)',
    cursor: 'pointer',
    fontFamily: 'inherit',
  };
}

function Pill({children}: {children: React.ReactNode}) {
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        padding: '2px 7px',
        background: 'var(--a-surface-sunken)',
        border: '1px solid var(--a-hairline)',
        borderRadius: 4,
        fontSize: 10.5,
        color: 'var(--a-text-subtle)',
      }}
    >
      {children}
    </span>
  );
}
