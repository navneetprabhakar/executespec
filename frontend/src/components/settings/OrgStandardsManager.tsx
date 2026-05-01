import {useEffect, useState} from 'react';
import {
  listOrgStandards,
  createOrgStandard,
  updateOrgStandard,
  deleteOrgStandard,
  type OrgStandardDto,
} from '@/lib/api';
import {cn} from '@/lib/utils';

/**
 * M-024 (Wave 2) — org-standards CRUD management panel.
 * Backed by the new OrgStandardController (V136 schema). Lets
 * ORG_ADMINs maintain the per-org engineering-standards library
 * without dropping to SQL.
 */
export default function OrgStandardsManager({orgId}: {orgId: string}) {
  const [items, setItems] = useState<OrgStandardDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<OrgStandardDto | null>(null);
  const [creating, setCreating] = useState(false);

  const load = () => {
    listOrgStandards(orgId)
      .then((rows) => {
        setItems(rows);
        setError(null);
      })
      .catch((e) => setError(e?.message ?? 'Failed to load standards'));
  };
  useEffect(load, [orgId]);

  const handleDelete = async (s: OrgStandardDto) => {
    if (!confirm(`Delete standard "${s.standardId}"? This is permanent.`)) return;
    try {
      await deleteOrgStandard(orgId, s.standardId);
      load();
    } catch (e: any) {
      setError(e?.message ?? 'Delete failed');
    }
  };

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] p-4 space-y-3'>
      <div className='flex items-baseline justify-between gap-3 flex-wrap'>
        <div>
          <h3 className='text-sm font-medium text-primary'>Engineering standards</h3>
          <p className='text-xs text-muted mt-0.5'>
            Org-wide rules enforced on every run. Project-level overrides require ≥30 char
            rationale; <code>orgLevelLocked</code> standards cannot be overridden at the project
            level.
          </p>
        </div>
        <button
          onClick={() => {
            setEditing(null);
            setCreating(true);
          }}
          className='rounded-sm bg-accent-blue px-3 py-1.5 text-xs font-medium text-primary hover:bg-accent-blue/80'
        >
          + New standard
        </button>
      </div>

      {error && (
        <div className='rounded-sm bg-status-error/10 border border-status-error/30 px-3 py-2 text-xs text-status-error'>
          {error}
        </div>
      )}

      {!items && !error && <p className='text-xs text-muted'>Loading…</p>}

      {items && items.length === 0 && (
        <p className='text-xs text-muted py-4 text-center'>
          No standards yet. Create one to start enforcing org-wide rules.
        </p>
      )}

      {items && items.length > 0 && (
        <ul className='divide-y divide-[var(--border-default)] rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)]'>
          {items.map((s) => (
            <li key={s.id} className='px-3 py-2.5 flex items-center gap-3'>
              <div className='flex-1 min-w-0'>
                <div className='flex items-baseline gap-2'>
                  <span className='text-sm font-mono text-primary truncate'>{s.standardId}</span>
                  {s.orgLevelLocked && (
                    <span className='text-[10px] uppercase tracking-wider text-status-error border border-status-error/30 rounded-sm px-1 py-0.5'>
                      locked
                    </span>
                  )}
                </div>
                <p className='text-[10px] text-muted mt-0.5'>
                  Updated {new Date(s.updatedAt).toLocaleDateString()} · {s.standardYaml.length}{' '}
                  chars
                </p>
              </div>
              <button
                onClick={() => {
                  setCreating(false);
                  setEditing(s);
                }}
                className='text-xs text-secondary hover:text-primary px-2 py-1 rounded-sm hover:bg-[var(--gray-200)]'
              >
                Edit
              </button>
              <button
                onClick={() => handleDelete(s)}
                className='text-xs text-status-error hover:text-status-error px-2 py-1 rounded-sm hover:bg-status-error/10'
              >
                Delete
              </button>
            </li>
          ))}
        </ul>
      )}

      {(creating || editing) && (
        <StandardEditor
          orgId={orgId}
          existing={editing}
          onClose={() => {
            setEditing(null);
            setCreating(false);
          }}
          onSaved={() => {
            setEditing(null);
            setCreating(false);
            load();
          }}
        />
      )}
    </div>
  );
}

function StandardEditor({
  orgId,
  existing,
  onClose,
  onSaved,
}: {
  orgId: string;
  existing: OrgStandardDto | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [standardId, setStandardId] = useState(existing?.standardId ?? '');
  const [standardYaml, setStandardYaml] = useState(
    existing?.standardYaml ??
      'id: my-standard\ntitle: My standard\nseverity: HIGH\nrule: |\n  Describe the rule here.\n',
  );
  const [orgLevelLocked, setOrgLevelLocked] = useState(existing?.orgLevelLocked ?? false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      if (existing) {
        await updateOrgStandard(orgId, existing.standardId, {standardYaml, orgLevelLocked});
      } else {
        await createOrgStandard(orgId, {
          standardId: standardId.trim(),
          standardYaml,
          orgLevelLocked,
        });
      }
      onSaved();
    } catch (e: any) {
      setErr(e?.message ?? 'Save failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className='fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4'>
      <form
        onSubmit={handleSave}
        className='bg-[var(--gray-100)] border border-[var(--border-default)] rounded-md w-full max-w-2xl max-h-[90vh] overflow-y-auto p-4 space-y-3'
      >
        <div className='flex items-baseline justify-between'>
          <h3 className='text-sm font-medium text-primary'>
            {existing ? `Edit ${existing.standardId}` : 'Create standard'}
          </h3>
          <button type='button' onClick={onClose} className='text-muted hover:text-primary text-sm'>
            ×
          </button>
        </div>

        {!existing && (
          <div>
            <label className='block text-xs text-muted mb-1'>
              Standard ID (immutable; matches the YAML's <code>id</code> field)
            </label>
            <input
              type='text'
              value={standardId}
              onChange={(e) => setStandardId(e.target.value)}
              required
              placeholder='no-direct-db-access'
              className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-sm text-primary font-mono focus:border-accent-blue focus:outline-none'
            />
          </div>
        )}

        <div>
          <label className='block text-xs text-muted mb-1'>YAML body</label>
          <textarea
            value={standardYaml}
            onChange={(e) => setStandardYaml(e.target.value)}
            required
            rows={14}
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-xs text-primary font-mono focus:border-accent-blue focus:outline-none'
          />
        </div>

        <label className='flex items-start gap-2 text-sm text-secondary cursor-pointer'>
          <input
            type='checkbox'
            checked={orgLevelLocked}
            onChange={(e) => setOrgLevelLocked(e.target.checked)}
            className='mt-0.5 accent-accent-blue'
          />
          <span>
            <span className='text-primary'>Lock at org level</span>
            <span className='block text-xs text-muted mt-0.5'>
              Project-level overrides will be logged + ignored. Use for non-negotiable
              security/compliance rules.
            </span>
          </span>
        </label>

        {err && (
          <div className='rounded-sm bg-status-error/10 border border-status-error/30 px-3 py-2 text-xs text-status-error'>
            {err}
          </div>
        )}

        <div className='flex items-center gap-2 pt-1'>
          <button
            type='submit'
            disabled={busy || !standardYaml || (!existing && !standardId.trim())}
            className={cn(
              'rounded-sm px-3 py-1.5 text-xs font-medium',
              busy
                ? 'bg-[var(--gray-300)] text-muted'
                : 'bg-accent-blue text-primary hover:bg-accent-blue/80',
            )}
          >
            {busy ? 'Saving…' : existing ? 'Save changes' : 'Create'}
          </button>
          <button
            type='button'
            onClick={onClose}
            className='rounded-sm border border-[var(--border-default)] px-3 py-1.5 text-xs text-muted hover:text-primary'
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}
