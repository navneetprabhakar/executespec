import {useEffect, useState} from 'react';
import {useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeLoader} from '@/ide/IdeKit';
import {ProjectTabs} from '@/ide/IdeProjectTabs';

export function IdeProjectActivity() {
  const {projectId = ''} = useParams();
  const [feed, setFeed] = useState<Array<Record<string, unknown>> | null>(null);

  useEffect(() => {
    if (!projectId) return;
    api
      .getActivityFeed(projectId, {sinceDays: 30})
      .then((f) =>
        setFeed(
          ((f as unknown as {entries?: unknown[]}).entries as Array<Record<string, unknown>>) ??
            (Array.isArray(f) ? (f as Array<Record<string, unknown>>) : []),
        ),
      )
      .catch(() => setFeed([]));
  }, [projectId]);

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <h1 className='ide-h1'>Activity</h1>
      {feed === null ? (
        <IdeLoader label='Loading activity…' />
      ) : feed.length === 0 ? (
        <div className='ide-empty'>No recent activity.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {feed.map((e, i) => (
            <div
              key={i}
              style={{
                display: 'flex',
                flexWrap: 'wrap',
                gap: 10,
                padding: '10px 16px',
                borderBottom: i < feed.length - 1 ? '1px solid var(--ide-border)' : 'none',
                fontSize: 13,
              }}
            >
              <span style={{color: 'var(--ide-accent)', fontSize: 11, width: 110, flexShrink: 0}}>
                {String(e.eventType || e.type || 'event')}
              </span>
              <span style={{flex: 1, minWidth: 180}}>
                {String(e.summary || e.description || e.message || '')}
              </span>
              <span style={{color: 'var(--ide-fg-muted)', fontSize: 11}}>
                {String(e.actorName || e.actor || '')}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
