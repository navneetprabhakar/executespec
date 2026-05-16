import {useEffect, useState} from 'react';
import * as api from '@/lib/api';

/** Module-level cache so per-project surfaces don't re-fetch the list each
 *  time we navigate between tabs. /api/projects is fast; /api/projects/{id}
 *  is slow and occasionally hangs in dev. */
let cachedList: api.ProjectDto[] | null = null;
let cachedAt = 0;
const TTL_MS = 30_000;

async function projectList(): Promise<api.ProjectDto[]> {
  if (cachedList && Date.now() - cachedAt < TTL_MS) return cachedList;
  const list = await api.getProjects();
  cachedList = list;
  cachedAt = Date.now();
  return list;
}

/**
 * Resolve a project's name + summary fields without waiting on the slow
 * single-project endpoint. We try the list first (fast, cached) and
 * fall back to the single-project fetch in the background.
 *
 * Returns `null` until at least the list has loaded — callers should
 * render a loading state instead of the raw UUID when this is null.
 */
export function useProjectMeta(projectId: string | undefined): api.ProjectDto | null {
  const [project, setProject] = useState<api.ProjectDto | null>(null);

  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;
    // Fast path: look up in cached list.
    projectList()
      .then((list) => {
        if (cancelled) return;
        const hit = list.find((p) => p.id === projectId);
        if (hit) setProject(hit);
      })
      .catch(() => {
        /* swallow */
      });
    // Best-effort detail fetch (may take a while or fail).
    api
      .getProject(projectId)
      .then((p) => {
        if (!cancelled && p) setProject(p);
      })
      .catch(() => {
        /* keep the list value */
      });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  return project;
}
