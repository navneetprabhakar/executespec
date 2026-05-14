import {errMsg} from '@/lib/utils';
import {useEffect, useMemo, useState} from 'react';
import {Link} from 'react-router-dom';
import FilterSheet from '@/components/shared/FilterSheet';
import {listMigrations, type MigrationListItem} from '@/lib/api';

/**
 * R-340 — public gallery for migration recipes.  Card grid + filters.
 *
 * Backed by GET /api/migrations (list with optional category / tier /
 * search filters; tier-gating decoration applied per-row by the
 * server based on the caller's tier).  Lock-icon cards open an
 * upgrade prompt; unlocked cards link to /migrations/:slug for the
 * detail page where the user fills slots + runs Phase 1 dry-run +
 * applies.
 */
export default function MigrationsPage() {
  const [items, setItems] = useState<MigrationListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [tierFilter, setTierFilter] = useState<string>('');
  const [categoryFilter, setCategoryFilter] = useState<string>('');
  // Audit r5 (P2-5, 2026-05-06) — same hide-locked pattern as
  // LibraryPage. Migrations is even more paywall-heavy on FREE
  // (every recipe is PRO+ today); the toggle gives users a one-click
  // escape from a wall of padlocks while keeping discovery default-on.
  const [hideLocked, setHideLocked] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    listMigrations({})
      .then((data) => {
        if (cancelled) return;
        setItems(data ?? []);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setError(errMsg(e) ?? 'Failed to load migrations');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const categories = useMemo(() => {
    const set = new Set<string>();
    for (const it of items) set.add(it.recipe.category);
    return Array.from(set).sort();
  }, [items]);

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return items.filter((it) => {
      if (categoryFilter && it.recipe.category !== categoryFilter) return false;
      if (tierFilter && it.recipe.tier !== tierFilter) return false;
      if (hideLocked && it.locked) return false;
      if (needle) {
        const hay = (
          it.recipe.name +
          ' ' +
          (it.recipe.tagline ?? '') +
          ' ' +
          (it.recipe.description ?? '')
        ).toLowerCase();
        if (!hay.includes(needle)) return false;
      }
      return true;
    });
  }, [items, search, tierFilter, categoryFilter, hideLocked]);

  const lockedCount = useMemo(() => items.filter((it) => it.locked).length, [items]);

  return (
    <div style={{padding: 16, paddingBottom: 48}}>
      {/* Header */}
      <div style={{marginBottom: 20}}>
        <h1 style={{fontSize: 24, fontWeight: 700, color: 'var(--v3-text)', margin: 0}}>
          Migrations
        </h1>
        <p style={{fontSize: 13, color: 'var(--v3-text-subtle)', margin: '6px 0 0', maxWidth: 700}}>
          Pick a recipe → see what it'll do across your repos before you commit → click apply, get
          PRs. Same recipe runs on every linked repo in your project.
        </p>
      </div>

      {/* Filters.
          2026-05-14 (P6) — same FilterSheet pattern as LibraryPage:
          search inline, selects + Hide-locked behind a Filter sheet on
          phone, inline wrap row on desktop. */}
      <div className='mb-4 space-y-2 sm:space-y-0'>
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder='Search recipes…'
          className='w-full sm:w-auto'
          style={{
            display: 'block',
            padding: '10px 12px',
            background: 'var(--v3-bg)',
            color: 'var(--v3-text)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            fontSize: 13,
            outline: 'none',
            fontFamily: 'inherit',
            minHeight: 44,
            width: '100%',
          }}
        />
        <FilterSheet
          activeCount={(categoryFilter ? 1 : 0) + (tierFilter ? 1 : 0) + (hideLocked ? 1 : 0)}
          onReset={() => {
            setCategoryFilter('');
            setTierFilter('');
            setHideLocked(false);
          }}
          inlineClassName='flex flex-wrap gap-2 mt-2 sm:mt-2'
        >
          <select
            value={categoryFilter}
            onChange={(e) => setCategoryFilter(e.target.value)}
            style={selectStyle}
          >
            <option value=''>All categories</option>
            {categories.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
          <select
            value={tierFilter}
            onChange={(e) => setTierFilter(e.target.value)}
            style={selectStyle}
          >
            <option value=''>All tiers</option>
            <option value='FREE'>Free</option>
            <option value='PRO'>Pro</option>
            <option value='TEAM'>Team</option>
            <option value='ENTERPRISE'>Enterprise</option>
          </select>
          {lockedCount > 0 && (
            <button
              type='button'
              onClick={() => setHideLocked((v) => !v)}
              aria-pressed={hideLocked}
              style={{
                padding: '10px 12px',
                background: hideLocked ? 'var(--v3-brand)' : 'var(--v3-bg)',
                color: hideLocked ? 'var(--v3-text-on-brand)' : 'var(--v3-text-muted)',
                border: '1px solid ' + (hideLocked ? 'var(--v3-brand)' : 'var(--v3-border)'),
                borderRadius: 6,
                cursor: 'pointer',
                fontSize: 13,
                fontFamily: 'inherit',
                fontWeight: 500,
                minHeight: 44,
              }}
            >
              {hideLocked ? `Show all (${lockedCount} locked)` : 'Hide locked'}
            </button>
          )}
        </FilterSheet>
      </div>

      {/* Body */}
      {loading && (
        <div
          style={{padding: 24, textAlign: 'center', color: 'var(--v3-text-subtle)', fontSize: 13}}
        >
          Loading migrations…
        </div>
      )}
      {error && !loading && (
        <div
          style={{
            padding: 12,
            background: 'rgba(239,68,68,0.12)',
            border: '1px solid rgba(239,68,68,0.36)',
            color: '#FCA5A5',
            borderRadius: 6,
            fontSize: 13,
          }}
        >
          {error}
        </div>
      )}
      {!loading && !error && filtered.length === 0 && (
        <div
          style={{
            padding: 24,
            textAlign: 'center',
            color: 'var(--v3-text-subtle)',
            fontSize: 13,
            border: '1px dashed var(--v3-border)',
            borderRadius: 6,
          }}
        >
          No migrations match your filters. {search && `(searching for "${search}")`}
        </div>
      )}

      {/* Card grid */}
      {!loading && !error && filtered.length > 0 && (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))',
            gap: 12,
          }}
        >
          {filtered.map((item) => (
            <RecipeCard key={item.recipe.slug} item={item} />
          ))}
        </div>
      )}
    </div>
  );
}

function RecipeCard({item}: {item: MigrationListItem}) {
  const r = item.recipe;
  const credits = formatCreditRange(r.estCreditsLow, r.estCreditsHigh);
  const files = formatFileRange(r.estFilesLow, r.estFilesHigh);

  const inner = (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        padding: 14,
        background: item.locked ? 'var(--v3-surface-elevated)' : 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 8,
        height: '100%',
        opacity: item.locked ? 0.7 : 1,
        position: 'relative',
      }}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'space-between'}}>
        <div
          style={{
            fontSize: 14,
            fontWeight: 600,
            color: 'var(--v3-text)',
          }}
        >
          {r.name}
        </div>
        {item.locked && (
          <span title={`Upgrade to ${item.unlockTier ?? 'PRO'} to use`} style={lockChipStyle}>
            🔒 {item.unlockTier ?? 'PRO'}
          </span>
        )}
      </div>
      {r.tagline && (
        <p style={{margin: 0, fontSize: 12, color: 'var(--v3-text-subtle)', lineHeight: 1.5}}>
          {r.tagline}
        </p>
      )}
      <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 4}}>
        <span style={categoryChipStyle}>{r.category}</span>
        {credits && <span style={chipStyle}>{credits}</span>}
        {files && <span style={chipStyle}>{files}</span>}
      </div>
    </div>
  );

  if (item.locked) {
    return (
      <Link to='/pricing' style={{textDecoration: 'none'}}>
        {inner}
      </Link>
    );
  }
  return (
    <Link to={`/migrations/${r.slug}`} style={{textDecoration: 'none'}}>
      {inner}
    </Link>
  );
}

function formatCreditRange(low: string | null, high: string | null): string | null {
  if (!low && !high) return null;
  const l = low ? parseFloat(low).toFixed(0) : '?';
  const h = high ? parseFloat(high).toFixed(0) : '?';
  return `≈ ${l}–${h} credits`;
}

function formatFileRange(low: number | null, high: number | null): string | null {
  if (low == null && high == null) return null;
  const l = low ?? '?';
  const h = high ?? '?';
  return `${l}–${h} files`;
}

const selectStyle: React.CSSProperties = {
  padding: '8px 10px',
  background: 'var(--v3-bg)',
  color: 'var(--v3-text)',
  border: '1px solid var(--v3-border)',
  borderRadius: 6,
  fontSize: 12,
  fontFamily: 'inherit',
  cursor: 'pointer',
};

const chipStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--v3-text-subtle)',
  background: 'var(--v3-bg)',
  padding: '2px 6px',
  borderRadius: 4,
  border: '1px solid var(--v3-border)',
};

const categoryChipStyle: React.CSSProperties = {
  ...chipStyle,
  color: 'var(--v3-brand)',
  borderColor: 'var(--v3-brand)',
  background: 'transparent',
};

const lockChipStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 600,
  background: 'rgba(245,158,11,0.18)',
  color: '#FBBF24',
  padding: '2px 6px',
  borderRadius: 4,
  border: '1px solid rgba(245,158,11,0.4)',
};
