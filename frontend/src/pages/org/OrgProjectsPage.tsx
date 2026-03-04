import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ChevronDown, ChevronUp, FolderOpen, FolderArchive,
  Users, Plus, X, Eye, AlertCircle,
} from 'lucide-react';
import { useAuthStore, selectIsOrgAdmin } from '@/stores/useAuthStore';
import { useRunStore } from '@/stores/useRunStore';
import * as api from '@/lib/api';
import { cn } from '@/lib/utils';

// ── Sub-component: member row ──────────────────────────────────────────────────
function MemberRow({
  member,
  canManage,
  onRemove,
}: {
  member: api.ProjectMemberDto;
  canManage: boolean;
  onRemove: (userId: string) => void;
}) {
  return (
    <div className="flex items-center justify-between py-2 px-3 rounded-lg hover:bg-white/[0.03] transition-colors">
      <div className="flex items-center gap-3 min-w-0">
        <div className="h-7 w-7 rounded-full bg-accent-violet/20 flex items-center justify-center text-xs font-semibold text-accent-violet flex-shrink-0">
          {member.firstName?.[0]?.toUpperCase() ?? member.email[0].toUpperCase()}
        </div>
        <div className="min-w-0">
          <p className="text-sm text-white truncate">{member.firstName} {member.lastName}</p>
          <p className="text-xs text-zinc-500 truncate">{member.email}</p>
        </div>
      </div>
      <div className="flex items-center gap-2 flex-shrink-0 ml-2">
        <span className="text-xs px-2 py-0.5 rounded-full bg-accent-violet/10 text-accent-violet">
          {member.projectRole}
        </span>
        {canManage && (
          <button
            onClick={() => onRemove(member.userId)}
            className="text-zinc-600 hover:text-red-400 transition-colors p-1 rounded"
            title="Remove from project"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    </div>
  );
}

// ── Sub-component: add member form ─────────────────────────────────────────────
function AddMemberForm({
  projectId,
  existingMemberIds,
  orgMembers,
  onAdded,
}: {
  projectId: string;
  existingMemberIds: Set<string>;
  orgMembers: api.OrgMemberDto[];
  onAdded: () => void;
}) {
  const [userId, setUserId] = useState('');
  const [role, setRole] = useState('DEVELOPER');
  const [adding, setAdding] = useState(false);
  const [err, setErr] = useState('');

  const eligible = orgMembers.filter((m) => !existingMemberIds.has(m.userId));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!userId) return;
    setErr('');
    setAdding(true);
    try {
      await api.addProjectMember(projectId, userId, role);
      setUserId('');
      onAdded();
    } catch (ex: any) {
      setErr(ex.message ?? 'Failed to add member');
    } finally {
      setAdding(false);
    }
  };

  if (eligible.length === 0) {
    return (
      <p className="text-xs text-zinc-500 mt-2 italic">
        All org members are already in this project.
      </p>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="flex items-end gap-2 mt-3">
      <div className="flex-1 min-w-0">
        <label className="text-xs text-zinc-500 mb-1 block">Add member</label>
        <select
          value={userId}
          onChange={(e) => setUserId(e.target.value)}
          className="w-full rounded-lg border border-white/[0.08] bg-zinc-900 px-3 py-1.5 text-sm text-white focus:border-accent-violet focus:outline-none"
        >
          <option value="">— select org member —</option>
          {eligible.map((m) => (
            <option key={m.userId} value={m.userId}>
              {m.firstName} {m.lastName} ({m.email})
            </option>
          ))}
        </select>
      </div>
      <div>
        <label className="text-xs text-zinc-500 mb-1 block">Role</label>
        <select
          value={role}
          onChange={(e) => setRole(e.target.value)}
          className="rounded-lg border border-white/[0.08] bg-zinc-900 px-3 py-1.5 text-sm text-white focus:border-accent-violet focus:outline-none"
        >
          <option value="DEVELOPER">Developer</option>
          <option value="PROJECT_LEAD">Project Lead</option>
          <option value="VIEWER">Viewer</option>
        </select>
      </div>
      <button
        type="submit"
        disabled={!userId || adding}
        className="px-3 py-1.5 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 disabled:opacity-50 transition-colors flex items-center gap-1"
      >
        <Plus className="h-3.5 w-3.5" />
        {adding ? 'Adding…' : 'Add'}
      </button>
      {err && (
        <p className="text-xs text-red-400 absolute mt-8">{err}</p>
      )}
    </form>
  );
}

// ── Sub-component: project card ────────────────────────────────────────────────
function ProjectCard({
  project,
  orgMembers,
  isAdmin,
  onArchive,
  onViewRuns,
}: {
  project: api.ProjectDto;
  orgMembers: api.OrgMemberDto[];
  isAdmin: boolean;
  onArchive: (id: string) => void;
  onViewRuns: (id: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [members, setMembers] = useState<api.ProjectMemberDto[]>([]);
  const [membersLoaded, setMembersLoaded] = useState(false);
  const [membersLoading, setMembersLoading] = useState(false);

  const loadMembers = async () => {
    if (membersLoaded) return;
    setMembersLoading(true);
    try {
      const data = await api.getProjectMembers(project.id);
      setMembers(data);
      setMembersLoaded(true);
    } catch {
      // ignore
    } finally {
      setMembersLoading(false);
    }
  };

  const handleToggle = () => {
    const next = !expanded;
    setExpanded(next);
    if (next) loadMembers();
  };

  const handleRemoveMember = async (userId: string) => {
    if (!confirm('Remove this member from the project?')) return;
    await api.removeProjectMember(project.id, userId);
    setMembers((prev) => prev.filter((m) => m.userId !== userId));
  };

  const handleMemberAdded = async () => {
    setMembersLoaded(false);
    await loadMembers();
  };

  const isArchived = project.status === 'ARCHIVED';
  const existingIds = new Set(members.map((m) => m.userId));

  return (
    <div className={cn(
      'rounded-xl border transition-colors',
      isArchived
        ? 'border-white/[0.04] bg-white/[0.01]'
        : 'border-white/[0.08] bg-[#0f1117]'
    )}>
      {/* Card header */}
      <div
        className="flex items-center justify-between px-5 py-4 cursor-pointer select-none"
        onClick={handleToggle}
        role="button"
        aria-expanded={expanded}
      >
        <div className="flex items-center gap-3 min-w-0">
          {isArchived
            ? <FolderArchive className="h-4 w-4 text-zinc-600 flex-shrink-0" />
            : <FolderOpen className="h-4 w-4 text-accent-violet flex-shrink-0" />
          }
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <p className={cn('font-semibold truncate', isArchived ? 'text-zinc-500' : 'text-white')}>
                {project.name}
              </p>
              <span className={cn(
                'text-xs px-2 py-0.5 rounded-full flex-shrink-0',
                isArchived
                  ? 'bg-zinc-800 text-zinc-500'
                  : 'bg-emerald-900/40 text-emerald-400'
              )}>
                {project.status}
              </span>
            </div>
            {project.description && (
              <p className="text-xs text-zinc-500 truncate">{project.description}</p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0 ml-4">
          {!isArchived && (
            <button
              onClick={(e) => { e.stopPropagation(); onViewRuns(project.id); }}
              className="flex items-center gap-1 text-xs text-zinc-400 hover:text-white border border-white/[0.08] hover:border-white/20 rounded-lg px-3 py-1.5 transition-colors"
              title="Filter runs to this project"
            >
              <Eye className="h-3.5 w-3.5" />
              View Runs
            </button>
          )}
          {expanded
            ? <ChevronUp className="h-4 w-4 text-zinc-500" />
            : <ChevronDown className="h-4 w-4 text-zinc-500" />
          }
        </div>
      </div>

      {/* Expanded body */}
      {expanded && (
        <div className="px-5 pb-4 border-t border-white/[0.05] pt-4 space-y-4">
          {/* Members section */}
          <div>
            <div className="flex items-center gap-2 mb-2">
              <Users className="h-3.5 w-3.5 text-zinc-500" />
              <span className="text-xs font-medium text-zinc-400 uppercase tracking-wider">Members</span>
              {membersLoaded && (
                <span className="text-xs text-zinc-600">({members.length})</span>
              )}
            </div>
            {membersLoading ? (
              <div className="text-xs text-zinc-600 py-2">Loading members…</div>
            ) : membersLoaded && members.length === 0 ? (
              <div className="flex items-center gap-2 py-2 text-xs text-zinc-500">
                <AlertCircle className="h-3.5 w-3.5" />
                No members assigned yet.
              </div>
            ) : (
              <div className="space-y-0.5">
                {members.map((m) => (
                  <MemberRow
                    key={m.userId}
                    member={m}
                    canManage={isAdmin && !isArchived}
                    onRemove={handleRemoveMember}
                  />
                ))}
              </div>
            )}

            {/* Add member form — admin + active project only */}
            {isAdmin && !isArchived && membersLoaded && (
              <AddMemberForm
                projectId={project.id}
                existingMemberIds={existingIds}
                orgMembers={orgMembers}
                onAdded={handleMemberAdded}
              />
            )}
          </div>

          {/* Archive action */}
          {isAdmin && !isArchived && (
            <div className="flex justify-end pt-2 border-t border-white/[0.04]">
              <button
                onClick={() => onArchive(project.id)}
                className="flex items-center gap-1.5 text-xs text-zinc-500 hover:text-red-400 transition-colors"
              >
                <FolderArchive className="h-3.5 w-3.5" />
                Archive project
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────
export default function OrgProjectsPage() {
  const isAdmin = useAuthStore(selectIsOrgAdmin);
  const { setProjectFilter } = useRunStore();
  const navigate = useNavigate();

  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [orgMembers, setOrgMembers] = useState<api.OrgMemberDto[]>([]);
  const [loading, setLoading] = useState(true);

  // Create form
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [desc, setDesc] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');

  useEffect(() => {
    const loadData = async () => {
      const [projs, members] = await Promise.all([
        api.getProjects().catch(() => [] as api.ProjectDto[]),
        isAdmin ? api.getOrgMembers().catch(() => [] as api.OrgMemberDto[]) : Promise.resolve([] as api.OrgMemberDto[]),
      ]);
      setProjects(projs);
      setOrgMembers(members);
      setLoading(false);
    };
    loadData();
  }, [isAdmin]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreateError('');
    setCreating(true);
    try {
      const p = await api.createProject({ name, description: desc || undefined });
      setProjects((prev) => [p, ...prev]);
      setName('');
      setDesc('');
      setShowCreate(false);
    } catch (err: any) {
      setCreateError(err.message ?? 'Failed to create project');
    } finally {
      setCreating(false);
    }
  };

  const handleArchive = async (id: string) => {
    if (!confirm('Archive this project? No new runs will be linked to it.')) return;
    const updated = await api.archiveProject(id);
    setProjects((prev) => prev.map((p) => (p.id === id ? updated : p)));
  };

  const handleViewRuns = (projectId: string) => {
    setProjectFilter(projectId);
    navigate('/');
  };

  const active = projects.filter((p) => p.status === 'ACTIVE');
  const archived = projects.filter((p) => p.status === 'ARCHIVED');

  return (
    <div className="h-full overflow-y-auto p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">Projects</h1>
          <p className="text-sm text-zinc-500 mt-1">
            Group runs into projects and control team access.
          </p>
        </div>
        {isAdmin && (
          <button
            onClick={() => setShowCreate((v) => !v)}
            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 transition-colors"
          >
            <Plus className="h-4 w-4" />
            New Project
          </button>
        )}
      </div>

      {/* Create form (collapsible) */}
      {isAdmin && showCreate && (
        <div className="rounded-xl border border-white/[0.08] bg-[#0f1117] p-5">
          <h2 className="text-base font-semibold text-white mb-4">Create project</h2>
          <form onSubmit={handleCreate} className="space-y-3">
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              placeholder="Project name"
              className="w-full rounded-lg border border-white/[0.08] bg-zinc-900 px-3 py-2 text-sm text-white placeholder-zinc-600 focus:border-accent-violet focus:outline-none"
            />
            <input
              type="text"
              value={desc}
              onChange={(e) => setDesc(e.target.value)}
              placeholder="Description (optional)"
              className="w-full rounded-lg border border-white/[0.08] bg-zinc-900 px-3 py-2 text-sm text-white placeholder-zinc-600 focus:border-accent-violet focus:outline-none"
            />
            {createError && <p className="text-sm text-red-400">{createError}</p>}
            <div className="flex gap-3">
              <button
                type="submit"
                disabled={creating || !name.trim()}
                className="px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 disabled:opacity-50 transition-colors"
              >
                {creating ? 'Creating…' : 'Create'}
              </button>
              <button
                type="button"
                onClick={() => setShowCreate(false)}
                className="px-4 py-2 rounded-lg text-zinc-400 hover:text-white text-sm transition-colors"
              >
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Loading */}
      {loading && (
        <div className="text-zinc-500 text-sm">Loading projects…</div>
      )}

      {/* Active projects */}
      {!loading && (
        <>
          {active.length === 0 && archived.length === 0 && (
            <div className="text-center py-12">
              <FolderOpen className="h-10 w-10 text-zinc-700 mx-auto mb-3" />
              <p className="text-zinc-400 font-medium">No projects yet</p>
              <p className="text-zinc-600 text-sm mt-1">
                {isAdmin ? 'Create your first project above.' : 'Your org admin hasn\'t created any projects yet.'}
              </p>
            </div>
          )}

          {active.length > 0 && (
            <div className="space-y-3">
              {active.map((p) => (
                <ProjectCard
                  key={p.id}
                  project={p}
                  orgMembers={orgMembers}
                  isAdmin={isAdmin}
                  onArchive={handleArchive}
                  onViewRuns={handleViewRuns}
                />
              ))}
            </div>
          )}

          {/* Archived projects (collapsible section) */}
          {archived.length > 0 && (
            <ArchivedSection
              projects={archived}
              orgMembers={orgMembers}
              isAdmin={isAdmin}
              onViewRuns={handleViewRuns}
            />
          )}
        </>
      )}
    </div>
  );
}

// ── Archived section (collapsed by default) ────────────────────────────────────
function ArchivedSection({
  projects,
  orgMembers,
  isAdmin,
  onViewRuns,
}: {
  projects: api.ProjectDto[];
  orgMembers: api.OrgMemberDto[];
  isAdmin: boolean;
  onViewRuns: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);

  return (
    <div>
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2 text-sm text-zinc-500 hover:text-zinc-300 transition-colors mb-3"
      >
        {open ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
        Archived projects ({projects.length})
      </button>
      {open && (
        <div className="space-y-3 opacity-60">
          {projects.map((p) => (
            <ProjectCard
              key={p.id}
              project={p}
              orgMembers={orgMembers}
              isAdmin={isAdmin}
              onArchive={() => {}}
              onViewRuns={onViewRuns}
            />
          ))}
        </div>
      )}
    </div>
  );
}
