import {useEffect, useState} from 'react';
import {listProjectAdrs, getProjectAdr, type AdrSummary, type AdrDetail} from '@/lib/api';
import {MarkdownContent} from '@/components/shared/MarkdownContent';
import {cn, errMsg} from '@/lib/utils';

/**
 * M-014 (Wave 2) — Project → Code → ADRs panel.
 * Customer git is source-of-truth for the ADR markdown; the V137
 * `adr` table caches the parsed frontmatter so the dashboard can
 * query by status without round-tripping to GitHub. Only ACCEPTED
 * ADRs flow into planner context — but every status renders here so
 * users can see PROPOSED / DEPRECATED / SUPERSEDED history too.
 */
export default function AdrPanel({projectId}: {projectId: string}) {
  const [acceptedOnly, setAcceptedOnly] = useState(false);
  const [list, setList] = useState<AdrSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<AdrDetail | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    let alive = true;
    setError(null);
    listProjectAdrs(projectId, acceptedOnly)
      .then((rows) => {
        if (alive) setList(rows);
      })
      .catch((e) => {
        if (alive) setError(errMsg(e) ?? 'Failed to load ADRs');
      });
    return () => {
      alive = false;
    };
  }, [projectId, acceptedOnly]);

  useEffect(() => {
    if (!selectedId) {
      setSelected(null);
      return;
    }
    setDetailLoading(true);
    getProjectAdr(projectId, selectedId)
      .then(setSelected)
      .catch((e) => setError(errMsg(e) ?? 'Failed to load ADR'))
      .finally(() => setDetailLoading(false));
  }, [projectId, selectedId]);

  return (
    <div className='space-y-3'>
      <div className='flex items-baseline justify-between gap-3 flex-wrap'>
        <div>
          <h3 className='text-sm font-medium text-primary'>Architecture Decision Records</h3>
          <p className='text-xs text-muted mt-0.5'>
            Cached from <code>.executespec/arch/adrs/</code> in customer git. Only ACCEPTED ADRs
            flow into planner context.
          </p>
        </div>
        <label className='flex items-center gap-2 text-xs text-secondary cursor-pointer'>
          <input
            type='checkbox'
            checked={acceptedOnly}
            onChange={(e) => {
              setAcceptedOnly(e.target.checked);
              setSelectedId(null);
            }}
            className='accent-accent-blue'
          />
          Accepted only
        </label>
      </div>

      {error && (
        <div className='rounded-sm bg-status-error/10 border border-status-error/30 px-3 py-2 text-xs text-status-error'>
          {error}
        </div>
      )}

      <div className='grid grid-cols-1 lg:grid-cols-3 gap-3'>
        {/* List column */}
        <div className='lg:col-span-1 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] divide-y divide-[var(--border-default)] max-h-[480px] overflow-y-auto'>
          {!list && !error && <p className='text-xs text-muted p-4'>Loading…</p>}
          {list && list.length === 0 && (
            <p className='text-xs text-muted p-4'>
              No ADRs found{acceptedOnly ? ' (with status ACCEPTED)' : ''}. Add one to{' '}
              <code>.executespec/arch/adrs/NNNN-slug.md</code> in your repo.
            </p>
          )}
          {list?.map((adr) => (
            <button
              key={adr.id}
              onClick={() => setSelectedId(adr.id)}
              className={cn(
                'w-full text-left px-3 py-2.5 hover:bg-[var(--gray-200)] transition-colors',
                selectedId === adr.id && 'bg-accent-blue/10',
              )}
            >
              <div className='flex items-baseline gap-2'>
                <span className='text-[11px] font-mono text-muted'>
                  #{String(adr.adrIdInRepo).padStart(4, '0')}
                </span>
                <StatusChip status={adr.status} />
              </div>
              <p className='text-xs text-primary mt-0.5 truncate'>{adr.title}</p>
              {adr.decisionDate && (
                <p className='text-[10px] text-muted mt-0.5'>{adr.decisionDate}</p>
              )}
            </button>
          ))}
        </div>

        {/* Detail column */}
        <div className='lg:col-span-2 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-4 min-h-[200px]'>
          {!selectedId && <p className='text-xs text-muted'>Select an ADR to view its body.</p>}
          {selectedId && detailLoading && <p className='text-xs text-muted'>Loading…</p>}
          {selected && (
            <article className='space-y-3'>
              <header>
                <div className='flex items-baseline gap-2'>
                  <span className='text-xs font-mono text-muted'>
                    #{String(selected.adrIdInRepo).padStart(4, '0')}
                  </span>
                  <StatusChip status={selected.status} />
                </div>
                <h4 className='text-base font-medium text-primary mt-1'>{selected.title}</h4>
                <p className='text-xs text-muted mt-1'>
                  Decided <span className='text-secondary'>{selected.decisionDate ?? '—'}</span>
                  {selected.deciders && <> · {selected.deciders}</>}
                </p>
                {(selected.supersedes || selected.supersededBy || selected.relatedAdrs) && (
                  <p className='text-xs text-muted mt-1'>
                    {selected.supersedes && <>Supersedes: {selected.supersedes} · </>}
                    {selected.supersededBy && <>Superseded by: {selected.supersededBy} · </>}
                    {selected.relatedAdrs && <>Related: {selected.relatedAdrs}</>}
                  </p>
                )}
                {selected.filePath && (
                  <p className='text-[10px] text-muted mt-1 font-mono'>{selected.filePath}</p>
                )}
              </header>
              <div className='text-sm text-secondary border-t border-[var(--border-default)] pt-3'>
                {selected.body ? (
                  <MarkdownContent content={selected.body} />
                ) : (
                  <p className='text-muted text-xs'>No body — frontmatter only.</p>
                )}
              </div>
            </article>
          )}
        </div>
      </div>
    </div>
  );
}

function StatusChip({status}: {status: string}) {
  const tone =
    status === 'ACCEPTED'
      ? 'bg-status-success/15 text-status-success border-status-success/30'
      : status === 'PROPOSED'
        ? 'bg-accent-blue/15 text-accent-blue border-accent-blue/30'
        : status === 'DEPRECATED'
          ? 'bg-accent-amber/15 text-accent-amber border-accent-amber/30'
          : status === 'SUPERSEDED'
            ? 'bg-[var(--gray-300)] text-muted border-[var(--border-default)]'
            : 'bg-[var(--gray-200)] text-muted border-[var(--border-default)]';
  return (
    <span
      className={cn(
        'inline-block px-1.5 py-0.5 rounded-sm text-[10px] uppercase tracking-wider border',
        tone,
      )}
    >
      {status}
    </span>
  );
}
