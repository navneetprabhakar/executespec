import {useEffect, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TabsStrip} from '@/signal/components/Primitives';
import * as api from '@/lib/api';

/**
 * Project tab strip — shared across every project sub-page. Counts on each
 * tab (repos · runs · code · members) come from the dashboard endpoint so
 * the active set lines up with what the user actually has.
 *
 * UX-fix 2026-05-15:
 *   - `Members · -1` regression — the dashboard cast was reading non-existent
 *     fields (repoCount/totalRuns/openFindings) and `m.length` could land
 *     before the array was actually populated, yielding negative/garbage
 *     counts in the tab strip. Now we read the real dashboard fields
 *     (activeRuns/completedRuns/failedRuns/totalRepos) and clamp every count
 *     to `>= 0` before rendering.
 *   - Architecture is now a real top-level tab. Previously the only entry
 *     was Repos → "View architecture", which 404'd on projects with no linked
 *     repos.
 */
export function ProjectTabs({active}: {active: 'compose' | 'pulse' | 'repos' | 'runs' | 'code' | 'chat' | 'activity' | 'members' | 'architecture' | 'settings'}) {
  const {projectId} = useParams();
  const id = projectId || '';
  const base = `/projects/${id}`;
  const [counts, setCounts] = useState<{repos?: number; runs?: number; code?: number; members?: number}>({});

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    Promise.allSettled([
      api.getProjectDashboard(id).catch(() => null),
      api.getProjectMembers(id).catch(() => [] as api.ProjectMemberDto[]),
      api.getProjectRepos(id).catch(() => [] as Array<Record<string, unknown>>),
    ]).then(([dash, members, repos]) => {
      if (cancelled) return;
      const d = (dash.status === 'fulfilled' ? dash.value : null) as unknown as {
        activeRuns?: number; completedRuns?: number; failedRuns?: number;
        openFindings?: number; totalRepos?: number;
      } | null;
      const memberArr: api.ProjectMemberDto[] = members.status === 'fulfilled' && Array.isArray(members.value) ? members.value : [];
      const repoArr = repos.status === 'fulfilled' && Array.isArray(repos.value) ? repos.value : [];

      const totalRuns = d ? (d.activeRuns ?? 0) + (d.completedRuns ?? 0) + (d.failedRuns ?? 0) : undefined;
      // Clamp every count to `>= 0` so any garbage signed-int leak (the bug
      // that produced "Members · -1") can never reach the badge.
      const safe = (n?: number) => (typeof n === 'number' && n >= 0 ? n : undefined);
      setCounts({
        repos: safe(d?.totalRepos) ?? safe(repoArr.length),
        runs: safe(totalRuns),
        code: safe(d?.openFindings),
        members: safe(memberArr.length),
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
        {label: 'Architecture', to: `${base}/architecture`, active: active === 'architecture'},
        {label: 'Settings', to: `${base}/settings`, active: active === 'settings'},
      ]}
    />
  );
}
