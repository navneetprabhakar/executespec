import {useEffect, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import {
  getLibraryDetail,
  instantiateLibrarySolution,
  requiresCostCap,
  resolveLibraryVariant,
  type ReadymadeDetail,
  type ReadymadeSlot,
  type VariantResolutionResult,
} from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import ProjectRepoPicker from '../components/ProjectRepoPicker';
import StackPicker from '../components/StackPicker';

/**
 * R-330 / R-335 — Readymade Solutions detail page.
 *
 * <p>R-335 (UX rev): replaced the free-form Project ID + Repo IDs
 * text inputs with {@link ProjectRepoPicker} (real dropdowns +
 * inline new-project / new-repo flows), and the "Pick variant /
 * resolve from your stack" combo with {@link StackPicker} (clear
 * language → framework chip rows + silent auto-resolution from
 * the first picked repo).
 *
 * <p>Flow:
 * <ol>
 *   <li>{@code 1.} ProjectRepoPicker — pick (or create) project,
 *       then pick (or add) one or more repos.</li>
 *   <li>{@code 2.} StackPicker — pick language + framework; the
 *       chosen variant pre-fills via repo-stack auto-resolve.</li>
 *   <li>{@code 3.} Slot inputs — rendered by type (TEXT / SECRET /
 *       SELECT / NUMBER / BOOLEAN).</li>
 *   <li>{@code 4.} Use → POST /instantiate → navigate to the run
 *       detail page.</li>
 * </ol>
 *
 * <p>Locked solutions (caller below solution.tier) render the
 * upgrade CTA instead of the slot form.
 */
export default function LibraryDetailPage() {
  const {slug} = useParams<{slug: string}>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const showCostCap = requiresCostCap(user);

  const [detail, setDetail] = useState<ReadymadeDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [projectId, setProjectId] = useState('');
  const [repoIds, setRepoIds] = useState<string[]>([]);
  const [branch, setBranch] = useState('');
  const [slotValues, setSlotValues] = useState<Record<string, string>>({});
  const [selectedVariantId, setSelectedVariantId] = useState<string>('');

  // Auto-resolved variant hint — silent in the background, surfaced
  // by StackPicker as a small detection chip.  No manual button.
  const [resolution, setResolution] = useState<VariantResolutionResult | null>(null);

  // Apply state
  const [applyBusy, setApplyBusy] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);

  useEffect(() => {
    if (!slug) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    getLibraryDetail(slug)
      .then((data) => {
        if (cancelled) return;
        setDetail(data);
        const seed: Record<string, string> = {};
        for (const s of data.slots) {
          if (s.defaultValue) seed[s.name] = s.defaultValue;
        }
        setSlotValues(seed);
        // Default to first variant if there's only one — saves the
        // user a click on single-variant solutions.  Multi-variant
        // entries default to the first language's first framework so
        // the form is always submittable; the auto-resolve effect
        // below will refine the pick once a project + repo land.
        if (data.variants.length >= 1) {
          setSelectedVariantId(data.variants[0].id);
        }
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setError(e?.message ?? 'Failed to load solution');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [slug]);

  // R-335 — silent auto-resolve.  Fires whenever the user has a
  // project + at least one repo selected.  Updates the StackPicker's
  // detected-stack hint chip and pre-selects the matching variant
  // when the resolver returns OK.  No spinner, no errors surfaced —
  // a failed resolution just means the manual chip-picker is the
  // source of truth (the user-facing form still works).
  useEffect(() => {
    if (!slug || !projectId || repoIds.length === 0) {
      setResolution(null);
      return;
    }
    let cancelled = false;
    resolveLibraryVariant(slug, projectId, repoIds[0])
      .then((r) => {
        if (cancelled) return;
        setResolution(r);
        if (r.bestMatch) setSelectedVariantId(r.bestMatch.id);
      })
      .catch(() => {
        if (cancelled) return;
        setResolution(null);
      });
    return () => {
      cancelled = true;
    };
  }, [slug, projectId, repoIds]);

  async function onInstantiate() {
    if (!projectId || repoIds.length === 0) {
      setApplyError('Pick a project and at least one repo first.');
      return;
    }
    if (!slug) return;
    setApplyBusy(true);
    setApplyError(null);
    try {
      const r = await instantiateLibrarySolution(slug, {
        projectId,
        repoIds,
        slotValues,
        variantId: selectedVariantId || undefined,
        branch: branch.trim() || undefined,
      });
      navigate(`/projects/${projectId}/runs/${r.executionRunId}`);
    } catch (e) {
      setApplyError((e as Error)?.message ?? 'Instantiate failed');
    } finally {
      setApplyBusy(false);
    }
  }

  if (loading) {
    return (
      <div style={{padding: 32, textAlign: 'center', color: 'var(--v3-text-subtle)'}}>
        Loading solution…
      </div>
    );
  }
  if (error || !detail) {
    return (
      <div style={{padding: 16}}>
        <div style={errorBoxStyle}>
          {error ?? 'Solution not found.'}
          <div style={{marginTop: 8}}>
            <Link to='/library' style={{color: 'var(--v3-brand)', fontSize: 12}}>
              ← back to library
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const s = detail.solution;

  return (
    <div
      style={{padding: 16, paddingBottom: 48, display: 'flex', flexDirection: 'column', gap: 20}}
    >
      {/* Breadcrumb */}
      <div style={{fontSize: 12, color: 'var(--v3-text-subtle)'}}>
        <Link to='/library' style={{color: 'var(--v3-text-subtle)'}}>
          library
        </Link>
        {' / '}
        <span style={{color: 'var(--v3-text)'}}>{s.slug}</span>
      </div>

      {/* Hero */}
      <div>
        <div style={{display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6}}>
          <h1 style={{fontSize: 22, fontWeight: 700, color: 'var(--v3-text)', margin: 0}}>
            {s.name}
          </h1>
          {detail.locked && (
            <span style={lockChipStyle}>🔒 Upgrade to {detail.unlockTier} to use</span>
          )}
        </div>
        {s.tagline && (
          <p style={{fontSize: 13, color: 'var(--v3-text-subtle)', margin: 0}}>{s.tagline}</p>
        )}
      </div>

      {/* Description */}
      {s.description && (
        <div
          style={{
            fontSize: 13,
            color: 'var(--v3-text)',
            lineHeight: 1.6,
            whiteSpace: 'pre-wrap',
            padding: 12,
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
          }}
        >
          {s.description}
        </div>
      )}

      {/* Dependencies hint */}
      {detail.dependencies.length > 0 && (
        <div
          style={{
            padding: 10,
            background: 'rgba(91,141,239,0.08)',
            border: '1px solid rgba(91,141,239,0.30)',
            borderRadius: 6,
            fontSize: 12,
            color: 'var(--v3-text)',
          }}
        >
          ↪ This solution composes with{' '}
          {detail.dependencies.map((d, i) => (
            <span key={d.dependsOnId}>
              {i > 0 && ', '}
              <strong>{d.required ? 'requires' : 'recommends'}</strong>{' '}
              <code style={{fontSize: 11}}>{d.dependsOnId.slice(0, 8)}</code>
            </span>
          ))}
          .
        </div>
      )}

      {/* Locked path */}
      {detail.locked && (
        <div
          style={{
            padding: 16,
            background: 'rgba(245,158,11,0.12)',
            border: '1px solid rgba(245,158,11,0.4)',
            borderRadius: 6,
          }}
        >
          <div style={{fontSize: 14, fontWeight: 600, color: '#FBBF24', marginBottom: 6}}>
            This solution requires the {detail.unlockTier} tier.
          </div>
          <Link
            to='/pricing'
            style={{
              display: 'inline-block',
              marginTop: 6,
              padding: '6px 12px',
              background: 'var(--v3-brand)',
              color: 'var(--v3-text-on-brand)',
              borderRadius: 4,
              textDecoration: 'none',
              fontSize: 12,
              fontWeight: 600,
            }}
          >
            See pricing →
          </Link>
        </div>
      )}

      {/* Use form (gated on lock state) */}
      {!detail.locked && (
        <>
          <FormSection title='1. Where will this run?'>
            <ProjectRepoPicker
              projectId={projectId}
              repoIds={repoIds}
              onProjectChange={setProjectId}
              onRepoIdsChange={setRepoIds}
            />
            <FormField label='Branch (optional)' hint="Defaults to the project's target branch.">
              <input
                value={branch}
                onChange={(e) => setBranch(e.target.value)}
                placeholder='feature/add-stripe-checkout'
                style={inputStyle}
              />
            </FormField>
          </FormSection>

          {/* Stack — language + framework chips, with silent auto-detection */}
          <FormSection title='2. Stack'>
            <StackPicker
              variants={detail.variants}
              selectedVariantId={selectedVariantId}
              onChange={setSelectedVariantId}
              resolution={resolution}
            />
          </FormSection>

          {/* Slots */}
          {detail.slots.length > 0 && (
            <FormSection title='3. Solution parameters'>
              {detail.slots.map((slot) => (
                <SlotInput
                  key={slot.name}
                  slot={slot}
                  value={slotValues[slot.name] ?? ''}
                  onChange={(v) => setSlotValues({...slotValues, [slot.name]: v})}
                />
              ))}
            </FormSection>
          )}

          {/* Tier-stratified cost framing */}
          <div
            style={{
              padding: '6px 10px',
              background: showCostCap ? 'rgba(91,141,239,0.10)' : 'rgba(122,209,200,0.10)',
              border: `1px solid ${showCostCap ? 'rgba(91,141,239,0.35)' : 'rgba(122,209,200,0.35)'}`,
              borderRadius: 4,
              fontSize: 11.5,
              color: 'var(--v3-text)',
              lineHeight: 1.5,
            }}
          >
            {showCostCap
              ? "💳 Will use platform credits — capped at your tier's per-run budget."
              : '🔑 Will use your own LLM provider keys (BYOK) — billed direct by the provider.'}
          </div>

          <FormSection title='4. Use this solution'>
            <p style={{margin: 0, fontSize: 12, color: 'var(--v3-text-subtle)'}}>
              Submits this solution as an ExecutionRun. We'll pre-fill the spec from the variant's
              template + your slot values, run it through the existing pipeline, and open a PR.
            </p>
            <div style={{display: 'flex', gap: 8, marginTop: 8}}>
              <button
                type='button'
                onClick={onInstantiate}
                disabled={applyBusy}
                style={primaryButton(applyBusy)}
              >
                {applyBusy ? 'Submitting…' : 'Use this solution'}
              </button>
            </div>
            {applyError && <div style={errorBoxStyle}>{applyError}</div>}
          </FormSection>
        </>
      )}
    </div>
  );
}

// R-335: ResolutionBanner + variantLabel removed.  Their roles moved
// into the StackPicker component (DetectionHint + variantFrameworkLabel).

function FormSection({title, children}: {title: string; children: React.ReactNode}) {
  return (
    <div
      style={{
        padding: 14,
        border: '1px solid var(--v3-border)',
        borderRadius: 6,
        background: 'var(--v3-surface)',
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
      }}
    >
      <h3 style={{fontSize: 13, fontWeight: 600, color: 'var(--v3-text)', margin: 0}}>{title}</h3>
      {children}
    </div>
  );
}

function FormField({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div style={{display: 'flex', flexDirection: 'column', gap: 4}}>
      <label
        style={{
          fontSize: 11,
          fontWeight: 600,
          color: 'var(--v3-text-subtle)',
          textTransform: 'uppercase',
          letterSpacing: 0.4,
        }}
      >
        {label}
      </label>
      {children}
      {hint && (
        <span style={{fontSize: 10.5, color: 'var(--v3-text-subtle)', lineHeight: 1.4}}>
          {hint}
        </span>
      )}
    </div>
  );
}

function SlotInput({
  slot,
  value,
  onChange,
}: {
  slot: ReadymadeSlot;
  value: string;
  onChange: (v: string) => void;
}) {
  const label = slot.displayName + (slot.required ? ' *' : '');
  if (slot.type === 'SELECT' && slot.options) {
    let options: string[] = [];
    try {
      const parsed = JSON.parse(slot.options);
      if (Array.isArray(parsed)) options = parsed;
    } catch {
      /* fallthrough */
    }
    return (
      <FormField label={label} hint={slot.description ?? undefined}>
        <select value={value} onChange={(e) => onChange(e.target.value)} style={inputStyle}>
          {!slot.required && <option value=''>(none)</option>}
          {options.map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </select>
      </FormField>
    );
  }
  if (slot.type === 'BOOLEAN') {
    return (
      <FormField label={label} hint={slot.description ?? undefined}>
        <select value={value} onChange={(e) => onChange(e.target.value)} style={inputStyle}>
          <option value=''>(unset)</option>
          <option value='true'>true</option>
          <option value='false'>false</option>
        </select>
      </FormField>
    );
  }
  const isPassword = slot.type === 'SECRET';
  return (
    <FormField label={label} hint={slot.description ?? undefined}>
      <input
        type={isPassword ? 'password' : slot.type === 'NUMBER' ? 'number' : 'text'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={slot.defaultValue ?? ''}
        style={inputStyle}
      />
    </FormField>
  );
}

const inputStyle: React.CSSProperties = {
  padding: '7px 10px',
  background: 'var(--v3-bg)',
  color: 'var(--v3-text)',
  border: '1px solid var(--v3-border)',
  borderRadius: 4,
  fontSize: 12.5,
  outline: 'none',
  fontFamily: 'inherit',
  width: '100%',
};

function primaryButton(disabled: boolean): React.CSSProperties {
  return {
    padding: '7px 14px',
    background: disabled ? 'transparent' : 'var(--v3-brand)',
    color: disabled ? 'var(--v3-text-subtle)' : 'var(--v3-text-on-brand)',
    border: disabled ? '1px solid var(--v3-border)' : 'none',
    borderRadius: 4,
    fontSize: 12,
    fontWeight: 600,
    cursor: disabled ? 'not-allowed' : 'pointer',
    fontFamily: 'inherit',
  };
}

const lockChipStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  background: 'rgba(245,158,11,0.18)',
  color: '#FBBF24',
  padding: '3px 8px',
  borderRadius: 4,
  border: '1px solid rgba(245,158,11,0.4)',
};

const errorBoxStyle: React.CSSProperties = {
  marginTop: 8,
  padding: 10,
  background: 'rgba(239,68,68,0.12)',
  border: '1px solid rgba(239,68,68,0.36)',
  color: '#FCA5A5',
  borderRadius: 4,
  fontSize: 12,
};
