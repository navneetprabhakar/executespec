import { useEffect, useState } from 'react';
import { X, UserPlus } from 'lucide-react';
import { cn } from '@/lib/utils';
import * as api from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';

interface Props { onClose: () => void; onSuccess: () => void; }

const TIER_LABEL_FALLBACK: Record<string, string> = { FREE: 'Free', PRO: 'Pro', ENTERPRISE: 'Enterprise' };

export default function AdminInviteModal({ onClose, onSuccess }: Props) {
  const { addToast } = useUiStore();

  const [email, setEmail]         = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName]   = useState('');
  const [tier, setTier]           = useState('FREE');
  const [orgMode, setOrgMode]     = useState<'existing' | 'new'>('existing');
  const [orgId, setOrgId]         = useState('');
  const [newOrgName, setNewOrgName] = useState('');
  const [orgRole, setOrgRole]     = useState('DEVELOPER');
  const [orgs, setOrgs]           = useState<api.OrgDto[]>([]);
  const [tierConfigs, setTierConfigs] = useState<api.TierConfig[]>([]);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    api.adminListOrgs().then(setOrgs).catch(() => {});
    api.adminGetTierConfig().then(setTierConfigs).catch(() => {});
  }, []);

  const tierLabel = (t: string) => {
    const fromDb = tierConfigs.find((tc) => tc.tier === t);
    return fromDb?.displayName ?? TIER_LABEL_FALLBACK[t] ?? t;
  };

  const needsOrg = tier !== 'FREE';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const payload: api.AdminInviteRequest = {
        email, firstName, lastName, tier,
        ...(needsOrg && orgMode === 'existing' && orgId ? { orgId } : {}),
        ...(needsOrg && orgMode === 'new' && newOrgName ? { newOrgName } : {}),
        ...(needsOrg ? { orgRole } : {}),
      };
      await api.adminInviteUser(payload);
      addToast({ type: 'success', message: `Invite sent to ${email}` });
      onSuccess();
      onClose();
    } catch (err: any) {
      addToast({ type: 'error', message: err.message });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-2 md:p-4"
      style={{ backdropFilter: 'blur(4px)', background: 'rgba(0,0,0,0.65)' }}
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-sm bg-[var(--gray-200)] border border-[var(--border-default)]  p-6 space-y-5 overflow-y-auto"
        style={{ maxHeight: '95dvh' }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-primary flex items-center gap-2">
            <UserPlus className="h-4 w-4 text-accent-blue" />
            Invite User
          </h2>
          <button onClick={onClose} className="text-muted hover:text-secondary p-2 -m-1">
            <X className="h-4 w-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Name row */}
          <div className="grid grid-cols-2 gap-3">
            <Field label="First Name" required>
              <Input value={firstName} onChange={setFirstName} placeholder="Jane" required />
            </Field>
            <Field label="Last Name" required>
              <Input value={lastName} onChange={setLastName} placeholder="Doe" required />
            </Field>
          </div>

          {/* Email */}
          <Field label="Email" required>
            <Input type="email" value={email} onChange={setEmail} placeholder="jane@example.com" required />
          </Field>

          {/* Tier */}
          <Field label="Tier">
            <select
              value={tier}
              onChange={(e) => { setTier(e.target.value); setOrgId(''); setNewOrgName(''); }}
              className={selectCls}
            >
              {(tierConfigs.length > 0 ? tierConfigs : [{ tier: 'FREE' }, { tier: 'PRO' }, { tier: 'ENTERPRISE' }]).map((tc) => (
                <option key={tc.tier} value={tc.tier}>{tierLabel(tc.tier)}</option>
              ))}
            </select>
          </Field>

          {/* Org section (PRO / ENTERPRISE) */}
          {needsOrg && (
            <div className="space-y-3 pt-1 border-t border-[var(--border-default)]">
              <p className="text-xs text-muted">Organisation</p>

              {/* Toggle existing / new */}
              <div className="flex gap-2">
                {(['existing', 'new'] as const).map((m) => (
                  <button
                    key={m}
                    type="button"
                    onClick={() => setOrgMode(m)}
                    className={cn(
                      'flex-1 py-1.5 rounded-sm text-xs font-medium border transition-colors',
                      orgMode === m
                        ? 'bg-accent-blue/15 border-accent-blue/40 text-accent-blue'
                        : 'bg-[var(--gray-200)] border-[var(--border-default)] text-secondary hover:text-primary',
                    )}
                  >
                    {m === 'existing' ? 'Existing org' : 'Create new org'}
                  </button>
                ))}
              </div>

              {orgMode === 'existing' ? (
                <Field label="Select org">
                  <select value={orgId} onChange={(e) => setOrgId(e.target.value)} className={selectCls}>
                    <option value="">— select —</option>
                    {orgs.filter(o => o.status === 'ACTIVE').map(o => (
                      <option key={o.id} value={o.id}>{o.name}</option>
                    ))}
                  </select>
                </Field>
              ) : (
                <Field label="New org name" required>
                  <Input value={newOrgName} onChange={setNewOrgName} placeholder="Acme Corp" required />
                </Field>
              )}

              <Field label="Role in org">
                <select value={orgRole} onChange={(e) => setOrgRole(e.target.value)} className={selectCls}>
                  <option value="DEVELOPER">Developer</option>
                  <option value="ORG_ADMIN">Org Admin</option>
                  <option value="BILLING_ADMIN">Billing Admin</option>
                </select>
              </Field>
            </div>
          )}

          <button
            type="submit"
            disabled={submitting}
            className={cn(
              'w-full py-2.5 rounded-sm font-medium text-sm text-primary transition-all',
              'bg-accent-blue hover:bg-accent-blue/80 disabled:opacity-40 disabled:cursor-not-allowed',
              'flex items-center justify-center gap-2',
            )}
          >
            {submitting
              ? <span className="h-4 w-4 rounded-full border-2 border-white/30 border-t-white animate-spin" />
              : <UserPlus className="h-4 w-4" />
            }
            {submitting ? 'Sending…' : 'Send Invite'}
          </button>
        </form>
      </div>
    </div>
  );
}

// ── tiny helpers ─────────────────────────────────────────────────────────────
const selectCls = 'w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-secondary focus:outline-none focus:border-accent-blue/40';

function Field({ label, required, children }: { label: string; required?: boolean; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-xs text-secondary mb-1">{label}{required && <span className="text-accent-red ml-0.5">*</span>}</label>
      {children}
    </div>
  );
}

function Input({ type = 'text', value, onChange, placeholder, required }: {
  type?: string; value: string; onChange: (v: string) => void; placeholder?: string; required?: boolean;
}) {
  return (
    <input
      type={type}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      required={required}
      className="w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue/40"
    />
  );
}
