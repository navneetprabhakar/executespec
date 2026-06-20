import {ReactNode} from 'react';
import {Link} from 'react-router-dom';

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
