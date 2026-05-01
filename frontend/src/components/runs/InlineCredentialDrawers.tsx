/**
 * M-008 (Wave 2) — inline drawers for the two NewRunModal bounces:
 * "Add GitHub PAT" and "Add API keys". Drawer pattern keeps the
 * parent NewRunModal's typed-but-unsubmitted state alive (per
 * Drawer.tsx javadoc) instead of bouncing the user to /settings.
 *
 * Per parked decision #8: NewRunModal "Add API key" + "Add GitHub
 * PAT" are the most-trafficked bounce sites per the v1 UX
 * assessment. These two surfaces close that gap.
 */

import {useState} from 'react';
import {Drawer} from '@/components/shared/Drawer';
import * as api from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';

// ── Add GitHub PAT ──────────────────────────────────────────────────────────

export function AddGitHubPatDrawer({open, onClose}: {open: boolean; onClose: () => void}) {
  const {setUser} = useAuthStore();
  const [pat, setPat] = useState('');
  const [owner, setOwner] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ok: boolean; text: string} | null>(null);
  const [testing, setTesting] = useState(false);

  const reset = () => {
    setPat('');
    setOwner('');
    setMsg(null);
  };

  const handleTest = async () => {
    if (!pat) return;
    setTesting(true);
    setMsg(null);
    try {
      const result = await api.validateGitHub(pat);
      setMsg({ok: result.valid, text: result.message});
    } catch (e: any) {
      setMsg({ok: false, text: e?.message ?? 'Validation failed'});
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    try {
      const updated = await api.updateGithubSettings(pat, owner);
      setUser(updated);
      reset();
      onClose();
    } catch (e: any) {
      setMsg({ok: false, text: e?.message ?? 'Save failed'});
    } finally {
      setBusy(false);
    }
  };

  return (
    <Drawer
      open={open}
      onClose={() => {
        reset();
        onClose();
      }}
      title='Add GitHub PAT'
      description='Used to clone repos and push branches. Stays on your account.'
    >
      <form onSubmit={handleSave} className='space-y-3'>
        <div>
          <label className='block text-xs text-muted mb-1'>Personal access token</label>
          <input
            type='password'
            value={pat}
            onChange={(e) => setPat(e.target.value)}
            placeholder='ghp_…'
            required
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-sm text-primary focus:border-accent-blue focus:outline-none font-mono'
          />
          <p className='text-[11px] text-muted mt-1'>
            Needs <code>repo</code> scope. Create at{' '}
            <a
              href='https://github.com/settings/tokens/new?scopes=repo&description=ExecuteSpec'
              target='_blank'
              rel='noopener noreferrer'
              className='text-accent-blue underline'
            >
              github.com/settings/tokens
            </a>
            .
          </p>
        </div>
        <div>
          <label className='block text-xs text-muted mb-1'>Default GitHub owner (optional)</label>
          <input
            type='text'
            value={owner}
            onChange={(e) => setOwner(e.target.value)}
            placeholder='your-username-or-org'
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-sm text-primary focus:border-accent-blue focus:outline-none'
          />
        </div>

        {msg && (
          <div
            className={`p-2 text-xs rounded-sm border ${
              msg.ok
                ? 'bg-status-success/10 border-status-success/30 text-status-success'
                : 'bg-status-error/10 border-status-error/30 text-status-error'
            }`}
          >
            {msg.text}
          </div>
        )}

        <div className='flex items-center gap-2 pt-1'>
          <button
            type='button'
            onClick={handleTest}
            disabled={!pat || testing}
            className='rounded-sm border border-[var(--border-default)] px-3 py-1.5 text-xs text-muted hover:text-primary disabled:opacity-50'
          >
            {testing ? 'Testing…' : 'Test'}
          </button>
          <button
            type='submit'
            disabled={!pat || busy}
            className='rounded-sm bg-accent-blue px-3 py-1.5 text-xs font-medium text-primary hover:bg-accent-blue/80 disabled:opacity-50'
          >
            {busy ? 'Saving…' : 'Save & continue'}
          </button>
        </div>
      </form>
    </Drawer>
  );
}

// ── Add API keys (BYOK) ─────────────────────────────────────────────────────

export function AddApiKeysDrawer({open, onClose}: {open: boolean; onClose: () => void}) {
  const {setUser} = useAuthStore();
  const [openAi, setOpenAi] = useState('');
  const [anthropic, setAnthropic] = useState('');
  const [gemini, setGemini] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ok: boolean; text: string} | null>(null);

  const reset = () => {
    setOpenAi('');
    setAnthropic('');
    setGemini('');
    setMsg(null);
  };

  const hasInput = openAi || anthropic || gemini;

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    try {
      const updated = await api.updateAiKeySettings(openAi, anthropic, gemini);
      setUser(updated);
      reset();
      onClose();
    } catch (e: any) {
      setMsg({ok: false, text: e?.message ?? 'Save failed'});
    } finally {
      setBusy(false);
    }
  };

  return (
    <Drawer
      open={open}
      onClose={() => {
        reset();
        onClose();
      }}
      title='Add API keys'
      description='BYOK — only the providers you fill in will be enabled. Keys stay on your account.'
    >
      <form onSubmit={handleSave} className='space-y-3'>
        <div>
          <label className='block text-xs text-muted mb-1'>Anthropic API key</label>
          <input
            type='password'
            value={anthropic}
            onChange={(e) => setAnthropic(e.target.value)}
            placeholder='sk-ant-…'
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-sm text-primary focus:border-accent-blue focus:outline-none font-mono'
          />
        </div>
        <div>
          <label className='block text-xs text-muted mb-1'>OpenAI API key</label>
          <input
            type='password'
            value={openAi}
            onChange={(e) => setOpenAi(e.target.value)}
            placeholder='sk-…'
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-sm text-primary focus:border-accent-blue focus:outline-none font-mono'
          />
        </div>
        <div>
          <label className='block text-xs text-muted mb-1'>Gemini API key</label>
          <input
            type='password'
            value={gemini}
            onChange={(e) => setGemini(e.target.value)}
            placeholder='AIza…'
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-sm text-primary focus:border-accent-blue focus:outline-none font-mono'
          />
        </div>

        {msg && (
          <div
            className={`p-2 text-xs rounded-sm border ${
              msg.ok
                ? 'bg-status-success/10 border-status-success/30 text-status-success'
                : 'bg-status-error/10 border-status-error/30 text-status-error'
            }`}
          >
            {msg.text}
          </div>
        )}

        <button
          type='submit'
          disabled={!hasInput || busy}
          className='rounded-sm bg-accent-blue px-3 py-1.5 text-xs font-medium text-primary hover:bg-accent-blue/80 disabled:opacity-50'
        >
          {busy ? 'Saving…' : 'Save & continue'}
        </button>
      </form>
    </Drawer>
  );
}
