import {useEffect, useState, useCallback} from 'react';
import {Flag, RefreshCw, Save, ArrowLeft} from 'lucide-react';
import {useNavigate} from 'react-router-dom';
import RoleGuard from '@/components/auth/RoleGuard';
import {cn, errMsg} from '@/lib/utils';
import * as api from '@/lib/api';
import {useUiStore} from '@/stores/useUiStore';

// ── Category grouping ────────────────────────────────────────────────────────

const CATEGORIES: {label: string; prefix: string}[] = [
  {label: 'Integrations', prefix: 'integrations.'},
  {label: 'Billing', prefix: 'billing.'},
  {label: 'Pipeline', prefix: 'preview.|validator.|recovery.|submissions.'},
  {label: 'Security', prefix: 'pii.|sandbox.'},
];

function categorize(flags: api.FeatureFlagDto[]): {label: string; flags: api.FeatureFlagDto[]}[] {
  const result: {label: string; flags: api.FeatureFlagDto[]}[] = [];
  const used = new Set<string>();

  for (const cat of CATEGORIES) {
    const prefixes = cat.prefix.split('|');
    const matched = flags.filter((f) => prefixes.some((p) => f.flagKey.startsWith(p)));
    if (matched.length > 0) {
      result.push({label: cat.label, flags: matched});
      matched.forEach((f) => used.add(f.flagKey));
    }
  }

  const uncategorized = flags.filter((f) => !used.has(f.flagKey));
  if (uncategorized.length > 0) {
    result.push({label: 'Other', flags: uncategorized});
  }

  return result;
}

// ── Toggle switch ────────────────────────────────────────────────────────────

function ToggleSwitch({
  enabled,
  onChange,
  saving,
}: {
  enabled: boolean;
  onChange: (v: boolean) => void;
  saving: boolean;
}) {
  return (
    <button
      onClick={() => !saving && onChange(!enabled)}
      disabled={saving}
      className={cn(
        'relative inline-flex h-6 w-11 items-center rounded-full transition-colors',
        enabled ? 'bg-accent-green' : 'bg-[var(--gray-400)]',
        saving && 'opacity-50 cursor-not-allowed',
      )}
    >
      <span
        className={cn(
          'inline-block h-4 w-4 transform rounded-full bg-white transition-transform',
          enabled ? 'translate-x-6' : 'translate-x-1',
        )}
      />
    </button>
  );
}

// ── Main page ────────────────────────────────────────────────────────────────

export default function FeatureFlagsPage() {
  return (
    <RoleGuard requireSuperAdmin>
      <FeatureFlagsPageInner />
    </RoleGuard>
  );
}

function FeatureFlagsPageInner() {
  const navigate = useNavigate();
  const {addToast} = useUiStore();
  const [flags, setFlags] = useState<api.FeatureFlagDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState<Record<string, boolean>>({});

  const load = useCallback(() => {
    setLoading(true);
    api
      .adminListFeatureFlags()
      .then(setFlags)
      .catch((e: unknown) =>
        addToast({type: 'error', message: errMsg(e) || 'Failed to load feature flags'}),
      )
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleToggle = async (flag: api.FeatureFlagDto, newVal: boolean) => {
    setSaving((p) => ({...p, [flag.flagKey]: true}));
    try {
      const updated = await api.adminUpdateFeatureFlag(flag.flagKey, String(newVal));
      setFlags((prev) => prev.map((f) => (f.flagKey === flag.flagKey ? updated : f)));
      addToast({type: 'success', message: `${flag.flagKey} = ${newVal}`});
    } catch (e) {
      addToast({type: 'error', message: errMsg(e) || 'Update failed'});
    } finally {
      setSaving((p) => ({...p, [flag.flagKey]: false}));
    }
  };

  const handleSave = async (flag: api.FeatureFlagDto) => {
    const val = edits[flag.flagKey];
    if (val === undefined || val === flag.flagValue) return;
    setSaving((p) => ({...p, [flag.flagKey]: true}));
    try {
      const updated = await api.adminUpdateFeatureFlag(flag.flagKey, val);
      setFlags((prev) => prev.map((f) => (f.flagKey === flag.flagKey ? updated : f)));
      setEdits((p) => {
        const n = {...p};
        delete n[flag.flagKey];
        return n;
      });
      addToast({type: 'success', message: `${flag.flagKey} updated`});
    } catch (e) {
      addToast({type: 'error', message: errMsg(e) || 'Update failed'});
    } finally {
      setSaving((p) => ({...p, [flag.flagKey]: false}));
    }
  };

  const groups = categorize(flags);

  return (
    <div className='h-full flex flex-col overflow-hidden relative'>
      <div
        aria-hidden
        className='pointer-events-none absolute inset-0 -z-10'
        style={{
          background:
            'radial-gradient(ellipse 60% 40% at 20% 0%, color-mix(in srgb, var(--accent-blue) 12%, transparent), transparent 60%)',
        }}
      />
      {/* Header */}
      <div className='flex items-center gap-3 px-4 sm:px-6 py-5 border-b border-[var(--border-default)] flex-shrink-0'>
        <button
          onClick={() => navigate('/admin')}
          className='text-muted hover:text-secondary transition-colors'
          aria-label='Back'
        >
          <ArrowLeft className='h-4 w-4' />
        </button>
        <div className='flex items-center gap-3 flex-1 min-w-0'>
          <span className='inline-flex h-8 w-8 items-center justify-center rounded-md border border-[color-mix(in_srgb,var(--accent-blue)_30%,transparent)] bg-[color-mix(in_srgb,var(--accent-blue)_12%,transparent)] text-accent-blue flex-shrink-0'>
            <Flag className='h-4 w-4' />
          </span>
          <div className='min-w-0'>
            <div className='inline-block font-mono text-[10.5px] font-medium uppercase tracking-[0.08em] text-accent-blue'>
              Platform control
            </div>
            <h1 className='text-lg sm:text-xl font-semibold text-primary tracking-[-0.015em] leading-tight'>
              Feature flags
            </h1>
          </div>
        </div>
        <button
          onClick={load}
          disabled={loading}
          className='inline-flex items-center gap-1.5 px-3 py-1.5 text-xs text-secondary hover:text-primary bg-[var(--gray-200)] hover:bg-[var(--border-default)] rounded-md border border-[var(--border-default)] transition-colors flex-shrink-0'
        >
          <RefreshCw className={cn('h-3.5 w-3.5', loading && 'animate-spin')} />
          Refresh
        </button>
      </div>

      {/* Content */}
      <div className='flex-1 overflow-auto p-6 space-y-8'>
        {loading && flags.length === 0 ? (
          <div className='flex items-center justify-center py-20 text-muted text-sm'>
            Loading flags...
          </div>
        ) : (
          groups.map((group) => (
            <div key={group.label}>
              <h2 className='text-sm font-semibold text-secondary uppercase tracking-wider mb-3'>
                {group.label}
              </h2>
              <div className='rounded-lg border border-[var(--border-default)] overflow-hidden'>
                <div className='overflow-x-auto'>
                  <table className='w-full text-sm min-w-[640px] lg:min-w-0'>
                    <thead>
                      <tr className='border-b border-[var(--border-default)] bg-[var(--gray-100)]'>
                        <th className='text-left px-4 py-2.5 text-muted font-medium'>Key</th>
                        <th className='text-left px-4 py-2.5 text-muted font-medium w-64'>Value</th>
                        <th className='text-left px-4 py-2.5 text-muted font-medium w-24'>Type</th>
                        <th className='text-left px-4 py-2.5 text-muted font-medium w-24'>Scope</th>
                        <th className='text-left px-4 py-2.5 text-muted font-medium hidden lg:table-cell'>
                          Description
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {group.flags.map((flag) => {
                        const editVal = edits[flag.flagKey];
                        const isDirty = editVal !== undefined && editVal !== flag.flagValue;

                        return (
                          <tr
                            key={flag.flagKey}
                            className='border-b border-[var(--border-default)] last:border-b-0 hover:bg-[var(--gray-100)]'
                          >
                            <td className='px-4 py-3 text-primary font-mono text-xs'>
                              {flag.flagKey}
                            </td>
                            <td className='px-4 py-3'>
                              {flag.valueType === 'BOOLEAN' ? (
                                <ToggleSwitch
                                  enabled={flag.flagValue === 'true'}
                                  saving={!!saving[flag.flagKey]}
                                  onChange={(v) => handleToggle(flag, v)}
                                />
                              ) : (
                                <div className='flex items-center gap-2'>
                                  <input
                                    type={
                                      flag.valueType === 'INTEGER' ||
                                      flag.valueType === 'PERCENTAGE'
                                        ? 'number'
                                        : 'text'
                                    }
                                    value={editVal ?? flag.flagValue}
                                    onChange={(e) =>
                                      setEdits((p) => ({...p, [flag.flagKey]: e.target.value}))
                                    }
                                    className='bg-[var(--gray-200)] border border-[var(--border-default)] rounded px-2 py-1 text-xs text-primary w-full focus:outline-none focus:border-accent-blue/50'
                                  />
                                  {isDirty && (
                                    <button
                                      onClick={() => handleSave(flag)}
                                      disabled={!!saving[flag.flagKey]}
                                      className='text-accent-green hover:text-accent-green/80 transition-colors flex-shrink-0'
                                    >
                                      <Save className='h-3.5 w-3.5' />
                                    </button>
                                  )}
                                </div>
                              )}
                            </td>
                            <td className='px-4 py-3'>
                              <span
                                className={cn(
                                  'text-[10px] font-mono px-1.5 py-0.5 rounded',
                                  flag.valueType === 'BOOLEAN'
                                    ? 'bg-accent-blue/10 text-accent-blue'
                                    : flag.valueType === 'STRING'
                                      ? 'bg-accent-sky/10 text-accent-sky'
                                      : flag.valueType === 'INTEGER'
                                        ? 'bg-accent-amber/10 text-accent-amber'
                                        : 'bg-accent-green/10 text-accent-green',
                                )}
                              >
                                {flag.valueType}
                              </span>
                            </td>
                            <td className='px-4 py-3 text-muted text-xs'>{flag.scope}</td>
                            <td className='px-4 py-3 text-muted text-xs hidden lg:table-cell'>
                              {flag.description || '-'}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
