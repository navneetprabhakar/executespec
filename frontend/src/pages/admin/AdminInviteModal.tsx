import {useEffect, useState} from 'react';
import {UserPlus} from 'lucide-react';
import {cn, errMsg} from '@/lib/utils';
import * as api from '@/lib/api';
import {useUiStore} from '@/stores/useUiStore';
import ResponsiveModal from '@/components/shared/ResponsiveModal';
import Button from '@/components/shared/Button';

interface Props {
  onClose: () => void;
  onSuccess: () => void;
}

const TIER_LABEL_FALLBACK: Record<string, string> = {
  FREE: 'Free',
  PRO: 'Pro',
  ENTERPRISE: 'Enterprise',
};

export default function AdminInviteModal({onClose, onSuccess}: Props) {
  const {addToast} = useUiStore();

  const [email, setEmail] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [tier, setTier] = useState('FREE');
  const [orgMode, setOrgMode] = useState<'existing' | 'new'>('existing');
  const [orgId, setOrgId] = useState('');
  const [newOrgName, setNewOrgName] = useState('');
  const [orgRole, setOrgRole] = useState('DEVELOPER');
  const [orgs, setOrgs] = useState<api.OrgDto[]>([]);
  const [tierConfigs, setTierConfigs] = useState<api.TierConfig[]>([]);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    api
      .adminListOrgs()
      .then(setOrgs)
      .catch(() => {});
    api
      .adminGetTierConfig()
      .then(setTierConfigs)
      .catch(() => {});
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
        email,
        firstName,
        lastName,
        tier,
        ...(needsOrg && orgMode === 'existing' && orgId ? {orgId} : {}),
        ...(needsOrg && orgMode === 'new' && newOrgName ? {newOrgName} : {}),
        ...(needsOrg ? {orgRole} : {}),
      };
      await api.adminInviteUser(payload);
      addToast({type: 'success', message: `Invite sent to ${email}`});
      onSuccess();
      onClose();
    } catch (err) {
      addToast({type: 'error', message: errMsg(err)});
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ResponsiveModal open={true} onClose={onClose} title='Invite User' maxWidth='max-w-md'>
      <form onSubmit={handleSubmit} className='space-y-4'>
        <div className='grid grid-cols-2 gap-3'>
          <Field label='First Name' required>
            <Input value={firstName} onChange={setFirstName} placeholder='Jane' required />
          </Field>
          <Field label='Last Name' required>
            <Input value={lastName} onChange={setLastName} placeholder='Doe' required />
          </Field>
        </div>

        <Field label='Email' required>
          <Input
            type='email'
            value={email}
            onChange={setEmail}
            placeholder='jane@example.com'
            required
          />
        </Field>

        <Field label='Tier'>
          <select
            value={tier}
            onChange={(e) => {
              setTier(e.target.value);
              setOrgId('');
              setNewOrgName('');
            }}
            className={selectCls}
          >
            {(tierConfigs.length > 0
              ? tierConfigs
              : [{tier: 'FREE'}, {tier: 'PRO'}, {tier: 'ENTERPRISE'}]
            ).map((tc) => (
              <option key={tc.tier} value={tc.tier}>
                {tierLabel(tc.tier)}
              </option>
            ))}
          </select>
        </Field>

        {needsOrg && (
          <div className='space-y-3 pt-1 border-t border-[var(--border-default)]'>
            <p className='text-xs text-muted'>Organisation</p>

            <div className='flex gap-2'>
              {(['existing', 'new'] as const).map((m) => (
                <Button
                  key={m}
                  type='button'
                  size='sm'
                  variant={orgMode === m ? 'primary' : 'secondary'}
                  onClick={() => setOrgMode(m)}
                  className='flex-1'
                >
                  {m === 'existing' ? 'Existing org' : 'Create new org'}
                </Button>
              ))}
            </div>

            {orgMode === 'existing' ? (
              <Field label='Select org'>
                <select
                  value={orgId}
                  onChange={(e) => setOrgId(e.target.value)}
                  className={selectCls}
                >
                  <option value=''>— select —</option>
                  {orgs
                    .filter((o) => o.status === 'ACTIVE')
                    .map((o) => (
                      <option key={o.id} value={o.id}>
                        {o.name}
                      </option>
                    ))}
                </select>
              </Field>
            ) : (
              <Field label='New org name' required>
                <Input
                  value={newOrgName}
                  onChange={setNewOrgName}
                  placeholder='Acme Corp'
                  required
                />
              </Field>
            )}

            <Field label='Role in org'>
              <select
                value={orgRole}
                onChange={(e) => setOrgRole(e.target.value)}
                className={selectCls}
              >
                <option value='DEVELOPER'>Developer</option>
                <option value='ORG_ADMIN'>Org Admin</option>
                <option value='BILLING_ADMIN'>Billing Admin</option>
              </select>
            </Field>
          </div>
        )}

        <Button
          type='submit'
          variant='primary'
          size='lg'
          fullWidth
          loading={submitting}
          disabled={submitting}
        >
          <UserPlus className='h-4 w-4' />
          {submitting ? 'Sending…' : 'Send Invite'}
        </Button>
      </form>
    </ResponsiveModal>
  );
}

const selectCls =
  'w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-secondary focus:outline-none focus:border-accent-blue/40';

function Field({
  label,
  required,
  children,
}: {
  label: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className='block text-xs text-secondary mb-1'>
        {label}
        {required && <span className='text-accent-red ml-0.5'>*</span>}
      </label>
      {children}
    </div>
  );
}

function Input({
  type = 'text',
  value,
  onChange,
  placeholder,
  required,
}: {
  type?: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  required?: boolean;
}) {
  return (
    <input
      type={type}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      required={required}
      className={cn(
        'w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue/40',
      )}
    />
  );
}
