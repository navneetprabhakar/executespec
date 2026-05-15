import {useEffect, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TabsStrip} from '@/signal/components/Primitives';
import * as api from '@/lib/api';

/**
 * Project tab strip — shared across every project sub-page. Counts on each
 * tab (repos · runs · code · members) come from the dashboard endpoint so
 * the active set lines up with what the user actually has.
 */
export function ProjectTabs({active}: {active: 'compose' | 'pulse' | 'repos' | 'runs' | 'code' | 'chat' | 'activity' | 'members' | 'settings'}) {
  const {projectId} = useParams();
  const id = projectId || '';
  const base = `/projects/${id}`;
  const [counts, setCounts] = useState<{repos?: number; runs?: number; code?: number; members?: number; chat?: number}>({});

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    Promise.allSettled([
      api.getProjectDashboard(id).catch(() => null),
      api.getProjectMembers(id).catch(() => [] as api.ProjectMemberDto[]),
    ]).then(([dash, members]) => {
      if (cancelled) return;
      const d = (dash.status === 'fulfilled' ? dash.value : null) as unknown as {repoCount?: number; totalRuns?: number; openFindings?: number} | null;
      const m = members.status === 'fulfilled' ? members.value : [];
      setCounts({
        repos: d?.repoCount,
        runs: d?.totalRuns,
        code: d?.openFindings,
        members: m.length,
      });
    });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const label = (l: string, n?: number) => (n != null ? `${l} · ${n}` : l);
  return (
    <TabsStrip
      items={[
        // Compose is the default landing — same UX as v3/dev. The base path
        // (/projects/:id) renders ProjectComposePage now.
        {label: 'Compose', to: base, active: active === 'compose'},
        {label: 'Pulse', to: `${base}/pulse`, active: active === 'pulse'},
        {label: label('Repos', counts.repos), to: `${base}/repos`, active: active === 'repos'},
        {label: label('Runs', counts.runs), to: `${base}/runs`, active: active === 'runs'},
        {label: label('Code', counts.code), to: `${base}/code`, active: active === 'code'},
        {label: 'Chat', to: `${base}/chat`, active: active === 'chat'},
        {label: 'Activity', to: `${base}/activity`, active: active === 'activity'},
        {label: label('Members', counts.members), to: `${base}/members`, active: active === 'members'},
        {label: 'Settings', to: `${base}/settings`, active: active === 'settings'},
      ]}
    />
  );
}
