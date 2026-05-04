/**
 * TemplateGrid — card-grid spec template picker.
 *
 * Phase 1.3 #34: replaces the "click dropdown → scroll list → click row"
 * pattern in spec-input UIs with a 2-3 column card grid where users can
 * scan templates at a glance and click to apply.
 *
 * Use in surfaces that have horizontal room (NewRunModal, EmptyProjectLanding,
 * the project Launch flow). The compact dropdown {@link TemplatePicker}
 * remains for tight surfaces (ExecutionSetup inline header).
 */
import {useEffect, useState} from 'react';
import {errMsg} from '@/lib/utils';
import {FileText, Plus} from 'lucide-react';
import {listSpecTemplates, type SpecTemplate} from '@/lib/api';

interface Props {
  /** Required — templates are scoped per project (the picker also returns org-shared ones via the same call). */
  projectId: string;
  /** Called with the chosen template's requirement text. */
  onPick: (text: string) => void;
  /** Optional — when present, shown alongside cards as a "save current" affordance. */
  currentRequirement?: string;
  /** Called when the user wants to open the save-template dialog. Optional. */
  onSaveCurrent?: () => void;
  /** Optional class merged onto the wrapper. */
  className?: string;
  /** Visible header copy. Default "Start from a template". */
  heading?: string;
}

export default function TemplateGrid({
  projectId,
  onPick,
  currentRequirement,
  onSaveCurrent,
  className,
  heading,
}: Props) {
  const [items, setItems] = useState<SpecTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    listSpecTemplates(projectId)
      .then(setItems)
      .catch((e) => setError(e instanceof Error ? errMsg(e) : String(e)))
      .finally(() => setLoading(false));
  }, [projectId]);

  if (loading) {
    return <div className={`text-[11px] text-muted ${className ?? ''}`}>Loading templates…</div>;
  }
  if (error) {
    return <div className={`text-[11px] text-status-error ${className ?? ''}`}>{error}</div>;
  }
  if (items.length === 0 && !onSaveCurrent) return null;

  return (
    <div className={className ?? ''}>
      <div className='flex items-center justify-between mb-2'>
        <h3 className='text-[10px] font-mono font-medium uppercase tracking-wider text-muted'>
          {heading ?? 'Start from a template'}
        </h3>
        {items.length > 0 && (
          <span className='text-[10px] text-muted'>{items.length} available</span>
        )}
      </div>
      <div className='grid grid-cols-2 sm:grid-cols-3 gap-2'>
        {items.map((t) => (
          <button
            key={t.id}
            type='button'
            onClick={() => onPick(t.requirementTemplate)}
            className='group flex flex-col items-start gap-1.5 text-left p-3 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] hover:border-accent-blue/40 hover:bg-accent-blue/5 transition-colors'
            title={t.description ?? t.requirementTemplate.slice(0, 200)}
          >
            <div className='flex items-center gap-1.5 w-full'>
              <FileText className='h-3.5 w-3.5 text-accent-blue flex-shrink-0' />
              <span className='text-xs font-medium text-primary truncate flex-1'>{t.name}</span>
              <span className='text-[9px] uppercase tracking-wide text-muted'>
                {t.ownerType === 'ORG' ? 'org' : 'project'}
              </span>
            </div>
            <p className='text-[11px] text-muted leading-snug line-clamp-2'>
              {t.description ?? truncate(t.requirementTemplate, 90)}
            </p>
          </button>
        ))}
        {onSaveCurrent && currentRequirement && currentRequirement.trim().length > 20 && (
          <button
            type='button'
            onClick={onSaveCurrent}
            className='flex flex-col items-start gap-1.5 text-left p-3 rounded-sm border border-dashed border-[var(--border-default)] bg-[var(--gray-100)] hover:border-accent-blue/40 hover:bg-accent-blue/5 transition-colors'
            title='Save the current requirement text as a new template'
          >
            <div className='flex items-center gap-1.5'>
              <Plus className='h-3.5 w-3.5 text-accent-blue' />
              <span className='text-xs font-medium text-primary'>Save current</span>
            </div>
            <p className='text-[11px] text-muted leading-snug'>
              Turn what you've typed into a reusable template.
            </p>
          </button>
        )}
      </div>
    </div>
  );
}

function truncate(s: string, n: number): string {
  return s.length <= n ? s : s.slice(0, n) + '…';
}
