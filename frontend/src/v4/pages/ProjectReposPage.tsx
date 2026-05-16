/**
 * v4 — repos manager page.
 *
 * Embeds the v3 ProjectRepoManager component inside the v4 chrome. Same
 * features (add, sync, install webhook, unlink, edit relationships) —
 * just framed in v4 typography + spacing + a back link to the project
 * home. Users never leave /v4/* when navigating in/out of this page.
 */
import {useEffect, useState} from 'react';
import {Link, useParams} from 'react-router-dom';
import {ChevronLeft} from 'lucide-react';
import ProjectRepoManager from '@/components/project/ProjectRepoManager';
import {getProject, type ProjectDto} from '@/lib/api';
import LargeTitle from '../components/LargeTitle';
import {useV4Context} from '../lib/useV4Context';

export default function ProjectReposPage() {
  const {projectId} = useParams<{projectId: string}>();
  const [project, setProject] = useState<ProjectDto | null>(null);
  const setCtx = useV4Context((s) => s.set);
  const clearCtx = useV4Context((s) => s.clear);

  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;
    getProject(projectId)
      .then((p) => !cancelled && setProject(p))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  useEffect(() => {
    if (project?.name) setCtx({label: project.name, projectId});
    return () => clearCtx();
  }, [project?.name, projectId, setCtx, clearCtx]);

  if (!projectId) return null;

  return (
    <>
      <Link
        to={`/v4/projects/${projectId}`}
        className='v4-press'
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
          padding: '4px 8px 4px 4px',
          marginBottom: 6,
          fontSize: 'var(--v4-text-sm)',
          color: 'var(--v4-text-muted)',
          textDecoration: 'none',
          borderRadius: 8,
        }}
      >
        <ChevronLeft size={16} aria-hidden='true' />
        {project?.name ?? 'Workspace'}
      </Link>

      <LargeTitle title='Repos' subtitle='Linked repositories · branch · webhook · relationships' />

      <div className='v4-card' style={{padding: 'clamp(12px, 3vw, 18px)', marginBottom: 24}}>
        <ProjectRepoManager projectId={projectId} />
      </div>
    </>
  );
}
