import {useEffect, useState} from 'react';
import {Link, useNavigate, useSearchParams} from 'react-router-dom';
import {
  ChevronDown,
  ChevronUp,
  FolderOpen,
  FolderArchive,
  Users,
  Plus,
  X,
  Eye,
  AlertCircle,
  Cpu,
  LayoutDashboard,
  FolderGit2,
} from 'lucide-react';
import {useAuthStore, selectIsOrgAdmin} from '@/stores/useAuthStore';
import {useRunStore} from '@/stores/useRunStore';
import * as api from '@/lib/api';
import {cn} from '@/lib/utils';

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
    <div className='flex items-center justify-between py-2 px-3 rounded-sm hover:bg-[var(--gray-200)] transition-colors'>
      <div className='flex items-center gap-3 min-w-0'>
        <div className='h-7 w-7 rounded-full bg-accent-blue/20 flex items-center justify-center text-xs font-semibold text-accent-blue flex-shrink-0'>
          {member.firstName?.[0]?.toUpperCase() ?? member.email[0].toUpperCase()}
        </div>
        <div className='min-w-0'>
          <p className='text-sm text-primary truncate'>
            {member.firstName} {member.lastName}
          </p>
          <p className='text-xs text-muted truncate'>{member.email}</p>
        </div>
      </div>
      <div className='flex items-center gap-2 flex-shrink-0 ml-2'>
        <span className='text-xs px-2 py-0.5 rounded-sm bg-accent-blue/10 text-accent-blue'>
          {member.projectRole}
        </span>
        {canManage && (
          <button
            onClick={() => onRemove(member.userId)}
            className='text-muted hover:text-accent-red transition-colors p-1 rounded'
            title='Remove from project'
          >
            <X className='h-3.5 w-3.5' />
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
      <p className='text-xs text-muted mt-2 italic'>All org members are already in this project.</p>
    );
  }

  return (
    <form onSubmit={handleSubmit} className='flex items-end gap-2 mt-3'>
      <div className='flex-1 min-w-0'>
        <label className='text-xs text-muted mb-1 block'>Add member</label>
        <select
          value={userId}
          onChange={(e) => setUserId(e.target.value)}
          className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-1.5 text-sm text-primary focus:border-accent-blue focus:outline-none'
        >
          <option value=''>— select org member —</option>
          {eligible.map((m) => (
            <option key={m.userId} value={m.userId}>
              {m.firstName} {m.lastName} ({m.email})
            </option>
          ))}
        </select>
      </div>
      <div>
        <label className='text-xs text-muted mb-1 block'>Role</label>
        <select
          value={role}
          onChange={(e) => setRole(e.target.value)}
          className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-1.5 text-sm text-primary focus:border-accent-blue focus:outline-none'
        >
          <option value='DEVELOPER'>Developer</option>
          <option value='PROJECT_LEAD'>Project Lead</option>
          <option value='VIEWER'>Viewer</option>
        </select>
      </div>
      <button
        type='submit'
        disabled={!userId || adding}
        className='px-3 py-1.5 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 disabled:opacity-50 transition-colors flex items-center gap-1'
      >
        <Plus className='h-3.5 w-3.5' />
        {adding ? 'Adding…' : 'Add'}
      </button>
      {err && <p className='text-xs text-accent-red absolute mt-8'>{err}</p>}
    </form>
  );
}

// ── Sub-component: project card ────────────────────────────────────────────────
// v2.5.14 — backend enum renamed GOOGLE → GEMINI in v2.5.13 (see V119 migration).
// Keep `GOOGLE` in the lookup so legacy project payloads still render gracefully
// during the upgrade window, but new writes use GEMINI.
const PROVIDER_LABELS: Record<string, string> = {
  ANTHROPIC: 'Anthropic (Claude)',
  GEMINI: 'Gemini',
  GOOGLE: 'Gemini',
  OPENAI: 'OpenAI (GPT)',
};

function ProjectCard({
  project,
  orgMembers,
  isAdmin,
  onArchive,
  onViewRuns,
  onUpdateProject,
}: {
  project: api.ProjectDto;
  orgMembers: api.OrgMemberDto[];
  isAdmin: boolean;
  onArchive: (id: string) => void;
  onViewRuns: (id: string) => void;
  onUpdateProject: (updated: api.ProjectDto) => void;
}) {
  const navigate = useNavigate();
  const [expanded, setExpanded] = useState(false);
  const [members, setMembers] = useState<api.ProjectMemberDto[]>([]);
  const [membersLoaded, setMembersLoaded] = useState(false);
  const [membersLoading, setMembersLoading] = useState(false);
  const [savingProvider, setSavingProvider] = useState(false);

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
    <div
      className={cn(
        'rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] transition-colors',
        // 2026-05-02: dropped the active/archived bg split. Active was hardcoded
        // `#0f1117` (near-black, pure hex) while everything else on the page —
        // sidebar, hero, KPI tiles, archived cards — uses `var(--gray-100)`. The
        // intended "active stands out" visual was achieved by punching a darker
        // card into a lighter page, which read as inconsistent rather than
        // emphasised. Active vs archived is now conveyed by icon (FolderOpen /
        // FolderArchive) + text colour (text-primary / text-muted) only, matching
        // the rest of the design system. Reintroduces the v2.6.0 UX-overhaul
        // rule: "no raw hex; everything goes through `var(--*)` tokens."
      )}
    >
      {/* Card header — stacks to 2 rows on mobile so chips + actions don't overlap the title */}
      <div
        className='flex flex-col sm:flex-row sm:items-center justify-between px-4 sm:px-5 py-3 sm:py-4 gap-3 cursor-pointer select-none'
        onClick={handleToggle}
        role='button'
        aria-expanded={expanded}
      >
        <div className='flex items-start sm:items-center gap-3 min-w-0'>
          {isArchived ? (
            <FolderArchive className='h-4 w-4 text-muted flex-shrink-0 mt-0.5 sm:mt-0' />
          ) : (
            <FolderOpen className='h-4 w-4 text-accent-blue flex-shrink-0 mt-0.5 sm:mt-0' />
          )}
          <div className='min-w-0 flex-1'>
            <p className={cn('font-semibold truncate', isArchived ? 'text-muted' : 'text-primary')}>
              {project.name}
            </p>
            <div className='flex items-center gap-2 flex-wrap mt-1 sm:mt-0.5'>
              <span
                className={cn(
                  'text-[10px] sm:text-xs px-1.5 sm:px-2 py-0.5 rounded-sm flex-shrink-0 uppercase tracking-wider',
                  isArchived
                    ? 'bg-[var(--gray-200)] text-muted'
                    : 'bg-status-success/20 text-status-success',
                )}
              >
                {project.status}
              </span>
              <span className='text-[10px] sm:text-xs px-1.5 sm:px-2 py-0.5 rounded-sm flex-shrink-0 bg-blue-900/30 text-accent-blue'>
                {PROVIDER_LABELS[project.aiProvider] ?? project.aiProvider}
              </span>
            </div>
            {project.description && (
              <p className='text-xs text-muted truncate mt-1 hidden sm:block'>
                {project.description}
              </p>
            )}
          </div>
        </div>
        <div className='flex items-center gap-2 flex-shrink-0 sm:ml-4 self-stretch sm:self-auto'>
          {!isArchived && (
            <>
              {/* v2.5.7 UX-fix: prominent "Open" CTA so users have a clear path
                  into the project dashboard without hunting through the
                  expanded card body. Mobile: actions take a full row below the title. */}
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  navigate(`/projects/${project.id}/dashboard`);
                }}
                className='flex flex-1 sm:flex-none items-center justify-center gap-1 text-xs font-medium text-accent-blue hover:text-primary border border-accent-blue/40 hover:border-accent-blue bg-accent-blue/10 hover:bg-accent-blue/20 rounded-sm px-3 py-1.5 transition-colors'
                title='Open project dashboard'
              >
                <FolderOpen className='h-3.5 w-3.5' />
                Open
              </button>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onViewRuns(project.id);
                }}
                className='flex flex-1 sm:flex-none items-center justify-center gap-1 text-xs text-secondary hover:text-primary border border-[var(--border-default)] hover:border-white/20 rounded-sm px-3 py-1.5 transition-colors'
                title='Filter runs to this project'
              >
                <Eye className='h-3.5 w-3.5' />
                View Runs
              </button>
            </>
          )}
          {expanded ? (
            <ChevronUp className='h-4 w-4 text-muted flex-shrink-0' />
          ) : (
            <ChevronDown className='h-4 w-4 text-muted flex-shrink-0' />
          )}
        </div>
      </div>

      {/* Expanded body */}
      {expanded && (
        <div className='px-5 pb-4 border-t border-[var(--gray-300)] pt-4 space-y-4'>
          {/* Members section */}
          <div>
            <div className='flex items-center gap-2 mb-2'>
              <Users className='h-3.5 w-3.5 text-muted' />
              <span className='text-xs font-medium text-secondary uppercase tracking-wider'>
                Members
              </span>
              {membersLoaded && <span className='text-xs text-muted'>({members.length})</span>}
            </div>
            {membersLoading ? (
              <div className='text-xs text-muted py-2'>Loading members…</div>
            ) : membersLoaded && members.length === 0 ? (
              <div className='flex items-center gap-2 py-2 text-xs text-muted'>
                <AlertCircle className='h-3.5 w-3.5' />
                No members assigned yet.
              </div>
            ) : (
              <div className='space-y-0.5'>
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

          {/* AI Provider setting — admin + active only */}
          {isAdmin && !isArchived && (
            <div>
              <div className='flex items-center gap-2 mb-2'>
                <Cpu className='h-3.5 w-3.5 text-muted' />
                <span className='text-xs font-medium text-secondary uppercase tracking-wider'>
                  AI Provider
                </span>
              </div>
              <select
                value={project.aiProvider}
                disabled={savingProvider}
                onChange={async (e) => {
                  setSavingProvider(true);
                  try {
                    const updated = await api.updateProjectSettings(project.id, {
                      aiProvider: e.target.value,
                    });
                    onUpdateProject(updated);
                  } catch {
                    /* ignore */
                  } finally {
                    setSavingProvider(false);
                  }
                }}
                className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-1.5 text-sm text-primary focus:border-accent-blue focus:outline-none disabled:opacity-50'
              >
                <option value='ANTHROPIC'>Anthropic (Claude) — Recommended</option>
                <option value='GEMINI'>Gemini</option>
                <option value='OPENAI'>OpenAI (GPT)</option>
              </select>
              <p className='text-xs text-muted mt-1'>
                All pipeline runs in this project will use this provider.
              </p>
            </div>
          )}

          {/* Archive action */}
          {isAdmin && !isArchived && (
            <div className='flex justify-end pt-2 border-t border-[var(--border-default)]'>
              <button
                onClick={() => onArchive(project.id)}
                className='flex items-center gap-1.5 text-xs text-muted hover:text-accent-red transition-colors'
              >
                <FolderArchive className='h-3.5 w-3.5' />
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
  const {setProjectFilter} = useRunStore();
  const navigate = useNavigate();

  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [orgMembers, setOrgMembers] = useState<api.OrgMemberDto[]>([]);
  const [loading, setLoading] = useState(true);

  // Create form
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [desc, setDesc] = useState('');
  const [provider, setProvider] = useState('ANTHROPIC');

  // P-06 (UX audit, 2026-05-03) — when the sidebar (P-07) or another surface
  // routes here with `?new=1`, auto-pop the create form so the user sees the
  // intended affordance immediately. Strip the param after consumption so a
  // refresh doesn't keep re-opening the form.
  const [searchParams, setSearchParams] = useSearchParams();
  useEffect(() => {
    if (searchParams.get('new') === '1' && !showCreate) {
      setShowCreate(true);
      const next = new URLSearchParams(searchParams);
      next.delete('new');
      setSearchParams(next, {replace: true});
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);
  // v2.5.14 Phase 3.5 — starter templates. Clicking a template pre-fills the
  // create form with a representative name + description and opens it.
  const applyTemplate = (t: {name: string; desc: string}) => {
    setName(t.name);
    setDesc(t.desc);
    setShowCreate(true);
  };
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');

  useEffect(() => {
    const loadData = async () => {
      const [projs, members] = await Promise.all([
        api.getProjects().catch(() => [] as api.ProjectDto[]),
        isAdmin
          ? api.getOrgMembers().catch(() => [] as api.OrgMemberDto[])
          : Promise.resolve([] as api.OrgMemberDto[]),
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
      const p = await api.createProject({
        name,
        description: desc || undefined,
        aiProvider: provider,
      });
      setProjects((prev) => [p, ...prev]);
      setName('');
      setDesc('');
      setProvider('ANTHROPIC');
      setShowCreate(false);
      // v2.5.7 UX-fix: jump straight into the new project's dashboard so the
      // next steps (link repos, analyse, launch) are obvious. Previously users
      // landed back on the list with no clear next action.
      // P-06 (UX audit, 2026-05-03): tag the URL with ?firstRun=1 so the
      // dashboard can auto-render the EmptyProjectLanding (FirstRunChecklist)
      // even before the dashboard fetches the empty repos/runs/architecture
      // payload — eliminates the "first paint shows the wrong thing" flash
      // for newly-created projects.
      navigate(`/projects/${p.id}/dashboard?firstRun=1`);
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

  // v2.5.14 — aggregate KPIs for the hero tiles, matching ProjectDashboardPage.
  const activeCount = projects.filter((p) => p.status === 'ACTIVE').length;
  const archivedCount = projects.filter((p) => p.status === 'ARCHIVED').length;
  // P-11 (UX audit, 2026-05-03) — "Top provider" KPI tile retired. It was
  // decorative — operators don't make decisions on "which provider has
  // most projects assigned." The audit recommended replacing with "Spend
  // MTD" but that requires totalCostUsd on the project list DTO which
  // isn't in scope here; for now we drop the tile entirely (4 tiles fit
  // grid-cols-4 cleanly). When the project list DTO grows totalCostUsd,
  // restore here as a Spend tile.

  return (
    <div className='h-full flex flex-col'>
      {/* v2.5.14 — chrome matches ProjectDashboardPage:
          back link + icon-chip hero + status/provider chips + KPI tiles. */}
      <div className='border-b border-[var(--border-default)] px-4 pt-3 pb-3 sm:px-6 space-y-2.5 flex-shrink-0'>
        <Link
          to='/'
          className='inline-flex items-center gap-1 text-xs text-muted hover:text-accent-blue transition-colors'
        >
          ← Back to runs
        </Link>

        <header className='flex items-start justify-between gap-3'>
          <div className='min-w-0 flex items-start gap-3'>
            <span className='inline-flex h-9 w-9 items-center justify-center rounded-md border border-[color-mix(in_srgb,var(--accent-blue)_30%,transparent)] bg-[color-mix(in_srgb,var(--accent-blue)_12%,transparent)] text-accent-blue flex-shrink-0'>
              <FolderGit2 className='h-5 w-5' />
            </span>
            <div className='min-w-0'>
              <div className='font-mono text-[10.5px] font-medium uppercase tracking-[0.08em] text-accent-blue mb-0.5'>
                Projects
              </div>
              <h1 className='text-lg sm:text-xl font-semibold text-primary tracking-[-0.015em] leading-tight'>
                Group runs into projects.
              </h1>
              <p className='text-xs text-secondary max-w-2xl mt-1'>
                Project-scope organises runs, repos, and team access.
                {isAdmin
                  ? ' Use templates below or create from scratch.'
                  : ' Ask your org admin to create a project.'}
              </p>
            </div>
          </div>
          <div className='flex items-center gap-2 flex-shrink-0'>
            <Link
              to='/projects'
              title='Project dashboard'
              className='hidden sm:flex items-center gap-1.5 px-2.5 py-1 rounded-sm border border-[var(--border-default)] text-xs font-medium text-secondary hover:text-primary hover:bg-[var(--gray-200)] transition-colors'
            >
              <LayoutDashboard className='h-3 w-3' />
              Dashboard
            </Link>
            {isAdmin && (
              <button
                onClick={() => setShowCreate((v) => !v)}
                aria-label={showCreate ? 'Close create form' : 'Create new project'}
                className='flex items-center gap-1.5 px-2.5 py-1 rounded-sm bg-accent-blue text-white text-xs font-medium hover:bg-accent-blue-hover transition-colors'
              >
                <Plus className='h-3 w-3' />
                {showCreate ? 'Close' : 'New project'}
              </button>
            )}
          </div>
        </header>

        {/* P-11 (UX audit, 2026-05-03) — KPI tiles trimmed 5 → 4 (Top
            provider retired). 2 / 2 / 4 cols. */}
        <section className='grid grid-cols-2 sm:grid-cols-2 lg:grid-cols-4 gap-2'>
          <OrgMetricTile label='Total' value={projects.length} accent='text-primary' />
          <OrgMetricTile label='Active' value={activeCount} accent='text-accent-blue' />
          <OrgMetricTile label='Archived' value={archivedCount} accent='text-muted' />
          <OrgMetricTile label='Members' value={orgMembers.length || '—'} accent='text-primary' />
        </section>
      </div>

      {/* Body: scrollable content area — same pattern as ProjectDashboardPage. */}
      <div className='flex-1 min-h-0 overflow-y-auto p-4 sm:p-6 space-y-4 sm:space-y-6'>
        {/* v2.5.14 Phase 3.5 — starter templates, shown only when the projects
          list is empty + admin can create. Three representative shapes cover
          ~90% of new-user spec flows. */}
        {isAdmin && !loading && projects.length === 0 && !showCreate && (
          <div className='rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] p-4'>
            <h2 className='text-sm font-semibold text-primary mb-1'>Start from a template</h2>
            <p className='text-xs text-muted mb-3'>
              Three common project shapes. Pick one or create from scratch.
            </p>
            <div className='grid grid-cols-1 sm:grid-cols-3 gap-2'>
              {[
                {
                  name: 'API + backend service',
                  desc: 'Spring Boot or Node/Express service with REST endpoints, DB migrations, and test coverage.',
                  accent: 'border-accent-blue/30',
                },
                {
                  name: 'Frontend web app',
                  desc: 'React/Vite or Next.js app with routing, state, and component tests.',
                  accent: 'border-accent-planner/30',
                },
                {
                  name: 'Tech-debt sweep',
                  desc: 'Bring an existing repo in, analyse architecture, and clear flagged issues.',
                  accent: 'border-accent-harness/30',
                },
              ].map((t) => (
                <button
                  key={t.name}
                  onClick={() => applyTemplate(t)}
                  className={`text-left p-3 rounded-sm border ${t.accent} bg-[var(--gray-200)]/50 hover:bg-[var(--gray-200)] transition-colors`}
                >
                  <p className='text-sm font-medium text-primary'>{t.name}</p>
                  <p className='text-[11px] text-muted mt-1 leading-relaxed'>{t.desc}</p>
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Create form (collapsible) — v2.5.14 uses var(--gray-100) not hard-coded hex. */}
        {isAdmin && showCreate && (
          <div className='rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] p-5'>
            <h2 className='text-base font-semibold text-primary mb-4'>Create project</h2>
            <form onSubmit={handleCreate} className='space-y-3'>
              <input
                type='text'
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                placeholder='Project name'
                className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-sm text-primary placeholder-[var(--text-muted)] focus:border-accent-blue focus:outline-none'
              />
              <input
                type='text'
                value={desc}
                onChange={(e) => setDesc(e.target.value)}
                placeholder='Description (optional)'
                className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-sm text-primary placeholder-[var(--text-muted)] focus:border-accent-blue focus:outline-none'
              />
              <div>
                <label className='text-xs text-muted mb-1 block'>AI Provider</label>
                <select
                  value={provider}
                  onChange={(e) => setProvider(e.target.value)}
                  className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-sm text-primary focus:border-accent-blue focus:outline-none'
                >
                  <option value='ANTHROPIC'>Anthropic (Claude) — Recommended</option>
                  <option value='GEMINI'>Gemini</option>
                  <option value='OPENAI'>OpenAI (GPT)</option>
                </select>
              </div>
              {createError && <p className='text-sm text-accent-red'>{createError}</p>}
              <div className='flex gap-3'>
                <button
                  type='submit'
                  disabled={creating || !name.trim()}
                  className='px-4 py-2 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 disabled:opacity-50 transition-colors'
                >
                  {creating ? 'Creating…' : 'Create'}
                </button>
                <button
                  type='button'
                  onClick={() => setShowCreate(false)}
                  className='px-4 py-2 rounded-sm text-secondary hover:text-primary text-sm transition-colors'
                >
                  Cancel
                </button>
              </div>
            </form>
          </div>
        )}

        {/* Loading */}
        {loading && <div className='text-muted text-sm'>Loading projects…</div>}

        {/* Active projects */}
        {!loading && (
          <>
            {active.length === 0 && archived.length === 0 && (
              <div className='text-center py-16'>
                {/* Phase 1.1 #12 — empty-state hero pattern (B2B sweep). */}
                <FolderOpen className='h-12 w-12 text-[var(--text-muted)] mx-auto mb-3' />
                <p className='text-base font-semibold text-primary'>
                  {isAdmin ? 'Ship your first project' : 'No projects yet'}
                </p>
                <p className='text-sm text-muted mt-1.5 max-w-md mx-auto'>
                  {isAdmin
                    ? 'A project bundles a spec, the repo it targets, and every run that ships against it. Pick a starter template above or create a blank one.'
                    : "Your org admin hasn't created any projects yet — ping them to set up the first one."}
                </p>
              </div>
            )}

            {active.length > 0 && (
              <div className='space-y-3'>
                {active.map((p) => (
                  <ProjectCard
                    key={p.id}
                    project={p}
                    orgMembers={orgMembers}
                    isAdmin={isAdmin}
                    onArchive={handleArchive}
                    onViewRuns={handleViewRuns}
                    onUpdateProject={(updated) =>
                      setProjects((prev) => prev.map((x) => (x.id === updated.id ? updated : x)))
                    }
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
    </div>
  );
}

// v2.5.14 — metric tile matching ProjectDashboardPage.MetricTile. Kept local
// to avoid coupling this page to the project-scoped one.
function OrgMetricTile({
  label,
  value,
  accent,
}: {
  label: string;
  value: string | number;
  accent?: string;
}) {
  const isEmpty = value === '—' || value === 0 || value === '0';
  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2.5 py-2'>
      <div className='text-[11px] font-medium text-secondary uppercase tracking-wide'>{label}</div>
      {isEmpty && value === '—' ? (
        <div className='text-[11px] text-muted italic mt-1.5'>No data yet</div>
      ) : (
        <div className={cn('text-lg font-semibold tabular-nums mt-0.5', accent ?? 'text-primary')}>
          {value}
        </div>
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
        className='flex items-center gap-2 text-sm text-muted hover:text-secondary transition-colors mb-3'
      >
        {open ? <ChevronUp className='h-3.5 w-3.5' /> : <ChevronDown className='h-3.5 w-3.5' />}
        Archived projects ({projects.length})
      </button>
      {open && (
        <div className='space-y-3 opacity-60'>
          {projects.map((p) => (
            <ProjectCard
              key={p.id}
              project={p}
              orgMembers={orgMembers}
              isAdmin={isAdmin}
              onArchive={() => {}}
              onViewRuns={onViewRuns}
              onUpdateProject={() => {}}
            />
          ))}
        </div>
      )}
    </div>
  );
}
