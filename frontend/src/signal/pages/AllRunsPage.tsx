import {useEffect, useMemo, useState} from 'react';
import {TopBar, TabBar, IconButton} from '@/signal/components/Chrome';
import {Feed, Row, Card, TabsStrip, Button} from '@/signal/components/Primitives';
import {LoadingState, SkeletonRow} from '@/signal/components/Loaders';
import {Search} from '@/signal/icons/icons';
import * as api from '@/lib/api';

type RunFilter = 'all' | 'live' | 'shipped' | 'needs' | 'failed' | 'archived';

function relTime(input?: string | null): string {
  if (!input) return '—';
  const t = new Date(input).getTime();
  if (!t) return '—';
  const s = Math.round((Date.now() - t) / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 30) return `${d}d ago`;
  return new Date(input).toLocaleDateString();
}

function statusToDot(s?: string): 'live' | 'mint' | 'coral' | 'signal' | 'lavender' {
  const x = (s || '').toUpperCase();
  if (x === 'CLASSIFYING' || x === 'PLANNING' || x === 'EXECUTING' || x === 'AWAITING_APPROVAL' || x === 'RUNNING') return 'live';
  if (x === 'COMPLETED') return 'mint';
  if (x === 'FAILED' || x === 'CANCELLED') return 'coral';
  if (x === 'PARTIAL') return 'signal';
  return 'lavender';
}

function scoreKind(score?: number | null): 'good' | 'warn' | 'fail' | 'default' {
  if (score == null) return 'default';
  if (score >= 85) return 'good';
  if (score >= 70) return 'warn';
  if (score > 0) return 'fail';
  return 'default';
}

function shortRunId(id?: string): string {
  if (!id) return 'R-—';
  return `R-${id.slice(0, 6)}`;
}

export default function AllRunsPage() {
  const [filter, setFilter] = useState<RunFilter>('all');
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);
  const [page, setPage] = useState(0);
  const [more, setMore] = useState(false);
  const [err, setErr] = useState('');

  useEffect(() => {
    let cancelled = false;
    setRuns(null);
    setErr('');
    api
      .getRuns({page: 0, size: 30})
      .then((p) => {
        if (cancelled) return;
        setRuns(p.content);
        setMore(p.hasNext);
        setPage(0);
      })
      .catch((e: unknown) => {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load runs.');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function loadMore() {
    try {
      const next = await api.getRuns({page: page + 1, size: 30});
      setRuns((prev) => [...(prev || []), ...next.content]);
      setPage((p) => p + 1);
      setMore(next.hasNext);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load more.');
    }
  }

  const counts = useMemo(() => {
    const list = runs || [];
    let live = 0, ship = 0, needs = 0, fail = 0, arch = 0;
    for (const r of list) {
      const s = (r.status || '').toUpperCase();
      const score = (r as unknown as {verifiedScore?: number}).verifiedScore;
      if ((r as unknown as {archived?: boolean}).archived) arch++;
      else if (s === 'CLASSIFYING' || s === 'PLANNING' || s === 'EXECUTING' || s === 'AWAITING_APPROVAL' || s === 'RUNNING') live++;
      else if (s === 'COMPLETED' && (score == null || score >= 85)) ship++;
      else if (s === 'COMPLETED' || s === 'PARTIAL') needs++;
      else if (s === 'FAILED' || s === 'CANCELLED') fail++;
    }
    return {live, ship, needs, fail, arch};
  }, [runs]);

  const filtered = useMemo(() => {
    const list = runs || [];
    return list.filter((r) => {
      const s = (r.status || '').toUpperCase();
      const score = (r as unknown as {verifiedScore?: number}).verifiedScore;
      const archived = (r as unknown as {archived?: boolean}).archived;
      if (filter === 'archived') return !!archived;
      if (archived) return false;
      if (filter === 'all') return true;
      if (filter === 'live') return s === 'CLASSIFYING' || s === 'PLANNING' || s === 'EXECUTING' || s === 'AWAITING_APPROVAL' || s === 'RUNNING';
      if (filter === 'shipped') return s === 'COMPLETED' && (score == null || score >= 85);
      if (filter === 'needs') return (s === 'COMPLETED' && score != null && score < 85) || s === 'PARTIAL';
      if (filter === 'failed') return s === 'FAILED' || s === 'CANCELLED';
      return true;
    });
  }, [runs, filter]);

  const total = runs?.length || 0;
  return (
    <>
      <TopBar
        title='All runs'
        sub={`${total} loaded · ${counts.live} live`}
        back
        backTo='/home'
        actions={
          <IconButton to='/search' ariaLabel='Search'>
            <Search size={18} />
          </IconButton>
        }
      />
      <Feed>
        <TabsStrip
          items={[
            {label: `All · ${total}`, active: filter === 'all', onClick: () => setFilter('all')},
            {label: `Live · ${counts.live}`, active: filter === 'live', onClick: () => setFilter('live')},
            {label: `Shipped · ${counts.ship}`, active: filter === 'shipped', onClick: () => setFilter('shipped')},
            {label: `Needs · ${counts.needs}`, active: filter === 'needs', onClick: () => setFilter('needs')},
            {label: `Failed · ${counts.fail}`, active: filter === 'failed', onClick: () => setFilter('failed')},
            {label: `Archived · ${counts.arch}`, active: filter === 'archived', onClick: () => setFilter('archived')},
          ]}
        />

        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}

        {runs === null ? (
          <Card>
            <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>Loading runs…</p>
          </Card>
        ) : filtered.length === 0 ? (
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
              No runs match this filter. {filter !== 'all' && <a href='#' onClick={(e) => {e.preventDefault(); setFilter('all');}} style={{color: 'var(--signal-coral)'}}>Show all →</a>}
            </p>
          </Card>
        ) : (
          filtered.map((r) => {
            const score = (r as unknown as {verifiedScore?: number}).verifiedScore;
            const cost = (r as unknown as {actualCost?: number; totalCost?: number}).actualCost ??
              (r as unknown as {totalCost?: number}).totalCost;
            const proj = (r as unknown as {projectName?: string; projectId?: string}).projectName ||
              (r as unknown as {projectId?: string}).projectId;
            const s = (r.status || '').toUpperCase();
            const isLive = s === 'CLASSIFYING' || s === 'PLANNING' || s === 'EXECUTING' || s === 'AWAITING_APPROVAL' || s === 'RUNNING';
            return (
              <Row
                key={r.runId}
                to={isLive ? `/live/${r.runId}` : `/result/${r.runId}`}
                dot={statusToDot(s)}
                title={(() => {
                  // Prefer the agent-generated short runTitle over the raw
                  // spec for the same reason as HomePage — list rows look
                  // ridiculous when filled with 600-char spec dumps.
                  const rAny = r as unknown as {runTitle?: string};
                  return rAny.runTitle?.trim() || r.userRequest?.trim() || `Run ${shortRunId(r.runId)}`;
                })()}
                meta={
                  <>
                    <span className='mono'>{shortRunId(r.runId)}</span>
                    {proj && (
                      <>
                        <span>·</span>
                        <span className='mono'>{String(proj).slice(0, 16)}</span>
                      </>
                    )}
                    <span>·</span>
                    <span>{relTime((r as unknown as {createdAt?: string}).createdAt)}</span>
                  </>
                }
                trailing={score != null ? `${Math.round(score)}%` : isLive ? 'live' : s.toLowerCase()}
                trailingKind={scoreKind(score)}
                trailingLabel={cost != null ? `$${cost.toFixed(2)}` : undefined}
              />
            );
          })
        )}

        {more && (
          <Button kind='secondary' block style={{marginTop: 12}} onClick={loadMore}>
            Load more
          </Button>
        )}
      </Feed>
      <TabBar active='runs' />
    </>
  );
}
