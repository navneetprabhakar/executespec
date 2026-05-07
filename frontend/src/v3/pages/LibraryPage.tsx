import {errMsg} from '@/lib/utils';
import {useEffect, useMemo, useState} from 'react';
import {Link} from 'react-router-dom';
import {getLibraryFacets, listLibrary, type LibraryFacets, type ReadymadeListItem} from '@/lib/api';

/**
 * R-330 — Readymade Solutions gallery.
 *
 * <p>Card grid backed by GET /api/library.  Faceting on the 4 macro
 * groups (people-and-money / data-and-infra / ui-and-growth /
 * smart-and-reach) + 15 user-friendly categories + tier filter +
 * free-text search.  Locked cards (caller below solution.tier) link
 * to /pricing instead of the detail page.
 *
 * <p>Mirrors MigrationsPage shape so users get a consistent gallery
 * UX across both products.  When a third library-shaped product
 * lands (Agent Builder v2?) we extract the shared chrome into a
 * V3LibraryShell component.
 */
export default function LibraryPage() {
  const [items, setItems] = useState<ReadymadeListItem[]>([]);
  const [facets, setFacets] = useState<LibraryFacets | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [tierFilter, setTierFilter] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('');
  const [macroFilter, setMacroFilter] = useState('');
  // Audit r5 (P2-5, 2026-05-06) — let FREE users hide locked cards so
  // the page doesn't feel like a 90% paywall on first paint. Default
  // off (preserves discovery for everyone); flipping on filters to
  // only the cards the current tier can actually use.
  const [hideLocked, setHideLocked] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([listLibrary({}), getLibraryFacets().catch(() => null)])
      .then(([list, facetData]) => {
        if (cancelled) return;
        setItems(list ?? []);
        setFacets(facetData);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setError(errMsg(e) ?? 'Failed to load library');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const macroGroups = useMemo(() => {
    if (!facets) return [];
    return Object.entries(facets.macroGroups).map(([k, n]) => ({key: k, count: n}));
  }, [facets]);

  const categories = useMemo(() => {
    if (!facets) {
      // Fallback: derive from items in case facets endpoint failed
      const set = new Set<string>();
      items.forEach((it) => set.add(it.solution.category));
      return Array.from(set)
        .sort()
        .map((c) => ({key: c, count: 0}));
    }
    return Object.entries(facets.categories).map(([k, n]) => ({key: k, count: n}));
  }, [facets, items]);

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return items.filter((it) => {
      const s = it.solution;
      if (categoryFilter && s.category !== categoryFilter) return false;
      if (macroFilter && s.macroGroup !== macroFilter) return false;
      if (tierFilter && s.tier !== tierFilter) return false;
      if (hideLocked && it.locked) return false;
      if (needle) {
        const hay = (s.name + ' ' + (s.tagline ?? '') + ' ' + (s.description ?? '')).toLowerCase();
        if (!hay.includes(needle)) return false;
      }
      return true;
    });
  }, [items, search, tierFilter, categoryFilter, macroFilter, hideLocked]);

  const lockedCount = useMemo(() => items.filter((it) => it.locked).length, [items]);

  return (
    <div style={{padding: 16, paddingBottom: 48}}>
      {/* Header */}
      <div style={{marginBottom: 20}}>
        <h1 style={{fontSize: 24, fontWeight: 700, color: 'var(--v3-text)', margin: 0}}>Library</h1>
        <p style={{fontSize: 13, color: 'var(--v3-text-subtle)', margin: '6px 0 0', maxWidth: 700}}>
          Pluggable solutions that adapt to your stack. Sign-in, payments, notifications, data, AI —
          pick a card → fill in the slots → ship it as a PR. Free starters for everyone, premium
          catalog for paid tiers.
        </p>
      </div>

      {/* Macro-group tabs (top-of-page) */}
      {macroGroups.length > 0 && (
        <div style={{display: 'flex', gap: 6, marginBottom: 12, flexWrap: 'wrap'}}>
          <button
            type='button'
            onClick={() => setMacroFilter('')}
            style={macroPill(macroFilter === '')}
          >
            All
          </button>
          {macroGroups.map(({key, count}) => (
            <button
              key={key}
              type='button'
              onClick={() => setMacroFilter(macroFilter === key ? '' : key)}
              style={macroPill(macroFilter === key)}
            >
              {humanize(key)} <span style={{opacity: 0.55, marginLeft: 4}}>{count}</span>
            </button>
          ))}
        </div>
      )}

      {/* Filters row */}
      <div style={{display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap'}}>
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder='Search solutions…'
          style={{
            flex: '1 1 280px',
            padding: '8px 12px',
            background: 'var(--v3-bg)',
            color: 'var(--v3-text)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            fontSize: 13,
            outline: 'none',
            fontFamily: 'inherit',
          }}
        />
        <select
          value={categoryFilter}
          onChange={(e) => setCategoryFilter(e.target.value)}
          style={selectStyle}
        >
          <option value=''>All categories</option>
          {categories.map(({key}) => (
            <option key={key} value={key}>
              {humanize(key)}
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
          <option value='PRO'>Solo</option>
          <option value='TEAM'>Team</option>
          <option value='ENTERPRISE'>Enterprise</option>
        </select>
        {/* Audit r5 (P2-5, 2026-05-06) — quick "hide locked" toggle.
            FREE users with ~3 unlocked / 28 cards saw mostly padlocks
            on first paint. This pill flips the locked items off
            without forcing them onto a hunt through filters. Locked
            count surfaces honestly so the upgrade path stays visible. */}
        {lockedCount > 0 && (
          <button
            type='button'
            onClick={() => setHideLocked((v) => !v)}
            aria-pressed={hideLocked}
            style={{
              padding: '8px 12px',
              background: hideLocked ? 'var(--v3-brand)' : 'var(--v3-bg)',
              color: hideLocked ? 'var(--v3-text-on-brand)' : 'var(--v3-text-muted)',
              border: '1px solid ' + (hideLocked ? 'var(--v3-brand)' : 'var(--v3-border)'),
              borderRadius: 6,
              cursor: 'pointer',
              fontSize: 12.5,
              fontFamily: 'inherit',
              fontWeight: 500,
            }}
            title={
              hideLocked
                ? `Showing only items your tier can use. ${lockedCount} hidden.`
                : `Hide ${lockedCount} locked items. Upgrade options stay one click away on /pricing.`
            }
          >
            {hideLocked ? `Show all (${lockedCount} locked)` : 'Hide locked'}
          </button>
        )}
      </div>

      {/* Body */}
      {loading && (
        <div
          style={{padding: 24, textAlign: 'center', color: 'var(--v3-text-subtle)', fontSize: 13}}
        >
          Loading library…
        </div>
      )}
      {error && !loading && <div style={errorBox}>{error}</div>}
      {!loading && !error && filtered.length === 0 && (
        <div style={emptyState}>
          No solutions match your filters. {search && `(searching for "${search}")`}
        </div>
      )}

      {!loading && !error && filtered.length > 0 && (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))',
            gap: 12,
          }}
        >
          {filtered.map((item) => (
            <SolutionCard key={item.solution.slug} item={item} />
          ))}
        </div>
      )}
    </div>
  );
}

function SolutionCard({item}: {item: ReadymadeListItem}) {
  const s = item.solution;
  const credits = formatCreditRange(s.estCreditsLow, s.estCreditsHigh);
  const files = formatFileRange(s.estFilesLow, s.estFilesHigh);

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
        <div style={{fontSize: 14, fontWeight: 600, color: 'var(--v3-text)'}}>{s.name}</div>
        {item.locked && (
          <span title={`Upgrade to ${item.unlockTier ?? 'PRO'} to use`} style={lockChipStyle}>
            🔒 {item.unlockTier ?? 'PRO'}
          </span>
        )}
      </div>
      {s.tagline && (
        <p style={{margin: 0, fontSize: 12, color: 'var(--v3-text-subtle)', lineHeight: 1.5}}>
          {s.tagline}
        </p>
      )}
      <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 4}}>
        <span style={categoryChip}>{humanize(s.category)}</span>
        <span style={{...chipStyle, color: 'var(--v3-text-subtle)'}}>{s.kind.toLowerCase()}</span>
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
    <Link to={`/library/${s.slug}`} style={{textDecoration: 'none'}}>
      {inner}
    </Link>
  );
}

/** Task 16 (2026-05-01 fix list) — naive Title-Case rendered slugs as
 *  "People And Money" / "Smart And Reach" / "Ui And Growth" / "Data
 *  And Infra" which read computer-generated. Use an explicit label
 *  map for known macro-groups + categories so chips match what a
 *  human would write (proper "&", "UI", etc). Falls back to the old
 *  Title-Case for any unmapped key so newly-added categories don't
 *  silently disappear behind an empty label. */
const HUMAN_LABELS: Record<string, string> = {
  // Macro groups
  'people-and-money': 'People & Money',
  'smart-and-reach': 'Marketing & Reach',
  'ui-and-growth': 'UI & Growth',
  'data-and-infra': 'Data & Infrastructure',
  // Common categories whose slug-cased rendering reads awkward
  'sign-in-and-users': 'Sign-in & Users',
  'add-ai': 'Add AI',
  'ship-and-monitor': 'Ship & Monitor',
  'build-the-ui': 'Build the UI',
  'take-payments': 'Take Payments',
  'grow-the-user-base': 'Grow the User Base',
  'send-messages': 'Send Messages',
  'track-usage': 'Track Usage',
  'store-data': 'Store Data',
  'work-with-apis': 'Work with APIs',
  'talk-to-users': 'Talk to Users',
  'booking-and-scheduling': 'Booking & Scheduling',
  'go-mobile': 'Go Mobile',
  'stay-compliant': 'Stay Compliant',
  'location-and-maps': 'Location & Maps',
};

function humanize(key: string): string {
  if (!key) return '';
  const mapped = HUMAN_LABELS[key.toLowerCase()];
  if (mapped) return mapped;
  return key.replace(/[-_]/g, ' ').replace(/\b\w/g, (m) => m.toUpperCase());
}

function formatCreditRange(low: string | null, high: string | null): string | null {
  if (!low && !high) return null;
  const l = low ? parseFloat(low).toFixed(0) : '?';
  const h = high ? parseFloat(high).toFixed(0) : '?';
  return `≈ ${l}–${h} credits`;
}

function formatFileRange(low: number | null, high: number | null): string | null {
  if (low == null && high == null) return null;
  return `${low ?? '?'}–${high ?? '?'} files`;
}

function macroPill(active: boolean): React.CSSProperties {
  return {
    padding: '5px 12px',
    background: active ? 'var(--v3-brand)' : 'var(--v3-surface)',
    color: active ? 'var(--v3-text-on-brand)' : 'var(--v3-text)',
    border: `1px solid ${active ? 'var(--v3-brand)' : 'var(--v3-border)'}`,
    borderRadius: 16,
    fontSize: 12,
    fontWeight: 500,
    cursor: 'pointer',
    fontFamily: 'inherit',
  };
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

const categoryChip: React.CSSProperties = {
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

const errorBox: React.CSSProperties = {
  padding: 12,
  background: 'rgba(239,68,68,0.12)',
  border: '1px solid rgba(239,68,68,0.36)',
  color: '#FCA5A5',
  borderRadius: 6,
  fontSize: 13,
};

const emptyState: React.CSSProperties = {
  padding: 24,
  textAlign: 'center',
  color: 'var(--v3-text-subtle)',
  fontSize: 13,
  border: '1px dashed var(--v3-border)',
  borderRadius: 6,
};
