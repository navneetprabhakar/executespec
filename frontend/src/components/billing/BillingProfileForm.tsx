import { useEffect, useState } from 'react';
import { Save } from 'lucide-react';
import { getBillingProfile, updateBillingProfile, type BillingProfile } from '@/lib/invoice-api';

const INDIAN_STATES = [
  { code: '01', name: 'Jammu & Kashmir' }, { code: '02', name: 'Himachal Pradesh' },
  { code: '03', name: 'Punjab' }, { code: '04', name: 'Chandigarh' },
  { code: '05', name: 'Uttarakhand' }, { code: '06', name: 'Haryana' },
  { code: '07', name: 'Delhi' }, { code: '08', name: 'Rajasthan' },
  { code: '09', name: 'Uttar Pradesh' }, { code: '10', name: 'Bihar' },
  { code: '11', name: 'Sikkim' }, { code: '12', name: 'Arunachal Pradesh' },
  { code: '13', name: 'Nagaland' }, { code: '14', name: 'Manipur' },
  { code: '15', name: 'Mizoram' }, { code: '16', name: 'Tripura' },
  { code: '17', name: 'Meghalaya' }, { code: '18', name: 'Assam' },
  { code: '19', name: 'West Bengal' }, { code: '20', name: 'Jharkhand' },
  { code: '21', name: 'Odisha' }, { code: '22', name: 'Chhattisgarh' },
  { code: '23', name: 'Madhya Pradesh' }, { code: '24', name: 'Gujarat' },
  { code: '26', name: 'Dadra & Nagar Haveli and Daman & Diu' },
  { code: '27', name: 'Maharashtra' }, { code: '28', name: 'Andhra Pradesh (old)' },
  { code: '29', name: 'Karnataka' }, { code: '30', name: 'Goa' },
  { code: '31', name: 'Lakshadweep' }, { code: '32', name: 'Kerala' },
  { code: '33', name: 'Tamil Nadu' }, { code: '34', name: 'Puducherry' },
  { code: '35', name: 'Andaman & Nicobar' }, { code: '36', name: 'Telangana' },
  { code: '37', name: 'Andhra Pradesh' }, { code: '38', name: 'Ladakh' },
];

export default function BillingProfileForm() {
  const [profile, setProfile] = useState<BillingProfile | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    getBillingProfile().then(setProfile).catch(() => {});
  }, []);

  const handleSave = async () => {
    if (!profile) return;
    setSaving(true);
    setSaved(false);
    try {
      const updated = await updateBillingProfile(profile);
      setProfile(updated);
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } catch {
      // handled
    } finally {
      setSaving(false);
    }
  };

  const handleStateChange = (code: string) => {
    const state = INDIAN_STATES.find(s => s.code === code);
    setProfile(p => p ? { ...p, stateCode: code, stateName: state?.name || '' } : p);
  };

  if (!profile) return null;

  const inputClasses =
    'w-full rounded-sm border border-white/[0.06] bg-white/[0.03] px-3 py-2 text-sm text-zinc-200 ' +
    'placeholder:text-zinc-600 focus:border-accent-violet/50 focus:outline-none focus:ring-1 focus:ring-accent-violet/20 transition-colors';

  const labelClasses = 'mb-1 block text-xs font-medium text-zinc-400';

  const field = (label: string, key: keyof BillingProfile, placeholder?: string) => (
    <div>
      <label className={labelClasses}>{label}</label>
      <input
        value={(profile[key] as string) || ''}
        onChange={(e) => setProfile({ ...profile, [key]: e.target.value })}
        placeholder={placeholder}
        className={inputClasses}
      />
    </div>
  );

  return (
    <div className="rounded-sm bg-surface-1 border border-white/[0.06] p-6 mt-6">
      <div className="mb-5 flex items-center justify-between">
        <div>
          <h3 className="text-base font-semibold text-zinc-100">Billing Details</h3>
          <p className="text-xs text-zinc-500 mt-0.5">GST and invoicing information</p>
        </div>
        <button
          onClick={handleSave}
          disabled={saving}
          className="flex items-center gap-1.5 rounded-sm bg-accent-violet px-3.5 py-1.5 text-xs font-semibold
                     text-white hover:bg-accent-violet/80 disabled:opacity-50 transition-colors"
        >
          <Save className="h-3.5 w-3.5" />
          {saving ? 'Saving...' : saved ? 'Saved!' : 'Save'}
        </button>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {field('Legal Name', 'legalName', 'Business / Individual name')}
        {field('GSTIN', 'gstin', '22AAAAA0000A1Z5')}
        {field('PAN', 'pan', 'AAAAA0000A')}
        {field('Address Line 1', 'addressLine1')}
        {field('Address Line 2', 'addressLine2')}
        {field('City', 'city')}

        <div>
          <label className={labelClasses}>State</label>
          <select
            value={profile.stateCode || ''}
            onChange={(e) => handleStateChange(e.target.value)}
            className={inputClasses}
          >
            <option value="">Select state</option>
            {INDIAN_STATES.map(s => (
              <option key={s.code} value={s.code}>{s.name} ({s.code})</option>
            ))}
          </select>
        </div>

        {field('Postal Code', 'postalCode')}

        <div>
          <label className={labelClasses}>Country</label>
          <select
            value={profile.country || 'IN'}
            onChange={(e) => setProfile({ ...profile, country: e.target.value })}
            className={inputClasses}
          >
            <option value="IN">India</option>
            <option value="US">United States</option>
            <option value="GB">United Kingdom</option>
            <option value="SG">Singapore</option>
          </select>
        </div>

        <div>
          <label className={labelClasses}>Preferred Currency</label>
          <select
            value={profile.preferredCurrency || 'INR'}
            onChange={(e) => setProfile({ ...profile, preferredCurrency: e.target.value })}
            className={inputClasses}
          >
            <option value="INR">INR (Indian Rupee)</option>
            <option value="USD">USD (US Dollar)</option>
          </select>
        </div>
      </div>
    </div>
  );
}
