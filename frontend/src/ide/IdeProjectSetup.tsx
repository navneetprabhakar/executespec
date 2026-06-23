import {Link, useParams} from 'react-router-dom';
import {ProjectTabs} from '@/ide/IdeProjectTabs';

export function IdeProjectSetup() {
  const {projectId = ''} = useParams();
  const rows = [
    {
      to: `/projects/${projectId}/members`,
      label: 'Members and sharing',
      sub: 'Invite external collaborators, add org members, manage share links',
    },
    {
      to: `/projects/${projectId}/repos`,
      label: 'Repositories',
      sub: 'Connect source repos and review workspace health',
    },
    {
      to: `/projects/${projectId}/settings`,
      label: 'Project settings',
      sub: 'Name, description, default branch, and project lifecycle',
    },
    {
      to: `/projects/${projectId}/system-map`,
      label: 'System map',
      sub: 'Architecture, codemaps, and generated project documentation',
    },
    {
      to: `/projects/${projectId}/activity`,
      label: 'Activity',
      sub: 'Recent project changes and operational events',
    },
  ];

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Project setup</div>
      <div className='ide-sub'>
        Configure access, repositories, and project context for this workspace.
      </div>
      <div className='ide-card' style={{padding: 0}}>
        {rows.map((row, i) => (
          <Link
            key={row.to}
            to={row.to}
            className='ide-work-row'
            style={{
              borderBottom: i < rows.length - 1 ? '1px solid var(--ide-border)' : 'none',
            }}
          >
            <span className='ide-work-main'>
              <span className='ide-work-title'>{row.label}</span>
              <span className='ide-work-meta'>{row.sub}</span>
            </span>
            <span className='ide-work-provider'>Open</span>
          </Link>
        ))}
      </div>
    </div>
  );
}
