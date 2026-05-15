import {useEffect, useMemo, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Card, Button, TabsStrip} from '@/signal/components/Primitives';
import {ProjectTabs} from './ProjectTabs';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import * as api from '@/lib/api';

type ActivityFilter = 'all' | 'runs' | 'prs' | 'members' | 'standards';

function dotForType(t: string): 'mint' | 'signal' | 'lavender' | 'coral' | 'mute' {
  const x = t.toUpperCase();
  if (x.includes('PR_MERGED') || x.includes('RUN_COMPLETED')) return 'mint';
  if (x.includes('PR_OPENED') || x.includes('PR')) return 'signal';
  if (x.includes('STANDARD') || x.includes('ADR')) return 'lavender';
  if (x.includes('FAIL') || x.includes('REMOVED')) return 'coral';
  return 'mute';
}

function relTime(input?: string): string {
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
  return `${d}d ago`;
}

export default function ProjectActivityPage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const [page, setPage] = useState<api.ActivityPage | null>(null);
  const project = useProjectMeta(id);
  const [filter, setFilter] = useState<ActivityFilter>('all');
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    api.getActivityFeed(id, {limit: 50, sinceDays: 90})
      .then((a) => { if (!cancelled) setPage(a); })
      .catch(() => { if (!cancelled) setErr('Failed to load activity.'); });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const entries: api.ActivityEntry[] = page?.entries || [];
  const filtered = useMemo(() => {
    if (filter === 'all') return entries;
    return entries.filter((e) => {
      const t = (e.eventType || '').toUpperCase();
      if (filter === 'runs') return t.includes('RUN_');
      if (filter === 'prs') return t.includes('PR_');
      if (filter === 'members') return t.includes('MEMBER') || t.includes('INVITE');
      if (filter === 'standards') return t.includes('STANDARD') || t.includes('ADR') || t.includes('INVARIANT');
      return true;
    });
  }, [entries, filter]);

  return (
    <>
      <TopBar title='Activity' sub={`${project?.name || 'project'} · ${entries.length} events`} back backTo={`/projects/${id}`} />
      <Feed>
        <ProjectTabs active='activity' />
        <TabsStrip
          items={[
            {label: `All · ${entries.length}`, active: filter === 'all', onClick: () => setFilter('all')},
            {label: 'Runs', active: filter === 'runs', onClick: () => setFilter('runs')},
            {label: 'PRs', active: filter === 'prs', onClick: () => setFilter('prs')},
            {label: 'Members', active: filter === 'members', onClick: () => setFilter('members')},
            {label: 'Standards', active: filter === 'standards', onClick: () => setFilter('standards')},
          ]}
        />

        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}

        {page == null ? (
          <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>Loading activity…</p></Card>
        ) : filtered.length === 0 ? (
          <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>No activity yet for this filter.</p></Card>
        ) : (
          filtered.map((e: api.ActivityEntry, i: number) => {
            const eAny = e as unknown as {actorEmail?: string; actorUserId?: string};
            return (
              <Row
                key={e.id || `${i}-${e.occurredAt}`}
                dot={dotForType(e.eventType || '')}
                title={e.summary || e.eventType || 'event'}
                meta={
                  <>
                    {(eAny.actorEmail || eAny.actorUserId) && <span>{eAny.actorEmail || eAny.actorUserId}</span>}
                    {e.eventType && (
                      <>
                        {(eAny.actorEmail || eAny.actorUserId) && <span>·</span>}
                        <span className='mono'>{e.eventType}</span>
                      </>
                    )}
                  </>
                }
                trailing=''
                trailingLabel={relTime(e.occurredAt)}
              />
            );
          })
        )}

        <Button kind='secondary' block style={{marginTop: 14}} onClick={() => {
          const csv = ['type,summary,actor,occurredAt', ...filtered.map((e: api.ActivityEntry) => {
            const eAny = e as unknown as {actorEmail?: string; actorUserId?: string};
            const actor = eAny.actorEmail || eAny.actorUserId || '';
            return `${e.eventType ?? ''},${(e.summary ?? '').replace(/,/g, ';')},${actor},${e.occurredAt ?? ''}`;
          })].join('\n');
          const blob = new Blob([csv], {type: 'text/csv'});
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `activity-${id}-${new Date().toISOString().slice(0, 10)}.csv`;
          a.click();
          URL.revokeObjectURL(url);
        }}>
          Export filtered as CSV
        </Button>
      </Feed>
    </>
  );
}
