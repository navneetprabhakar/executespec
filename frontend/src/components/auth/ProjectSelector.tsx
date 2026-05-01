import {useCallback, useEffect, useRef, useState} from 'react';
import {ChevronDown, FolderOpen, Plus} from 'lucide-react';
import {useAuthStore, selectIsOrgMember} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

interface Props {
  value: string;
  onChange: (id: string) => void;
  required?: boolean;
}

export default function ProjectSelector({value, onChange, required}: Props) {
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [saving, setSaving] = useState(false);
  const isOrgMember = useAuthStore(selectIsOrgMember);
  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api
      .getProjects()
      .then(setProjects)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  // Close dropdown on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
        setCreating(false);
        setNewName('');
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  // Focus input when entering create mode
  useEffect(() => {
    if (creating && inputRef.current) inputRef.current.focus();
  }, [creating]);

  const activeProjects = projects.filter((p) => p.status === 'ACTIVE');
  const selectedProject = activeProjects.find((p) => p.id === value);

  const handleCreate = useCallback(async () => {
    const trimmed = newName.trim();
    if (!trimmed || saving) return;
    setSaving(true);
    try {
      const created = await api.createProject({name: trimmed});
      setProjects((prev) => [...prev, created]);
      onChange(created.id);
      setCreating(false);
      setNewName('');
      setOpen(false);
    } catch {
      // silently fail — user can retry
    } finally {
      setSaving(false);
    }
  }, [newName, saving, onChange]);

  if (loading) return <div className='h-9 animate-pulse rounded-sm bg-[var(--gray-400)]' />;

  return (
    <div ref={containerRef} className='relative'>
      <label className='mb-1 block text-sm text-muted'>
        Project {required && <span className='text-accent-red'>*</span>}
      </label>

      {/* Trigger button */}
      <button
        type='button'
        onClick={() => setOpen((o) => !o)}
        className='w-full flex items-center justify-between rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-left text-sm transition-colors hover:border-slate-500 focus:border-accent-blue focus:outline-none'
      >
        <span className={selectedProject ? 'text-primary' : 'text-muted'}>
          {selectedProject ? (
            <span className='flex items-center gap-1.5'>
              <FolderOpen className='h-3.5 w-3.5 text-cyan-400' />
              {selectedProject.name}
            </span>
          ) : (
            'Select a project…'
          )}
        </span>
        <ChevronDown
          className={`h-4 w-4 text-muted transition-transform ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {/* Dropdown */}
      {open && (
        <div className='absolute z-50 mt-1 w-full rounded-sm border border-[var(--border-default)] bg-[#1e1e2e]  overflow-hidden'>
          <div className='max-h-52 overflow-y-auto py-1'>
            {/* No project option */}
            <button
              type='button'
              onClick={() => {
                onChange('');
                setOpen(false);
              }}
              className='w-full px-3 py-2 text-left text-sm text-secondary hover:bg-[var(--gray-200)] transition-colors'
            >
              No project
            </button>

            {/* Active projects */}
            {activeProjects.map((p) => (
              <button
                key={p.id}
                type='button'
                onClick={() => {
                  onChange(p.id);
                  setOpen(false);
                }}
                className={`w-full px-3 py-2 text-left text-sm flex items-center gap-2 transition-colors ${
                  p.id === value
                    ? 'bg-accent-blue/10 text-primary'
                    : 'text-secondary hover:bg-[var(--gray-200)]'
                }`}
              >
                <FolderOpen className='h-3.5 w-3.5 text-cyan-400 flex-shrink-0' />
                {p.name}
              </button>
            ))}
          </div>

          {/* Create new project — any org member can create */}
          {isOrgMember && (
            <div className='border-t border-[var(--border-default)]'>
              {!creating ? (
                <button
                  type='button'
                  onClick={() => setCreating(true)}
                  className='w-full px-3 py-2 text-left text-sm text-[var(--text-secondary)] hover:bg-[var(--gray-200)] flex items-center gap-2 transition-colors'
                >
                  <Plus className='h-3.5 w-3.5' />
                  Create new project
                </button>
              ) : (
                <div className='p-2 flex gap-2'>
                  <input
                    ref={inputRef}
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault();
                        handleCreate();
                      }
                      if (e.key === 'Escape') {
                        setCreating(false);
                        setNewName('');
                      }
                    }}
                    placeholder='Project name'
                    className='flex-1 rounded border border-[var(--border-default)] bg-[var(--gray-300)] px-2 py-1.5 text-sm text-primary placeholder:text-muted focus:border-accent-blue focus:outline-none'
                  />
                  <button
                    type='button'
                    onClick={handleCreate}
                    disabled={!newName.trim() || saving}
                    className='rounded bg-accent-blue px-3 py-1.5 text-xs font-medium text-primary hover:bg-accent-blue disabled:opacity-40 disabled:cursor-not-allowed transition-colors'
                  >
                    {saving ? '...' : 'Create'}
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
