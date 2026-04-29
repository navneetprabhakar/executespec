import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  getMigrationDetail,
  migrationApply,
  migrationDryRun,
  requiresCostCap,
  type MigrationDetail,
  type MigrationDryRunResult,
  type MigrationSlot,
} from '@/lib/api';
import { useAuthStore } from '@/stores/useAuthStore';
import ProjectRepoPicker from '../components/ProjectRepoPicker';

/**
 * R-340 — recipe detail page with the W3 two-phase halt-and-inspect
 * plan-gate UX.
 *
 * <p>Flow:
 * <ol>
 *   <li>Page loads → GET /api/migrations/{slug} for recipe / slots /
 *       variants / dependencies + lock state.</li>
 *   <li>User picks projectId + repoIds (today: free-form text inputs;
 *       a project + repo picker lands when this page is wired into
 *       the project dashboard).</li>
 *   <li>User clicks "Compute plan" → POST /dry-run → renders Phase 1
 *       per-repo applicability + cost / file estimate + sample diff.</li>
 *   <li>User fills slots (validators run client-side; full validation
 *       happens server-side on apply).</li>
 *   <li>User clicks "Apply" → POST /apply → navigates to the run-
 *       detail page where the existing pipeline lifecycle takes over.</li>
 * </ol>
 *
 * <p>Locked recipes (caller below recipe.tier) render the upgrade
 * CTA instead of the apply form.
 */
export default function V3MigrationDetail() {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  // R-341 — caller's tier drives the cost-section UX (cap-input vs
  // info-only chip).
  const user = useAuthStore((s) => s.user);
  const showCostCap = requiresCostCap(user);

  const [detail, setDetail] = useState<MigrationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Apply-form state.  R-335 (UX rev): projectId + repoIds now driven
  // by the shared {@link ProjectRepoPicker} dropdown component instead
  // of free-form UUID text inputs.
  const [projectId, setProjectId] = useState('');
  const [repoIds, setRepoIds] = useState<string[]>([]);
  const [branch, setBranch] = useState('');
  const [slotValues, setSlotValues] = useState<Record<string, string>>({});

  // Dry-run state
  const [dryRunBusy, setDryRunBusy] = useState(false);
  const [dryRunResult, setDryRunResult] = useState<MigrationDryRunResult | null>(null);
  const [dryRunError, setDryRunError] = useState<string | null>(null);

  // Apply state
  const [applyBusy, setApplyBusy] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);

  useEffect(() => {
    if (!slug) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    getMigrationDetail(slug)
      .then((data) => {
        if (cancelled) return;
        setDetail(data);
        // Pre-fill slot defaults so the form has sensible starting state.
        const seed: Record<string, string> = {};
        for (const s of data.slots) {
          if (s.defaultValue) seed[s.name] = s.defaultValue;
        }
        setSlotValues(seed);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setError(e?.message ?? 'Failed to load recipe');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [slug]);

  async function onComputePlan() {
    if (!slug || !projectId || repoIds.length === 0) {
      setDryRunError('Pick a project and at least one repo first.');
      return;
    }
    setDryRunBusy(true);
    setDryRunError(null);
    try {
      const r = await migrationDryRun(slug, { projectId, repoIds });
      setDryRunResult(r);
    } catch (e) {
      setDryRunError((e as Error)?.message ?? 'Dry-run failed');
    } finally {
      setDryRunBusy(false);
    }
  }

  async function onApply() {
    if (!slug || !projectId || repoIds.length === 0) {
      setApplyError('Pick a project and at least one repo first.');
      return;
    }
    setApplyBusy(true);
    setApplyError(null);
    try {
      const r = await migrationApply(slug, {
        projectId,
        repoIds,
        slotValues,
        branch: branch.trim() || undefined,
      });
      navigate(`/projects/${projectId}/runs/${r.executionRunId}`);
    } catch (e) {
      setApplyError((e as Error)?.message ?? 'Apply failed');
    } finally {
      setApplyBusy(false);
    }
  }

  if (loading) {
    return (
      <div style={{ padding: 32, textAlign: 'center', color: 'var(--v3-text-subtle)' }}>
        Loading recipe…
      </div>
    );
  }
  if (error || !detail) {
    return (
      <div style={{ padding: 16 }}>
        <div style={{
          padding: 12,
          background: 'rgba(239,68,68,0.12)',
          border: '1px solid rgba(239,68,68,0.36)',
          color: '#FCA5A5',
          borderRadius: 6,
          fontSize: 13,
        }}>
          {error ?? 'Recipe not found.'}
          <div style={{ marginTop: 8 }}>
            <Link to="/migrations" style={{ color: 'var(--v3-brand)', fontSize: 12 }}>
              ← back to migrations
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const r = detail.recipe;

  return (
    <div style={{ padding: 16, paddingBottom: 48, display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Breadcrumb */}
      <div style={{ fontSize: 12, color: 'var(--v3-text-subtle)' }}>
        <Link to="/migrations" style={{ color: 'var(--v3-text-subtle)' }}>migrations</Link>
        {' / '}
        <span style={{ color: 'var(--v3-text)' }}>{r.slug}</span>
      </div>

      {/* Hero */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
          <h1 style={{ fontSize: 22, fontWeight: 700, color: 'var(--v3-text)', margin: 0 }}>
            {r.name}
          </h1>
          {detail.locked && (
            <span style={lockChipStyle}>🔒 Upgrade to {detail.unlockTier} to use</span>
          )}
        </div>
        {r.tagline && (
          <p style={{ fontSize: 13, color: 'var(--v3-text-subtle)', margin: 0 }}>{r.tagline}</p>
        )}
      </div>

      {/* Description */}
      {r.description && (
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
          {r.description}
        </div>
      )}

      {/* Locked path — show upgrade CTA + skip the apply form */}
      {detail.locked && (
        <div style={{
          padding: 16,
          background: 'rgba(245,158,11,0.12)',
          border: '1px solid rgba(245,158,11,0.4)',
          borderRadius: 6,
        }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: '#FBBF24', marginBottom: 6 }}>
            This recipe requires the {detail.unlockTier} tier.
          </div>
          <Link to="/pricing" style={{
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

      {/* Apply form (gated on lock state) */}
      {!detail.locked && (
        <>
          {/* Project + repos — R-335 dropdown picker */}
          <FormSection title="1. Where will this migration run?">
            <ProjectRepoPicker
              projectId={projectId}
              repoIds={repoIds}
              onProjectChange={(id) => {
                setProjectId(id);
                // Stale dry-run output references the prior project's
                // repos; clear it so the user sees a fresh applicability
                // table after switching projects.
                setDryRunResult(null);
              }}
              onRepoIdsChange={setRepoIds}
            />
            <FormField label="Branch (optional)" hint="Override the default branch for this run.">
              <input
                value={branch}
                onChange={(e) => setBranch(e.target.value)}
                placeholder="feature/migrate-to-fastify"
                style={inputStyle}
              />
            </FormField>
          </FormSection>

          {/* Slots */}
          {detail.slots.length > 0 && (
            <FormSection title="2. Recipe parameters">
              {detail.slots.map((s) => (
                <SlotInput
                  key={s.name}
                  slot={s}
                  value={slotValues[s.name] ?? ''}
                  onChange={(v) => setSlotValues({ ...slotValues, [s.name]: v })}
                />
              ))}
            </FormSection>
          )}

          {/* Phase 1 — compute plan */}
          <FormSection title="3. Plan (Phase 1)">
            <p style={{ margin: 0, fontSize: 12, color: 'var(--v3-text-subtle)' }}>
              See what'll happen across your repos before you commit.  No LLM calls in Phase 1 —
              cost estimates come from historical data; sample diff comes from cache (when present).
            </p>
            <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
              <button
                type="button"
                onClick={onComputePlan}
                disabled={dryRunBusy}
                style={primaryButton(dryRunBusy)}
              >
                {dryRunBusy ? 'Computing…' : 'Compute plan'}
              </button>
            </div>
            {dryRunError && (
              <div style={errorBoxStyle}>{dryRunError}</div>
            )}
            {dryRunResult && <DryRunSummary result={dryRunResult} showCostCap={showCostCap} />}
          </FormSection>

          {/* Phase 2 — apply */}
          <FormSection title="4. Apply (Phase 2)">
            <p style={{ margin: 0, fontSize: 12, color: 'var(--v3-text-subtle)' }}>
              Submits this migration as an ExecutionRun.  Same recipe runs in parallel across every
              applicable repo; you get one PR per repo when each is done.
            </p>
            <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
              <button
                type="button"
                onClick={onApply}
                disabled={applyBusy || !dryRunResult}
                style={primaryButton(applyBusy || !dryRunResult)}
                title={!dryRunResult ? 'Compute the plan first.' : ''}
              >
                {applyBusy ? 'Applying…' : 'Apply migration'}
              </button>
            </div>
            {applyError && (
              <div style={errorBoxStyle}>{applyError}</div>
            )}
          </FormSection>
        </>
      )}
    </div>
  );
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
      <h3 style={{ fontSize: 13, fontWeight: 600, color: 'var(--v3-text)', margin: 0 }}>
        {title}
      </h3>
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
      {hint && (
        <span style={{ fontSize: 10.5, color: 'var(--v3-text-subtle)', lineHeight: 1.4 }}>
          {hint}
        </span>
      )}
    </div>
  );
}

function SlotInput({ slot, value, onChange }: {
  slot: MigrationSlot;
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
      /* fallthrough — render as text */
    }
    return (
      <FormField label={label} hint={slot.description ?? undefined}>
        <select value={value} onChange={(e) => onChange(e.target.value)} style={inputStyle}>
          {!slot.required && <option value="">(none)</option>}
          {options.map((o) => (
            <option key={o} value={o}>{o}</option>
          ))}
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

function DryRunSummary({ result, showCostCap }: { result: MigrationDryRunResult; showCostCap: boolean }) {
  const totalCost = result.estCostLowTotal && result.estCostHighTotal
    ? `≈ ${parseFloat(result.estCostLowTotal).toFixed(0)}–${parseFloat(result.estCostHighTotal).toFixed(0)} credits total`
    : null;
  const totalFiles = result.estFilesLowTotal != null && result.estFilesHighTotal != null
    ? `${result.estFilesLowTotal}–${result.estFilesHighTotal} files total`
    : null;

  // R-343 (P1 from UX persona review) — Riley wants a one-line
  // applicability rollup: "3 of 4 applicable · 1 stack mismatch".
  // Avoids hunting through per-repo cards to find blockers.
  const total = result.perRepo.length;
  const applicable = result.perRepo.filter((r) => r.status === 'APPLICABLE').length;
  const blocked = total - applicable;
  const blockedReasons = blocked > 0
    ? Array.from(new Set(result.perRepo
        .filter((r) => r.status !== 'APPLICABLE')
        .map((r) => r.status))).join(', ').toLowerCase().replace(/_/g, ' ')
    : null;

  return (
    <div style={{
      marginTop: 8,
      padding: 12,
      background: 'var(--v3-surface-elevated)',
      border: '1px solid var(--v3-border)',
      borderRadius: 6,
    }}>
      <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--v3-text)', marginBottom: 8 }}>
        Plan summary
      </div>
      {/* R-343 — applicability rollup line.  Renders only when total > 0. */}
      {total > 0 && (
        <div style={{
          marginBottom: 10,
          padding: '6px 10px',
          background: blocked === 0 ? 'rgba(74,222,128,0.08)' : 'rgba(251,191,36,0.10)',
          border: `1px solid ${blocked === 0 ? 'rgba(74,222,128,0.30)' : 'rgba(251,191,36,0.35)'}`,
          borderRadius: 4,
          fontSize: 12,
          color: 'var(--v3-text)',
          lineHeight: 1.5,
        }}>
          <strong style={{ color: blocked === 0 ? '#4ADE80' : '#FBBF24' }}>
            {applicable} of {total}
          </strong>{' '}
          {applicable === 1 ? 'repo is' : 'repos are'} applicable
          {blocked > 0 && (
            <>
              {' · '}
              <span style={{ color: '#FBBF24' }}>
                {blocked} blocked{blockedReasons ? ` (${blockedReasons})` : ''}
              </span>
            </>
          )}
          {blocked === 0 && total > 1 && (
            <span style={{ color: 'var(--v3-text-subtle)' }}> · ready to fan out</span>
          )}
        </div>
      )}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
        {totalCost && <span style={chipStyle}>{totalCost}</span>}
        {totalFiles && <span style={chipStyle}>{totalFiles}</span>}
        <span style={chipStyle}>{result.perRepo.length} repo(s) selected</span>
      </div>
      {/* R-341 — tier-stratified cost framing. */}
      <div style={{
        marginBottom: 10,
        padding: '6px 10px',
        background: showCostCap ? 'rgba(91,141,239,0.10)' : 'rgba(122,209,200,0.10)',
        border: `1px solid ${showCostCap ? 'rgba(91,141,239,0.35)' : 'rgba(122,209,200,0.35)'}`,
        borderRadius: 4,
        fontSize: 11.5,
        color: 'var(--v3-text)',
        lineHeight: 1.5,
      }}>
        {showCostCap
          ? '💳 Will use platform credits — capped at your tier\'s per-run budget. Upgrade or add a BYOK key on Settings to remove the cap.'
          : '🔑 Will use your own LLM provider keys (BYOK) — billed direct by the provider, no platform-side cap.'}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {result.perRepo.map((row) => (
          <RepoApplicabilityRow key={row.repoId} row={row} />
        ))}
      </div>
      {result.sampleDiff && (
        <details style={{ marginTop: 10 }}>
          <summary style={{ cursor: 'pointer', fontSize: 12, color: 'var(--v3-text-subtle)' }}>
            Sample diff (representative file)
          </summary>
          <pre style={{
            marginTop: 8,
            padding: 10,
            background: 'var(--v3-bg)',
            border: '1px solid var(--v3-border)',
            borderRadius: 4,
            fontSize: 11,
            color: 'var(--v3-text)',
            overflow: 'auto',
            maxHeight: 320,
          }}>{result.sampleDiff}</pre>
        </details>
      )}
    </div>
  );
}

function RepoApplicabilityRow({ row }: { row: import('@/lib/api').MigrationDryRunRepoApplicability }) {
  const statusColor = row.status === 'APPLICABLE' ? '#4ADE80' : '#FBBF24';
  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      padding: '6px 8px',
      background: 'var(--v3-bg)',
      border: '1px solid var(--v3-border)',
      borderRadius: 4,
      fontSize: 12,
    }}>
      <span style={{ color: statusColor, fontWeight: 600, minWidth: 110, fontSize: 11 }}>
        {row.status}
      </span>
      <span style={{ color: 'var(--v3-text)', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', flex: 1 }}>
        {row.fullName ?? row.repoId.slice(0, 8)}
      </span>
      {row.estFilesHigh != null && (
        <span style={chipStyle}>{row.estFilesLow}–{row.estFilesHigh} files</span>
      )}
      {row.reason && row.status !== 'APPLICABLE' && (
        <span style={{ color: 'var(--v3-text-subtle)', fontSize: 10.5 }}>
          {row.reason}
        </span>
      )}
    </div>
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

const chipStyle: React.CSSProperties = {
  fontSize: 10.5,
  color: 'var(--v3-text-subtle)',
  background: 'var(--v3-bg)',
  padding: '2px 6px',
  borderRadius: 4,
  border: '1px solid var(--v3-border)',
};

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
