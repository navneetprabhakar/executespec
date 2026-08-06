import {ChevronRight} from 'lucide-react';
import {Link, useParams} from 'react-router-dom';
import {ProjectTabs} from '@/ide/IdeProjectTabs';
import {ICON_STROKE} from '@/ide/IdeIcons';

export function IdeProjectSetup() {
  const {projectId = ''} = useParams();
  const rows = [
    {
      to: `/projects/${projectId}/members`,
      label: 'Members and sharing',
      sub: 'Collaborators, org members, share links',
    },
    {
      to: `/projects/${projectId}/repos`,
      label: 'Repositories',
      sub: 'Connected source repos and workspace health',
    },
    {
      to: `/projects/${projectId}/settings`,
      label: 'Project settings',
      sub: 'Name, description, default branch, lifecycle',
    },
    {
      to: `/projects/${projectId}/system-map`,
      label: 'System map',
      sub: 'Architecture, codemaps, generated docs',
    },
    {
      to: `/projects/${projectId}/activity`,
      label: 'Activity',
      sub: 'Recent project and operational events',
    },
  ];

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <h1 className='ide-h1'>Project setup</h1>
      <div className='ide-sub'>Access, repositories, and project context.</div>
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
            {/* "Open" was a word doing an affordance's job, and it read as a
                control that was not one. The chevron says the same thing and
                tracks the type ramp. The row itself is the link. */}
            <span className='gf-rowchevron' aria-hidden='true'>
              <ChevronRight size='1.15em' strokeWidth={ICON_STROKE} />
            </span>
          </Link>
        ))}
      </div>
    </div>
  );
}
