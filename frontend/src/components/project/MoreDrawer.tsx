/**
 * MoreDrawer — slide-over panel that holds the long-tail surfaces moved
 * out of the top-level tab strip (per UX & Wireframe Report §5.3).
 *
 * Holds: Settings · Generated Docs · Memory · Activity (full feed) · Team
 * · Project Docs (RAG uploads).
 *
 * Slides in from the right. Body scrolls; header pinned. Closes on backdrop
 * click, ESC, or X. Body scroll lock while open.
 */
import {useEffect, useState, useRef} from 'react';
import {X} from 'lucide-react';
import {cn} from '@/lib/utils';
import GeneratedDocsPanel from './GeneratedDocsPanel';
import ProjectMemoryPanel from './ProjectMemoryPanel';
import ProjectDocumentsPanel from './ProjectDocumentsPanel';
import ActivityTab from './ActivityTab';
import * as api from '@/lib/api';
import {useState as useReactState} from 'react';

export type DrawerPanel = 'settings' | 'docs' | 'memory' | 'activity' | 'team' | 'documents';

const PANEL_LABELS: Record<DrawerPanel, string> = {
  settings: 'Project settings',
  docs: 'Generated docs',
  memory: 'Project memory',
  activity: 'Activity feed',
  team: 'Team',
  documents: 'Reference docs',
};

interface MoreDrawerProps {
  open: boolean;
  panel: DrawerPanel;
  onClose: () => void;
  onPanelChange: (p: DrawerPanel) => void;
  projectId: string;
  project: api.ProjectDto | null;
  onProjectUpdate: (p: api.ProjectDto) => void;
}

export default function MoreDrawer({
  open,
  panel,
  onClose,
  onPanelChange,
  projectId,
  project,
  onProjectUpdate,
}: MoreDrawerProps) {
  const closeBtnRef = useRef<HTMLButtonElement>(null);

  // ESC to close + body scroll lock + initial focus on the close button
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    closeBtnRef.current?.focus();
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div
      className='fixed inset-0 z-40'
      role='dialog'
      aria-modal='true'
      aria-label='More project surfaces'
    >
      {/* Backdrop */}
      <div
        className='absolute inset-0 bg-black/40 backdrop-blur-[1px]'
        onClick={onClose}
        aria-hidden='true'
      />
      {/* Panel */}
      <aside
        className='absolute right-0 top-0 bottom-0 w-full sm:w-[480px] bg-[var(--bg-primary)] border-l border-[var(--border-default)] shadow-xl flex flex-col'
        role='document'
      >
        {/* Header */}
        <header className='flex items-center justify-between px-4 py-3 border-b border-[var(--border-default)] flex-shrink-0'>
          <div className='font-mono text-[10.5px] uppercase tracking-[0.08em] text-muted'>More</div>
          <button
            ref={closeBtnRef}
            onClick={onClose}
            aria-label='Close'
            title='Close (Esc)'
            className='text-muted hover:text-primary p-1 rounded-sm hover:bg-[var(--gray-200)]'
          >
            <X className='h-4 w-4' />
          </button>
        </header>

        {/* Panel chips */}
        <nav
          className='flex flex-wrap gap-1 px-4 py-2 border-b border-[var(--border-default)] flex-shrink-0'
          role='tablist'
        >
          {(Object.keys(PANEL_LABELS) as DrawerPanel[]).map((p) => (
            <button
              key={p}
              role='tab'
              aria-selected={panel === p}
              onClick={() => onPanelChange(p)}
              className={cn(
                'px-2 py-0.5 text-[11px] font-medium rounded-sm transition-colors',
                panel === p
                  ? 'bg-accent-blue/10 text-accent-blue border border-accent-blue/30'
                  : 'border border-transparent text-muted hover:text-secondary hover:border-[var(--border-default)]',
              )}
            >
              {PANEL_LABELS[p]}
            </button>
          ))}
        </nav>

        {/* Body */}
        <div className='flex-1 overflow-y-auto p-4'>
          {panel === 'settings' && project && (
            <DrawerSettingsPanel
              projectId={projectId}
              project={project}
              onUpdate={onProjectUpdate}
            />
          )}
          {panel === 'docs' && <GeneratedDocsPanel projectId={projectId} />}
          {panel === 'memory' && <ProjectMemoryPanel projectId={projectId} />}
          {panel === 'activity' && <ActivityTab projectId={projectId} />}
          {panel === 'documents' && <ProjectDocumentsPanel projectId={projectId} />}
          {panel === 'team' && (
            <div className='text-xs text-muted'>
              Team management is available via the existing Project members page.
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}

/**
 * DrawerSettingsPanel — same fields as the legacy ProjectSettingsPanel
 * (githubOwner) but inline in the drawer. Kept here to avoid leaking a
 * single-purpose helper back into ProjectDashboardPage.
 */
function DrawerSettingsPanel({
  projectId,
  project,
  onUpdate,
}: {
  projectId: string;
  project: api.ProjectDto;
  onUpdate: (p: api.ProjectDto) => void;
}) {
  const [owner, setOwner] = useReactState(project.githubOwner ?? '');
  const [saving, setSaving] = useReactState(false);
  const dirty = owner.trim() !== (project.githubOwner ?? '').trim();
  const save = async () => {
    if (!dirty) return;
    setSaving(true);
    try {
      const updated = await api.updateProjectSettings(projectId, {
        githubOwner: owner.trim() || undefined,
      });
      onUpdate(updated);
    } finally {
      setSaving(false);
    }
  };
  return (
    <div className='space-y-3'>
      <div>
        <h3 className='text-xs font-medium text-secondary mb-2'>Project settings</h3>
        <label className='block text-[11px] text-muted mb-1'>GitHub Owner</label>
        <div className='flex items-center gap-2'>
          <input
            type='text'
            value={owner}
            onChange={(e) => setOwner(e.target.value)}
            placeholder='e.g. my-org'
            className='flex-1 text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] bg-[var(--bg-primary)] text-primary placeholder:text-muted/50 focus:outline-none focus:border-accent-blue'
          />
          {dirty && (
            <button
              onClick={save}
              disabled={saving}
              className='text-[11px] font-medium px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50'
            >
              {saving ? 'Saving...' : 'Save'}
            </button>
          )}
        </div>
        <p className='text-[10px] text-muted mt-1'>
          Default GitHub org/owner for new repos created during a Launch.
        </p>
      </div>

      <div className='pt-2 border-t border-[var(--border-default)]'>
        <h3 className='text-xs font-medium text-secondary mb-2'>Project metadata</h3>
        <dl className='text-[11px] space-y-1'>
          <div className='flex justify-between'>
            <dt className='text-muted'>Status</dt>
            <dd className='text-secondary font-mono'>{project.status}</dd>
          </div>
          <div className='flex justify-between'>
            <dt className='text-muted'>Provider</dt>
            <dd className='text-secondary font-mono'>{project.aiProvider ?? '—'}</dd>
          </div>
          <div className='flex justify-between'>
            <dt className='text-muted'>Project ID</dt>
            <dd className='text-secondary font-mono text-[10px]'>{projectId}</dd>
          </div>
        </dl>
      </div>
    </div>
  );
}

/**
 * useDrawerState — small hook the parent page calls to wire ?drawer=foo
 * deep links and the global "open drawer" event from PulseTab.
 */
export function useDrawerState(): {
  open: boolean;
  panel: DrawerPanel;
  setOpen: (o: boolean) => void;
  setPanel: (p: DrawerPanel) => void;
} {
  const [open, setOpen] = useState(false);
  const [panel, setPanel] = useState<DrawerPanel>('settings');

  useEffect(() => {
    const sync = () => {
      const sp = new URLSearchParams(window.location.search);
      const d = sp.get('drawer');
      if (d && (Object.keys(PANEL_LABELS) as DrawerPanel[]).includes(d as DrawerPanel)) {
        setPanel(d as DrawerPanel);
        setOpen(true);
      } else {
        setOpen(false);
      }
    };
    sync();
    window.addEventListener('drawer-state', sync);
    window.addEventListener('popstate', sync);
    return () => {
      window.removeEventListener('drawer-state', sync);
      window.removeEventListener('popstate', sync);
    };
  }, []);

  return {open, panel, setOpen, setPanel};
}
