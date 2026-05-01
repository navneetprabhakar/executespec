import {useState} from 'react';
import {useAuthStore} from '@/stores/useAuthStore';
import * as api from '@/lib/api';
import ResponsiveModal from '@/components/shared/ResponsiveModal';
import Button from '@/components/shared/Button';

export default function ChangePasswordModal() {
  const {user, loadMe} = useAuthStore();
  const [current, setCurrent] = useState('');
  const [newPwd, setNewPwd] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  if (!user?.mustChangePassword) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (newPwd !== confirm) {
      setError('Passwords do not match');
      return;
    }
    if (newPwd.length < 10) {
      setError('Password must be at least 10 characters');
      return;
    }
    if (!/[A-Z]/.test(newPwd)) {
      setError('Password must contain at least one uppercase letter');
      return;
    }
    if (!/[a-z]/.test(newPwd)) {
      setError('Password must contain at least one lowercase letter');
      return;
    }
    if (!/[0-9]/.test(newPwd)) {
      setError('Password must contain at least one digit');
      return;
    }
    if (!/[!@#$%^&*()_+\-=\[\]{};':",./<>?]/.test(newPwd)) {
      setError('Password must contain at least one special character');
      return;
    }
    if (/\s/.test(newPwd)) {
      setError('Password must not contain whitespace');
      return;
    }
    setLoading(true);
    try {
      await api.authChangePassword(user.mustChangePassword ? null : current, newPwd);
      await loadMe();
    } catch (err: any) {
      setError(err.message ?? 'Failed to change password');
    } finally {
      setLoading(false);
    }
  };

  return (
    <ResponsiveModal
      open={true}
      onClose={() => {}}
      title='Change your password'
      maxWidth='max-w-md'
      hideCloseButton
      disableDismiss
    >
      <p className='mb-4 text-sm text-muted'>
        Your account requires a password change before you can continue.
      </p>
      <form onSubmit={handleSubmit} className='space-y-4'>
        {!user.mustChangePassword && (
          <div>
            <label className='mb-1 block text-sm text-muted'>Current password</label>
            <input
              type='password'
              value={current}
              onChange={(e) => setCurrent(e.target.value)}
              className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none'
            />
          </div>
        )}
        <div>
          <label className='mb-1 block text-sm text-muted'>New password</label>
          <input
            type='password'
            value={newPwd}
            onChange={(e) => setNewPwd(e.target.value)}
            required
            minLength={10}
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none'
          />
          <p className='mt-1 text-xs text-muted'>
            Min 10 chars, uppercase, lowercase, digit, special character, no spaces
          </p>
        </div>
        <div>
          <label className='mb-1 block text-sm text-muted'>Confirm new password</label>
          <input
            type='password'
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            required
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none'
          />
        </div>
        {error && <p className='text-sm text-accent-red'>{error}</p>}
        <Button
          type='submit'
          variant='primary'
          size='lg'
          fullWidth
          loading={loading}
          disabled={loading}
        >
          {loading ? 'Saving…' : 'Set new password'}
        </Button>
      </form>
    </ResponsiveModal>
  );
}
