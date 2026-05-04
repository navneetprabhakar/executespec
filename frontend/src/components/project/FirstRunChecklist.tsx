/**
 * FirstRunChecklist — orientation widget for users in the first-run flow on
 * a Project. Closes UX audit P-02 ("first-run wall: 4 sequential gates with
 * no progress indicator").
 *
 * Shows a 4-step path from empty project to first successful run:
 *
 *   1. Link a repository
 *   2. Add your GitHub access token (auto-checked when present on the user)
 *   3. Generate architecture
 *   4. Start your first run
 *
 * Each row has one of three states:
 *
 *   • DONE   — green check, faded text, no action button (collapsed-ish)
 *   • ACTIVE — accent-blue ring on the number, full text, action button visible
 *   • PENDING — muted text, action button hidden, "locked until previous"
 *               note rendered below
 *
 * The "active" step is the lowest-index step that isn't done — so users always
 * know exactly which one button is theirs to click. When all four are done the
 * component renders nothing (caller decides what to show instead — typically
 * the PulseTab full hero).
 *
 * Used by:
 *   • EmptyProjectLanding (replaces the legacy two-CTA hero on `noRuns && repoCount === 0`)
 *   • PulseTab (compact variant) when the project is in first-run state but
 *     past step 1 — `noRuns && (repoCount === 0 || archStatus === 'NONE')`
 *
 * Variant: caller passes `compact` to render a slim two-line version suitable
 * for a banner above PulseTab's existing hero. Default is the hero version.
 */
import {useNavigate} from 'react-router-dom';
import {Check, ArrowRight, Lock, Sparkles} from 'lucide-react';
import {useAuthStore} from '@/stores/useAuthStore';
import {cn} from '@/lib/utils';

export interface FirstRunChecklistProps {
  projectId: string;
  /** Number of repos linked to the project. Step 1 is done iff > 0. */
  repoCount: number;
  /** From dashboard.architectureDocStatus — step 3 done iff GENERATED or STALE. */
  archStatus: 'NONE' | 'GENERATED' | 'STALE' | null;
  /** Number of runs in the project (completed + active + failed). Step 4 done iff > 0. */
  runCount: number;
  /** Compact = 1 row, hero = 4 rows. Defaults to hero. */
  compact?: boolean;
  /** Click handler for the "Link a repository" step's action. */
  onLinkRepo?: () => void;
  /** Click handler for the "Generate architecture" step's action. */
  onGenerateArch?: () => void;
}

type StepKind = 'repo' | 'pat' | 'arch' | 'launch';
interface Step {
  kind: StepKind;
  title: string;
  done: boolean;
  /** Action label + handler, or null if the step has no inline action. */
  action: {label: string; onClick: () => void} | null;
  /** Optional sub-text rendered below the title. */
  subText?: string;
}

export default function FirstRunChecklist({
  projectId,
  repoCount,
  archStatus,
  runCount,
  compact = false,
  onLinkRepo,
  onGenerateArch,
}: FirstRunChecklistProps) {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const hasPat = Boolean(user?.hasGithubPat);
  const archDone = archStatus === 'GENERATED' || archStatus === 'STALE';

  const steps: Step[] = [
    {
      kind: 'repo',
      title: 'Link a repository',
      done: repoCount > 0,
      action: onLinkRepo ? {label: 'Link repo', onClick: onLinkRepo} : null,
      subText: 'GitHub repos get cloned into the workspace per run.',
    },
    {
      kind: 'pat',
      title: 'Add your GitHub access token',
      done: hasPat,
      // PAT lives in user settings — no inline mutation here, just a deep link.
      action: {
        label: 'Open Settings',
        onClick: () => navigate('/settings?focus=github'),
      },
      subText: 'Lets us read your repos + open pull requests from runs.',
    },
    {
      kind: 'arch',
      title: 'Generate architecture',
      done: archDone,
      action: onGenerateArch ? {label: 'Generate', onClick: onGenerateArch} : null,
      subText: 'A one-time scan so the planner knows what your code looks like.',
    },
    {
      kind: 'launch',
      title: 'Start your first run',
      done: runCount > 0,
      action: {
        label: 'Launch',
        onClick: () => navigate(`/projects/${projectId}/executions/new`),
      },
      subText: 'Paste a spec or pick a starter — your first run takes ~5 min.',
    },
  ];

  const completedCount = steps.filter((s) => s.done).length;
  const activeIndex = steps.findIndex((s) => !s.done); // -1 when all done

  // Render nothing when every step is done — caller decides what to show.
  if (activeIndex < 0) return null;

  if (compact) {
    return <CompactChecklist projectId={projectId} steps={steps} activeIndex={activeIndex} />;
  }

  return (
    <div className='rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] overflow-hidden'>
      <div className='px-4 py-3 border-b border-[var(--border-default)] flex items-center justify-between'>
        <div>
          <h3 className='text-sm font-medium text-primary tracking-tight'>Get started</h3>
          <p className='text-[11px] text-muted mt-0.5'>
            {completedCount} of {steps.length} done — {steps.length - completedCount} step
            {steps.length - completedCount !== 1 ? 's' : ''} to your first run
          </p>
        </div>
        <Sparkles className='h-4 w-4 text-accent-blue' />
      </div>
      <ol className='divide-y divide-[var(--border-default)]'>
        {steps.map((s, i) => (
          <Row key={s.kind} step={s} index={i} isActive={i === activeIndex} />
        ))}
      </ol>
    </div>
  );
}

function Row({step, index, isActive}: {step: Step; index: number; isActive: boolean}) {
  const num = index + 1;
  const locked = !step.done && !isActive;
  return (
    <li
      className={cn(
        'flex items-start gap-3 px-4 py-3 transition-colors',
        isActive && 'bg-accent-blue/5',
      )}
    >
      {/* Step indicator: green check (done) / accent-blue ring (active) /
          muted ring (pending). */}
      <div
        className={cn(
          'flex-shrink-0 h-6 w-6 rounded-full border flex items-center justify-center text-[11px] font-mono',
          step.done
            ? 'bg-status-success/15 border-status-success/40 text-status-success'
            : isActive
              ? 'bg-accent-blue/10 border-accent-blue/50 text-accent-blue'
              : 'bg-[var(--gray-200)] border-[var(--border-default)] text-muted',
        )}
        aria-label={step.done ? 'Step complete' : isActive ? 'Current step' : 'Locked step'}
      >
        {step.done ? <Check className='h-3 w-3' /> : num}
      </div>
      <div className='flex-1 min-w-0'>
        <div className='flex items-center gap-2'>
          <span
            className={cn(
              'text-sm',
              step.done
                ? 'text-muted line-through decoration-muted/50'
                : isActive
                  ? 'text-primary font-medium'
                  : 'text-secondary',
            )}
          >
            {step.title}
          </span>
          {locked && (
            <span title='Complete the previous step first' aria-label='Locked'>
              <Lock className='h-3 w-3 text-muted/60' />
            </span>
          )}
        </div>
        {step.subText && !step.done && (
          <p className='text-[11px] text-muted mt-0.5 leading-snug'>{step.subText}</p>
        )}
      </div>
      {isActive && step.action && (
        <button
          onClick={step.action.onClick}
          className='flex-shrink-0 inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 transition-colors'
        >
          {step.action.label}
          <ArrowRight className='h-3 w-3' />
        </button>
      )}
    </li>
  );
}

/**
 * Compact one-line version for embedding above PulseTab when the project has
 * progressed past step 1 but isn't fully set up. Renders the active step's
 * title + action button only — relies on the user having already seen the
 * full hero on the empty-project page to know there are 4 steps total.
 */
function CompactChecklist({
  steps,
  activeIndex,
}: {
  projectId: string;
  steps: Step[];
  activeIndex: number;
}) {
  const active = steps[activeIndex];
  const remaining = steps.length - steps.filter((s) => s.done).length;
  return (
    <div className='rounded-md border border-accent-blue/30 bg-accent-blue/5 px-3 py-2 flex items-center gap-3'>
      <Sparkles className='h-4 w-4 text-accent-blue flex-shrink-0' />
      <div className='flex-1 min-w-0'>
        <div className='text-xs font-medium text-primary'>Next: {active.title}</div>
        <div className='text-[10.5px] text-muted'>
          Step {activeIndex + 1} of {steps.length} · {remaining} more to your first run
        </div>
      </div>
      {active.action && (
        <button
          onClick={active.action.onClick}
          className='flex-shrink-0 inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-sm border border-accent-blue/40 bg-accent-blue/15 text-accent-blue hover:bg-accent-blue/25 transition-colors'
        >
          {active.action.label}
          <ArrowRight className='h-3 w-3' />
        </button>
      )}
    </div>
  );
}
