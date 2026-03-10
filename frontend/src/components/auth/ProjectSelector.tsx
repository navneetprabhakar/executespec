import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronDown, FolderOpen, Plus } from 'lucide-react';
import { useAuthStore, selectIsOrgAdmin } from '@/stores/useAuthStore';
import * as api from '@/lib/api';

interface Props {
  value: string;
  onChange: (id: string) => void;
  required?: boolean;
}

export default function ProjectSelector({ value, onChange, required }: Props) {
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [saving, setSaving] = useState(false);
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api.getProjects()
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

  const activeProjects = projects.filter(p => p.status === 'ACTIVE');
  const selectedProject = activeProjects.find(p => p.id === value);

  const handleCreate = useCallback(async () => {
    const trimmed = newName.trim();
    if (!trimmed || saving) return;
    setSaving(true);
    try {
      const created = await api.createProject({ name: trimmed });
      setProjects(prev => [...prev, created]);
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

  if (loading) return <div className="h-9 animate-pulse rounded-lg bg-slate-700" />;

  return (
    <div ref={containerRef} className="relative">
      <label className="mb-1 block text-sm text-slate-400">
        Project {required && <span className="text-red-400">*</span>}
      </label>

      {/* Trigger button */}
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-center justify-between rounded-lg border border-slate-600 bg-slate-800 px-3 py-2 text-left text-sm transition-colors hover:border-slate-500 focus:border-purple-500 focus:outline-none"
      >
        <span className={selectedProject ? 'text-white' : 'text-zinc-500'}>
          {selectedProject ? (
            <span className="flex items-center gap-1.5">
              <FolderOpen className="h-3.5 w-3.5 text-cyan-400" />
              {selectedProject.name}
            </span>
          ) : (
            'Select a project\u2026'
          )}
        </span>
        <ChevronDown className={`h-4 w-4 text-zinc-500 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {/* Dropdown */}
      {open && (
        <div className="absolute z-50 mt-1 w-full rounded-lg border border-white/[0.08] bg-[#1e1e2e] shadow-xl overflow-hidden">
          <div className="max-h-52 overflow-y-auto py-1">
            {/* No project option */}
            <button
              type="button"
              onClick={() => { onChange(''); setOpen(false); }}
              className="w-full px-3 py-2 text-left text-sm text-zinc-400 hover:bg-white/[0.04] transition-colors"
            >
              No project
            </button>

            {/* Active projects */}
            {activeProjects.map(p => (
              <button
                key={p.id}
                type="button"
                onClick={() => { onChange(p.id); setOpen(false); }}
                className={`w-full px-3 py-2 text-left text-sm flex items-center gap-2 transition-colors ${
                  p.id === value
                    ? 'bg-purple-500/10 text-white'
                    : 'text-zinc-300 hover:bg-white/[0.04]'
                }`}
              >
                <FolderOpen className="h-3.5 w-3.5 text-cyan-400 flex-shrink-0" />
                {p.name}
              </button>
            ))}
          </div>

          {/* Create new project (ORG_ADMIN / SUPER_ADMIN only) */}
          {isOrgAdmin && (
            <div className="border-t border-white/[0.06]">
              {!creating ? (
                <button
                  type="button"
                  onClick={() => setCreating(true)}
                  className="w-full px-3 py-2 text-left text-sm text-purple-400 hover:bg-white/[0.04] flex items-center gap-2 transition-colors"
                >
                  <Plus className="h-3.5 w-3.5" />
                  Create new project
                </button>
              ) : (
                <div className="p-2 flex gap-2">
                  <input
                    ref={inputRef}
                    value={newName}
                    onChange={e => setNewName(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter') { e.preventDefault(); handleCreate(); }
                      if (e.key === 'Escape') { setCreating(false); setNewName(''); }
                    }}
                    placeholder="Project name"
                    className="flex-1 rounded border border-slate-600 bg-slate-800 px-2 py-1.5 text-sm text-white placeholder:text-zinc-600 focus:border-purple-500 focus:outline-none"
                  />
                  <button
                    type="button"
                    onClick={handleCreate}
                    disabled={!newName.trim() || saving}
                    className="rounded bg-purple-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-purple-500 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
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
