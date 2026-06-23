import {type FormEvent, useState} from 'react';
import {Link, useNavigate, useSearchParams} from 'react-router-dom';

import * as api from '@/lib/api';
import AuthLayout from '@/signal/components/AuthLayout';
import {Button} from '@/signal/components/Primitives';

const MERGE_RAIL_MARK = '/assets/brand/merge-rail/executespec-merge-rail-mark-light.svg';

function RecoveryFormError({msg}: {msg: string}) {
  if (!msg) return null;
  return (
    <div
      style={{
        background: 'var(--signal-coral-soft)',
        color: 'var(--signal-coral-text)',
        border: '1px solid var(--signal-coral)',
        borderRadius: 10,
        padding: '10px 14px',
        marginBottom: 12,
        fontSize: 13,
      }}
    >
      {msg}
    </div>
  );
}

function RecoveryFormSuccess({msg}: {msg: string}) {
  if (!msg) return null;
  return (
    <div
      style={{
        background: 'var(--signal-mint-soft)',
        color: 'var(--signal-mint-text-on-light)',
        border: '1px solid var(--signal-mint)',
        borderRadius: 10,
        padding: '10px 14px',
        marginBottom: 12,
        fontSize: 13,
      }}
    >
      {msg}
    </div>
  );
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [ok, setOk] = useState('');
  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setErr('');
    setOk('');
    setBusy(true);
    try {
      await api.authForgotPassword(email);
      setOk('Reset link sent. Check your inbox (and spam folder).');
      setEmail('');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to send the reset link.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <AuthLayout>
      <div className='signal-splash'>
        <div className='brand'>
          <img className='logo' src={MERGE_RAIL_MARK} alt='' />
          <div style={{fontSize: 16, fontWeight: 600}}>ExecuteSpec</div>
        </div>
        <h1>Reset password.</h1>
        <p className='lede'>Enter your email. We will send a one-hour reset link.</p>
        <RecoveryFormError msg={err} />
        <RecoveryFormSuccess msg={ok} />
        <form onSubmit={onSubmit}>
          <div className='signal-field'>
            <label>Email</label>
            <input
              type='email'
              autoComplete='email'
              placeholder='you@company.com'
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          <Button kind='primary' block type='submit' disabled={busy}>
            {busy ? 'Sending…' : 'Send reset link'}
          </Button>
        </form>
        <div className='footer-link'>
          Remembered it? <Link to='/login'>Sign in</Link>
        </div>
      </div>
    </AuthLayout>
  );
}

export function ResetPasswordPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token') || '';
  const [pw, setPw] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setErr('');
    if (!token) {
      setErr('No reset token in the URL. Click the link in your email again.');
      return;
    }
    if (pw !== confirm) {
      setErr("Passwords don't match.");
      return;
    }
    if (pw.length < 12) {
      setErr('Password must be at least 12 characters.');
      return;
    }
    setBusy(true);
    try {
      await api.authResetPassword(token, pw);
      navigate('/login?reset=1', {replace: true});
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Reset failed. Request a fresh link.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <AuthLayout>
      <div className='signal-splash'>
        <div className='brand'>
          <img className='logo' src={MERGE_RAIL_MARK} alt='' />
          <div style={{fontSize: 16, fontWeight: 600}}>ExecuteSpec</div>
        </div>
        <h1>New password.</h1>
        <p className='lede'>Use at least 12 characters. Keep it distinct from other tools.</p>
        <RecoveryFormError msg={err} />
        <form onSubmit={onSubmit}>
          <div className='signal-field'>
            <label>New password</label>
            <input
              type='password'
              autoComplete='new-password'
              placeholder='••••••••'
              value={pw}
              onChange={(e) => setPw(e.target.value)}
              minLength={12}
              required
            />
          </div>
          <div className='signal-field'>
            <label>Confirm</label>
            <input
              type='password'
              autoComplete='new-password'
              placeholder='••••••••'
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              required
            />
          </div>
          <Button kind='primary' block type='submit' disabled={busy}>
            {busy ? 'Resetting…' : 'Reset password'}
          </Button>
        </form>
      </div>
    </AuthLayout>
  );
}
