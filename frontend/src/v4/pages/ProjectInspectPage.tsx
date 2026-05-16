/**
 * Project Inspect — v2-native page that hosts the deep workspace panels
 * (architecture / tech-debt / code-review / health / docs / memory)
 * inside the v4 chrome.
 *
 * Implementation: each panel is a self-contained component from the
 * existing v3 codebase (ArchitectureViewer / TechDebtPanel / etc.) — the
 * v2 wrapper provides the LargeTitle + back-button + v4 root,
 * embeds the v3 component as the body, and routes everything inside
 * /a/* so users never leave v2.
 *
 * Same data, same features, v4 chrome.
 */
import {useEffect, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import {ChevronLeft} from 'lucide-react';
import ArchitectureViewer from '@/components/project/ArchitectureViewer';
import TechDebtPanel from '@/components/project/TechDebtPanel';
import CodeQualityPanel from '@/components/project/CodeQualityPanel';
import WorkspaceHealthPanel from '@/components/project/WorkspaceHealthPanel';
import GeneratedDocsPanel from '@/components/project/GeneratedDocsPanel';
import ProjectMemoryPanel from '@/components/project/ProjectMemoryPanel';
import StandardsCompliancePanel from '@/components/project/StandardsCompliancePanel';
import ContractCoveragePanel from '@/components/project/ContractCoveragePanel';
import {getProject, getProjectRepos, type ProjectDto} from '@/lib/api';
import type {ProjectRepoLink} from '@/types/project';
import LargeTitle from '../components/LargeTitle';
import {useV4Context} from '../lib/useV4Context';

type Panel =
  | 'architecture'
  | 'tech-debt'
  | 'review'
  | 'health'
  | 'docs'
  | 'memory'
  | 'standards'
  | 'contracts';

const PANEL_TITLES: Record<Panel, {title: string; subtitle: string}> = {
  architecture: {
    title: 'Architecture',
    subtitle: 'Generated docs · per-repo HLD/LLD · cross-repo synthesis',
  },
  'tech-debt': {
    title: 'Tech debt',
    subtitle: 'Category × severity rollup with one-tap autofix',
  },
  review: {
    title: 'Code review',
    subtitle: 'Whole-repo static + LLM scan · severity-grouped findings',
  },
  health: {
    title: 'Workspace health',
    subtitle: 'Per-repo SHA drift · webhook · open-PR rollup',
  },
  docs: {
    title: 'Generated docs',
    subtitle: 'Explanation + Setup writeups for new contributors',
  },
  memory: {
    title: 'Project memory',
    subtitle: 'Run ledger · per-project facts the planner remembers',
  },
  standards: {
    title: 'Standards compliance',
    subtitle: 'Org-level standards · violations · standing exceptions',
  },
  contracts: {
    title: 'Contract coverage',
    subtitle: 'Cross-repo contract status · drift · per-consumer pins',
  },
};

export default function ProjectInspectPage() {
  const {projectId, panel} = useParams<{projectId: string; panel: Panel}>();
  const navigate = useNavigate();
  const [project, setProject] = useState<ProjectDto | null>(null);
  const [repos, setRepos] = useState<ProjectRepoLink[]>([]);
  const setAgentCtx = useV4Context((s) => s.set);
  const clearAgentCtx = useV4Context((s) => s.clear);

  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;
    Promise.all([
      getProject(projectId),
      getProjectRepos(projectId).catch(() => [] as ProjectRepoLink[]),
    ])
      .then(([p, rs]) => {
        if (cancelled) return;
        setProject(p);
        setRepos(rs);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  useEffect(() => {
    if (project?.name) setAgentCtx({label: project.name, projectId});
    return () => clearAgentCtx();
  }, [project?.name, projectId, setAgentCtx, clearAgentCtx]);

  if (!panel || !PANEL_TITLES[panel]) {
    return (
      <div className='v4-card' style={{padding: 24}}>
        Unknown inspect panel: <code>{panel}</code>
      </div>
    );
  }
  if (!projectId) return null;

  const {title, subtitle} = PANEL_TITLES[panel];

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

      <LargeTitle title={title} subtitle={subtitle} />

      <div className='v4-card' style={{padding: 'clamp(12px, 3vw, 18px)', marginBottom: 24}}>
        {panel === 'architecture' && (
          <ArchitectureViewer projectId={projectId} repoCount={repos.length} />
        )}
        {panel === 'tech-debt' && <TechDebtPanel projectId={projectId} />}
        {panel === 'review' && <CodeQualityPanel projectId={projectId} repos={repos} />}
        {panel === 'health' && <WorkspaceHealthPanel projectId={projectId} />}
        {panel === 'docs' && <GeneratedDocsPanel projectId={projectId} />}
        {panel === 'memory' && <ProjectMemoryPanel projectId={projectId} />}
        {panel === 'standards' && project?.orgId && (
          <StandardsCompliancePanel projectId={projectId} orgId={project.orgId} />
        )}
        {panel === 'contracts' && repos[0] && (
          <ContractCoveragePanel
            projectId={projectId}
            repoId={repos[0].repo.id}
            repoFullName={repos[0].repo.githubRepoFullName}
          />
        )}
        {panel === 'contracts' && !repos[0] && (
          <p
            style={{
              color: 'var(--v4-text-muted)',
              padding: 16,
              fontSize: 'var(--v4-text-sm)',
            }}
          >
            Link a repo first to see contract coverage.
          </p>
        )}
      </div>
    </>
  );
}
