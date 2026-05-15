import {ReactNode} from 'react';
import {Link} from 'react-router-dom';
import {ArrowRight} from '@/signal/icons/icons';

// ─────────── LiveHero ───────────

export function LiveHero({
  runId,
  title,
  tasks,
  elapsed,
  spent,
  actions,
}: {
  runId: string;
  title: ReactNode;
  tasks: string;
  elapsed: string;
  spent: string;
  actions?: ReactNode;
}) {
  return (
    <div className='signal-live-hero'>
      <div className='row1'>
        <span className='blink' />
        Running now · {runId}
      </div>
      <h2>{title}</h2>
      <div className='progress'>
        <div className='fill' />
      </div>
      <div className='stats'>
        <div className='stat'>
          <div className='v signal'>{tasks}</div>tasks
        </div>
        <div className='stat'>
          <div className='v'>{elapsed}</div>elapsed
        </div>
        <div className='stat'>
          <div className='v mint'>{spent}</div>spent
        </div>
      </div>
      {actions && <div className='actions'>{actions}</div>}
    </div>
  );
}

export function LiveHeroAction({
  children,
  to,
  primary,
}: {
  children: ReactNode;
  to: string;
  primary?: boolean;
}) {
  return (
    <Link to={to} className={`btn-hero ${primary ? 'primary' : ''}`}>
      {children}
    </Link>
  );
}

// ─────────── ScoreCard ───────────

export function ScoreCard({
  verdict,
  score,
  label,
  cells,
}: {
  verdict: string;
  score: number;
  label: string;
  cells: Array<{v: string; l: string}>;
}) {
  return (
    <div className='signal-score-card'>
      <div className='verdict'>{verdict}</div>
      <h2>
        {score}
        <span className='pct'>%</span>
      </h2>
      <div className='label'>{label}</div>
      <div className='grid'>
        {cells.map((c, i) => (
          <div key={i} className='cell'>
            <div className='v'>{c.v}</div>
            <div className='l'>{c.l}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─────────── StreamCard ───────────

type StreamKind = 'tool' | 'llm' | 'warn' | 'ok' | 'default';

export function StreamCard({
  kind = 'default',
  topLeft,
  ts,
  children,
}: {
  kind?: StreamKind;
  topLeft: string;
  ts: string;
  children: ReactNode;
}) {
  return (
    <div className={`signal-stream-card ${kind !== 'default' ? kind : ''}`}>
      <div className='top'>
        <span>{topLeft}</span>
        <span className='ts'>{ts}</span>
      </div>
      <div className='body'>{children}</div>
    </div>
  );
}

// ─────────── PrCard ───────────

export function PrCard({
  label,
  title,
  repo,
  ciStatus,
  add,
  rem,
  actions,
  to,
}: {
  label?: string;
  title: ReactNode;
  repo: string;
  ciStatus?: ReactNode;
  add?: string;
  rem?: string;
  actions?: ReactNode;
  to?: string;
}) {
  const inner = (
    <>
      {label && <div className='label-row'>{label}</div>}
      <div className='pr-title'>{title}</div>
      <div className='pr-meta'>
        <span>{repo}</span>
        {ciStatus}
        {add && (
          <>
            <span>·</span>
            <span className='add'>{add}</span>
          </>
        )}
        {rem && <span className='rem'>{rem}</span>}
      </div>
      {actions && <div className='pr-actions'>{actions}</div>}
    </>
  );
  if (to) {
    return (
      <Link to={to} className='signal-pr-card' style={{display: 'block'}}>
        {inner}
      </Link>
    );
  }
  return <div className='signal-pr-card'>{inner}</div>;
}

// ─────────── ComposerCard ───────────

export function ComposerCard({
  title = 'What should we build?',
  est,
  value,
  onChange,
  chips,
  submitTo,
  onSubmit,
  submitLabel = 'Submit run',
  submitBusy = false,
  submitDisabled = false,
}: {
  title?: string;
  est?: string;
  value?: string;
  onChange?: (v: string) => void;
  chips?: ReactNode;
  /** Static destination after submit. Used when no onSubmit callback is supplied. */
  submitTo?: string;
  /** Async submit handler. When provided, the button calls this on click instead of navigating. */
  onSubmit?: () => void | Promise<void>;
  submitLabel?: ReactNode;
  submitBusy?: boolean;
  submitDisabled?: boolean;
}) {
  const disabled = submitDisabled || submitBusy;
  return (
    <div className='signal-composer-card'>
      <div className='composer-h'>
        <h3>{title}</h3>
        {est && <span className='est'>{est}</span>}
      </div>
      <textarea
        value={value}
        onChange={(e) => onChange?.(e.target.value)}
        placeholder='Describe what you want, in plain English. Reference repos or files if you want the agent to stay inside specific boundaries.'
      />
      {chips && <div className='composer-chips'>{chips}</div>}
      {onSubmit ? (
        <button
          type='button'
          className='composer-submit'
          onClick={() => {
            if (!disabled) void onSubmit();
          }}
          disabled={disabled}
          aria-disabled={disabled || undefined}
          style={disabled ? {opacity: 0.6, cursor: 'not-allowed'} : undefined}
        >
          {submitBusy ? 'Submitting…' : submitLabel}
          <ArrowRight size={16} />
        </button>
      ) : (
        <Link to={submitTo || '#'} className='composer-submit'>
          {submitLabel}
          <ArrowRight size={16} />
        </Link>
      )}
    </div>
  );
}

// ─────────── TaskLine ───────────

type TaskStatus = 'todo' | 'live' | 'done';

export function TaskLine({
  status = 'todo',
  title,
  sub,
  tag,
}: {
  status?: TaskStatus;
  title: ReactNode;
  sub?: ReactNode;
  tag?: ReactNode;
}) {
  return (
    <div className={`signal-task-line ${status !== 'todo' ? status : ''}`}>
      <div className='check' />
      <div className='title'>
        {title}
        {sub && <span className='sub'>{sub}</span>}
      </div>
      {tag && <span className='tag'>{tag}</span>}
    </div>
  );
}
