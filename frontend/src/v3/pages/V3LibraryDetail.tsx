import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  getLibraryDetail,
  instantiateLibrarySolution,
  requiresCostCap,
  resolveLibraryVariant,
  type ReadymadeDetail,
  type ReadymadeSlot,
  type ReadymadeVariant,
  type VariantResolutionResult,
} from '@/lib/api';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * R-330 — Readymade Solutions detail page.
 *
 * <p>Mirrors V3MigrationDetail but tuned to the Readymades shape:
 * <ol>
 *   <li>User picks projectId + repoIds.</li>
 *   <li>For the first repo, the page calls
 *       {@code POST /api/library/{slug}/resolve} to surface the
 *       best-match variant (and the full available list when no
 *       exact match exists).</li>
 *   <li>User fills slots (rendered by type).</li>
 *   <li>Cost framing chip (R-341 cross-product) sets expectations
 *       per tier.</li>
 *   <li>Click "Use this" → POST /instantiate → navigate to the run
 *       detail page where the existing pipeline lifecycle takes
 *       over.</li>
 * </ol>
 *
 * <p>Locked solutions (caller below solution.tier) render the
 * upgrade CTA instead of the slot form.
 */
export default function V3LibraryDetail() {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const showCostCap = requiresCostCap(user);

  const [detail, setDetail] = useState<ReadymadeDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [projectId, setProjectId] = useState('');
  const [repoIdsInput, setRepoIdsInput] = useState('');
  const [branch, setBranch] = useState('');
  const [slotValues, setSlotValues] = useState<Record<string, string>>({});
  const [selectedVariantId, setSelectedVariantId] = useState<string>('');

  // Variant resolution state
  const [resolution, setResolution] = useState<VariantResolutionResult | null>(null);
  const [resolveBusy, setResolveBusy] = useState(false);
  const [resolveError, setResolveError] = useState<string | null>(null);

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
        // user a click on single-variant solutions.
        if (data.variants.length === 1) {
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

  const repoIds = useMemo(
    () => repoIdsInput.split(',').map((s) => s.trim()).filter(Boolean),
    [repoIdsInput]
  );

  async function onResolveVariant() {
    if (!slug || !projectId.trim() || repoIds.length === 0) {
      setResolveError('Project ID and at least one repo ID are required to resolve.');
      return;
    }
    setResolveBusy(true);
    setResolveError(null);
    try {
      // Resolve against the first selected repo.  When the user has
      // multiple repos selected, they're expected to be a cohesive
      // set (same stack); if not, manual variant pick covers it.
      const r = await resolveLibraryVariant(slug, projectId.trim(), repoIds[0]);
      setResolution(r);
      if (r.bestMatch) setSelectedVariantId(r.bestMatch.id);
    } catch (e) {
      setResolveError((e as Error)?.message ?? 'Variant resolution failed');
    } finally {
      setResolveBusy(false);
    }
  }

  async function onInstantiate() {
    if (!slug || !projectId.trim() || repoIds.length === 0) {
      setApplyError('Project ID and at least one repo ID are required.');
      return;
    }
    setApplyBusy(true);
    setApplyError(null);
    try {
      const r = await instantiateLibrarySolution(slug, {
        projectId: projectId.trim(),
        repoIds,
        slotValues,
        variantId: selectedVariantId || undefined,
        branch: branch.trim() || undefined,
      });
      navigate(`/v3/p/${projectId.trim()}/r/${r.executionRunId}`);
    } catch (e) {
      setApplyError((e as Error)?.message ?? 'Instantiate failed');
    } finally {
      setApplyBusy(false);
    }
  }

  if (loading) {
    return (
      <div style={{ padding: 32, textAlign: 'center', color: 'var(--v3-text-subtle)' }}>
        Loading solution…
      </div>
    );
  }
  if (error || !detail) {
    return (
      <div style={{ padding: 16 }}>
        <div style={errorBoxStyle}>
          {error ?? 'Solution not found.'}
          <div style={{ marginTop: 8 }}>
            <Link to="/v3/library" style={{ color: 'var(--v3-brand)', fontSize: 12 }}>
              ← back to library
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const s = detail.solution;

  return (
    <div style={{ padding: 16, paddingBottom: 48, display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Breadcrumb */}
      <div style={{ fontSize: 12, color: 'var(--v3-text-subtle)' }}>
        <Link to="/v3/library" style={{ color: 'var(--v3-text-subtle)' }}>library</Link>
        {' / '}
        <span style={{ color: 'var(--v3-text)' }}>{s.slug}</span>
      </div>

      {/* Hero */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
          <h1 style={{ fontSize: 22, fontWeight: 700, color: 'var(--v3-text)', margin: 0 }}>
            {s.name}
          </h1>
          {detail.locked && (
            <span style={lockChipStyle}>🔒 Upgrade to {detail.unlockTier} to use</span>
          )}
        </div>
        {s.tagline && (
          <p style={{ fontSize: 13, color: 'var(--v3-text-subtle)', margin: 0 }}>{s.tagline}</p>
        )}
      </div>

      {/* Description */}
      {s.description && (
        <div style={{
          fontSize: 13,
          color: 'var(--v3-text)',
          lineHeight: 1.6,
          whiteSpace: 'pre-wrap',
          padding: 12,
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border)',
          borderRadius: 6,
        }}>
          {s.description}
        </div>
      )}

      {/* Dependencies hint */}
      {detail.dependencies.length > 0 && (
        <div style={{
          padding: 10,
          background: 'rgba(91,141,239,0.08)',
          border: '1px solid rgba(91,141,239,0.30)',
          borderRadius: 6,
          fontSize: 12,
          color: 'var(--v3-text)',
        }}>
          ↪ This solution composes with{' '}
          {detail.dependencies.map((d, i) => (
            <span key={d.dependsOnId}>
              {i > 0 && ', '}
              <strong>{d.required ? 'requires' : 'recommends'}</strong>{' '}
              <code style={{ fontSize: 11 }}>{d.dependsOnId.slice(0, 8)}</code>
            </span>
          ))}
          .
        </div>
      )}

      {/* Locked path */}
      {detail.locked && (
        <div style={{
          padding: 16,
          background: 'rgba(245,158,11,0.12)',
          border: '1px solid rgba(245,158,11,0.4)',
          borderRadius: 6,
        }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: '#FBBF24', marginBottom: 6 }}>
            This solution requires the {detail.unlockTier} tier.
          </div>
          <Link to="/v3/pricing" style={{
            display: 'inline-block',
            marginTop: 6,
            padding: '6px 12px',
            background: 'var(--v3-brand)',
            color: 'var(--v3-text-on-brand)',
            borderRadius: 4,
            textDecoration: 'none',
            fontSize: 12,
            fontWeight: 600,
          }}>
            See pricing →
          </Link>
        </div>
      )}

      {/* Use form (gated on lock state) */}
      {!detail.locked && (
        <>
          <FormSection title="1. Pick project + repo">
            <FormField label="Project ID">
              <input value={projectId} onChange={(e) => setProjectId(e.target.value)}
                     placeholder="proj-uuid" style={inputStyle} />
            </FormField>
            <FormField label="Repo IDs" hint="Comma-separated UUIDs (most readymades target a single repo).">
              <input value={repoIdsInput} onChange={(e) => setRepoIdsInput(e.target.value)}
                     placeholder="repo-uuid" style={inputStyle} />
            </FormField>
            <FormField label="Branch (optional)">
              <input value={branch} onChange={(e) => setBranch(e.target.value)}
                     placeholder="feature/add-stripe-checkout" style={inputStyle} />
            </FormField>
          </FormSection>

          {/* Variant resolution */}
          <FormSection title="2. Pick variant (or auto-resolve from your stack)">
            <p style={{ margin: 0, fontSize: 12, color: 'var(--v3-text-subtle)' }}>
              {detail.variants.length === 1
                ? `Single variant — ${detail.variants[0].language} / ${detail.variants[0].framework}.`
                : `${detail.variants.length} variants available.  Click "Resolve from repo" to auto-pick by your repo's detected stack, or pick manually below.`}
            </p>
            {detail.variants.length > 1 && (
              <>
                <button type="button" onClick={onResolveVariant}
                        disabled={resolveBusy || !projectId.trim() || repoIds.length === 0}
                        style={primaryButton(resolveBusy || !projectId.trim() || repoIds.length === 0)}>
                  {resolveBusy ? 'Resolving…' : 'Resolve from repo'}
                </button>
                {resolveError && <div style={errorBoxStyle}>{resolveError}</div>}
                {resolution && <ResolutionBanner result={resolution} />}
                <select value={selectedVariantId}
                        onChange={(e) => setSelectedVariantId(e.target.value)}
                        style={inputStyle}>
                  <option value="">(auto)</option>
                  {detail.variants.map((v) => (
                    <option key={v.id} value={v.id}>
                      {variantLabel(v)}
                    </option>
                  ))}
                </select>
              </>
            )}
          </FormSection>

          {/* Slots */}
          {detail.slots.length > 0 && (
            <FormSection title="3. Solution parameters">
              {detail.slots.map((slot) => (
                <SlotInput key={slot.name} slot={slot}
                           value={slotValues[slot.name] ?? ''}
                           onChange={(v) => setSlotValues({ ...slotValues, [slot.name]: v })} />
              ))}
            </FormSection>
          )}

          {/* Tier-stratified cost framing */}
          <div style={{
            padding: '6px 10px',
            background: showCostCap ? 'rgba(91,141,239,0.10)' : 'rgba(122,209,200,0.10)',
            border: `1px solid ${showCostCap ? 'rgba(91,141,239,0.35)' : 'rgba(122,209,200,0.35)'}`,
            borderRadius: 4,
            fontSize: 11.5,
            color: 'var(--v3-text)',
            lineHeight: 1.5,
          }}>
            {showCostCap
              ? '💳 Will use platform credits — capped at your tier\'s per-run budget.'
              : '🔑 Will use your own LLM provider keys (BYOK) — billed direct by the provider.'}
          </div>

          <FormSection title="4. Use this solution">
            <p style={{ margin: 0, fontSize: 12, color: 'var(--v3-text-subtle)' }}>
              Submits this solution as an ExecutionRun.  We'll pre-fill the spec from the variant's
              template + your slot values, run it through the existing pipeline, and open a PR.
            </p>
            <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
              <button type="button" onClick={onInstantiate} disabled={applyBusy}
                      style={primaryButton(applyBusy)}>
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

function ResolutionBanner({ result }: { result: VariantResolutionResult }) {
  const labelByReason: Record<string, { color: string; label: string; explainer: string }> = {
    OK: {
      color: '#4ADE80',
      label: 'Resolved',
      explainer: `Best match: ${result.detectedLanguage} + ${result.detectedFramework} variant.`,
    },
    NO_VARIANT_FOR_STACK: {
      color: '#FBBF24',
      label: 'No exact match',
      explainer: `Detected ${result.detectedLanguage}${result.detectedFramework ? ' + ' + result.detectedFramework : ''}, but no variant for that stack — pick manually below.`,
    },
    STACK_NOT_DETECTED: {
      color: '#FBBF24',
      label: 'Stack not detected',
      explainer: 'We couldn\'t read the repo\'s stack — sync the repo first or pick a variant manually.',
    },
    REPO_NOT_LINKED: {
      color: '#FCA5A5',
      label: 'Repo not linked',
      explainer: 'That repo isn\'t linked to this project. Check the IDs.',
    },
  };
  const meta = labelByReason[result.reason] ?? labelByReason.STACK_NOT_DETECTED;
  return (
    <div style={{
      padding: 8,
      background: 'var(--v3-surface-elevated)',
      border: '1px solid var(--v3-border)',
      borderRadius: 4,
      fontSize: 11.5,
      color: 'var(--v3-text)',
    }}>
      <span style={{ color: meta.color, fontWeight: 600, marginRight: 6 }}>{meta.label}</span>
      {meta.explainer}
    </div>
  );
}

function variantLabel(v: ReadymadeVariant): string {
  const opts = v.options;
  let optsLabel = '';
  if (opts) {
    try {
      const parsed = JSON.parse(opts);
      const pairs = Object.entries(parsed)
        .map(([k, val]) => `${k}=${val}`)
        .join(', ');
      if (pairs) optsLabel = ` (${pairs})`;
    } catch {
      /* ignore */
    }
  }
  return `${v.language} · ${v.framework}${optsLabel}`;
}

function FormSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{
      padding: 14,
      border: '1px solid var(--v3-border)',
      borderRadius: 6,
      background: 'var(--v3-surface)',
      display: 'flex',
      flexDirection: 'column',
      gap: 10,
    }}>
      <h3 style={{ fontSize: 13, fontWeight: 600, color: 'var(--v3-text)', margin: 0 }}>{title}</h3>
      {children}
    </div>
  );
}

function FormField({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <label style={{ fontSize: 11, fontWeight: 600, color: 'var(--v3-text-subtle)', textTransform: 'uppercase', letterSpacing: 0.4 }}>
        {label}
      </label>
      {children}
      {hint && <span style={{ fontSize: 10.5, color: 'var(--v3-text-subtle)', lineHeight: 1.4 }}>{hint}</span>}
    </div>
  );
}

function SlotInput({ slot, value, onChange }: {
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
    } catch { /* fallthrough */ }
    return (
      <FormField label={label} hint={slot.description ?? undefined}>
        <select value={value} onChange={(e) => onChange(e.target.value)} style={inputStyle}>
          {!slot.required && <option value="">(none)</option>}
          {options.map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      </FormField>
    );
  }
  if (slot.type === 'BOOLEAN') {
    return (
      <FormField label={label} hint={slot.description ?? undefined}>
        <select value={value} onChange={(e) => onChange(e.target.value)} style={inputStyle}>
          <option value="">(unset)</option>
          <option value="true">true</option>
          <option value="false">false</option>
        </select>
      </FormField>
    );
  }
  const isPassword = slot.type === 'SECRET';
  return (
    <FormField label={label} hint={slot.description ?? undefined}>
      <input
        type={isPassword ? 'password' : (slot.type === 'NUMBER' ? 'number' : 'text')}
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
