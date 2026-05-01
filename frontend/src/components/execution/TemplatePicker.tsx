import {useEffect, useState} from 'react';
import {
  listSpecTemplates,
  createProjectSpecTemplate,
  deleteSpecTemplate,
  type SpecTemplate,
} from '@/lib/api';

interface Props {
  projectId: string;
  /** Current textarea value — used as the initial content for "Save current". */
  currentRequirement: string;
  /** Called with the chosen template's text; ExecutionSetup writes it into the textarea. */
  onPick: (text: string) => void;
}

/**
 * v2.5.2 (Phase 4.2 / UX-2.3) — Spec template picker for ExecutionSetup.
 *
 * Two-section dropdown:
 *  - Project templates (owner_type=PROJECT, owner_id=projectId)
 *  - Org templates (owner_type=ORG, joined backend-side from the project's org)
 *
 * Slot interpolation ({@code {{SLOT}}}) is intentionally left as plain text in
 * v1 — the picker drops the template into the textarea verbatim and the user
 * fills slots themselves. Inline slot tabbing is a follow-up (UX-2.3 polish).
 */
export default function TemplatePicker({projectId, currentRequirement, onPick}: Props) {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<SpecTemplate[]>([]);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    listSpecTemplates(projectId)
      .then(setItems)
      .catch((e) => {
        setError(e instanceof Error ? e.message : String(e));
      });
  };

  useEffect(() => {
    if (open) load(); /* eslint-disable-next-line */
  }, [open, projectId]);

  const projectItems = items.filter((t) => t.ownerType === 'PROJECT');
  const orgItems = items.filter((t) => t.ownerType === 'ORG');

  const saveCurrent = async () => {
    if (!newName.trim() || !currentRequirement.trim()) {
      setError('Need both a name and some requirement text to save.');
      return;
    }
    try {
      await createProjectSpecTemplate(projectId, {
        name: newName.trim(),
        requirementTemplate: currentRequirement,
      });
      setNewName('');
      setCreating(false);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const remove = async (id: string) => {
    try {
      await deleteSpecTemplate(projectId, id);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div className='relative inline-block'>
      <button
        type='button'
        onClick={() => setOpen((v) => !v)}
        className='text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary'
      >
        Templates ▾
      </button>
      {open && (
        <div className='absolute right-0 mt-1 w-80 z-20 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] shadow-md p-2 space-y-2'>
          {error && <div className='text-[11px] text-accent-red'>{error}</div>}

          <Section title={`Project templates (${projectItems.length})`}>
            {projectItems.length === 0 ? (
              <div className='text-[11px] text-muted'>None yet.</div>
            ) : (
              projectItems.map((t) => (
                <TemplateRow
                  key={t.id}
                  t={t}
                  onPick={(text) => {
                    onPick(text);
                    setOpen(false);
                  }}
                  onDelete={() => remove(t.id)}
                />
              ))
            )}
          </Section>

          <Section title={`Org templates (${orgItems.length})`}>
            {orgItems.length === 0 ? (
              <div className='text-[11px] text-muted'>None yet.</div>
            ) : (
              orgItems.map((t) => (
                <TemplateRow
                  key={t.id}
                  t={t}
                  onPick={(text) => {
                    onPick(text);
                    setOpen(false);
                  }}
                  onDelete={() => remove(t.id)}
                />
              ))
            )}
          </Section>

          <div className='border-t border-[var(--border-default)] pt-2'>
            {creating ? (
              <div className='space-y-1'>
                <input
                  type='text'
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  placeholder='Template name'
                  className='w-full text-xs rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] px-2 py-1'
                />
                <div className='text-[9px] text-muted'>
                  Saves the current requirement text as a project template.
                </div>
                <div className='flex gap-1 justify-end'>
                  <button
                    onClick={() => setCreating(false)}
                    className='text-[11px] text-muted hover:text-primary'
                  >
                    Cancel
                  </button>
                  <button
                    onClick={saveCurrent}
                    className='text-[11px] px-2 py-0.5 rounded-sm bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20'
                  >
                    Save
                  </button>
                </div>
              </div>
            ) : (
              <button
                onClick={() => setCreating(true)}
                disabled={!currentRequirement.trim()}
                title={
                  currentRequirement.trim()
                    ? 'Save the current requirement as a project template'
                    : 'Type a requirement first'
                }
                className='text-[11px] text-muted hover:text-primary disabled:opacity-50'
              >
                + Save current as template
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Section({title, children}: {title: string; children: React.ReactNode}) {
  return (
    <div>
      <div className='text-[9px] uppercase tracking-wide text-muted mb-1'>{title}</div>
      <div className='space-y-0.5'>{children}</div>
    </div>
  );
}

function TemplateRow({
  t,
  onPick,
  onDelete,
}: {
  t: SpecTemplate;
  onPick: (text: string) => void;
  onDelete: () => void;
}) {
  return (
    <div className='flex items-center justify-between gap-2 text-xs px-1 py-0.5 hover:bg-[var(--gray-200)] rounded-sm'>
      <button
        onClick={() => onPick(t.requirementTemplate)}
        className='flex-1 text-left truncate text-primary hover:text-accent-blue'
        title={t.description ?? t.requirementTemplate}
      >
        {t.name}
      </button>
      <button
        onClick={onDelete}
        title='Delete template'
        className='text-muted hover:text-accent-red text-[11px]'
      >
        ×
      </button>
    </div>
  );
}
