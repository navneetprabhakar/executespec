import {useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeLoader} from '@/ide/IdeKit';
import {LoadError} from '@/ide/LoadError';
import {useAsyncData} from '@/lib/loadState';
import {ProjectTabs} from '@/ide/IdeProjectTabs';
import {actorLabel} from '@/ide/projectMoreUtils';

export function IdeProjectActivity() {
  const {projectId = ''} = useParams();
  // D4 — "No recent activity." is a claim about the project's history.
  const feedState = useAsyncData(
    () =>
      api
        .getActivityFeed(projectId, {sinceDays: 30})
        .then(
          (f) =>
            ((f as unknown as {entries?: unknown[]}).entries as Array<Record<string, unknown>>) ??
            (Array.isArray(f) ? (f as Array<Record<string, unknown>>) : []),
        ),
    [projectId],
    !!projectId,
  );
  const feed = feedState.data;

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <h1 className='ide-h1'>Activity</h1>
      {feedState.failure ? (
        <LoadError failure={feedState.failure} what='activity' onRetry={feedState.reload} />
      ) : feed === null ? (
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
              <span style={{color: 'var(--ide-fg-muted)', fontSize: 11}}>{actorLabel(e)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
