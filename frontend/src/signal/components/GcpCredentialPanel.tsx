import {useEffect, useState} from 'react';
import {Button} from '@/signal/components/Primitives';
import {ConfirmModal} from '@/signal/components/Modal';
import * as api from '@/lib/api';

/**
 * GCP deploy-credential management — connect / validate / disconnect.
 * Surfaces the dark `/credentials/gcp` endpoints (required for permanent
 * Cloud Run deploys).
 *
 * Security: the user pastes their OWN service-account JSON (write-only,
 * BYOK-style). We never echo the raw key back — `CredentialSummary` only
 * carries the SA email + GCP project, so the secret is never re-displayed.
 *
 * Shared by the Signal DeploymentsPage + the IDE deployments surface;
 * `variant` switches styling so each chrome stays native.
 */
export function GcpCredentialPanel({variant}: {variant: 'signal' | 'ide'}) {
  const [cred, setCred] = useState<api.CredentialSummary | null | undefined>(undefined);
  const [json, setJson] = useState('');
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState('');
  const [msg, setMsg] = useState('');
  const [validation, setValidation] = useState<api.ValidationResult | null>(null);
  const [confirmDisconnect, setConfirmDisconnect] = useState(false);

  function load() {
    api
      .getGcpCredential()
      // `undefined` is the loading sentinel; the endpoint returns an empty body
      // (→ undefined) when no credential is set, so coalesce to null = "none".
      .then((c) => setCred(c ?? null))
      .catch(() => setCred(null));
  }
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function connect() {
    if (!json.trim()) return;
    setBusy('save');
    setMsg('');
    try {
      await api.storeGcpCredential(json.trim());
      setJson('');
      setEditing(false);
      setMsg('Credential saved.');
      load();
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setBusy('');
    }
  }
  async function validate() {
    setBusy('val');
    setMsg('');
    setValidation(null);
    try {
      setValidation(await api.validateGcpCredential());
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Validation failed.');
    } finally {
      setBusy('');
    }
  }
  async function doDisconnect() {
    setBusy('del');
    setMsg('');
    try {
      await api.deleteGcpCredential();
      setCred(null);
      setValidation(null);
      setMsg('Disconnected.');
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Disconnect failed.');
    } finally {
      setBusy('');
    }
  }

  const ide = variant === 'ide';
  const cardCls = ide ? 'ide-card' : undefined;
  const cardStyle = ide
    ? {marginBottom: 12}
    : {
        border: '1px solid var(--signal-hairline)',
        borderRadius: 12,
        padding: 14,
        marginBottom: 12,
        background: 'var(--signal-surface)',
      };
  const muted = ide ? 'var(--ide-fg-muted)' : 'var(--signal-text-dim)';
  const heading = ide ? 'var(--ide-fg-bright)' : 'var(--signal-ink)';

  return (
    <div className={cardCls} style={cardStyle}>
      <div style={{fontWeight: 600, color: heading, marginBottom: 4}}>Cloud deploy credential</div>
      <div style={{fontSize: 12.5, color: muted, marginBottom: 10}}>
        A GCP service-account JSON is required to deploy permanent Cloud Run services.
      </div>

      {msg && (
        <div
          style={{
            fontSize: 12.5,
            color: ide ? 'var(--ide-blue)' : 'var(--signal-coral)',
            marginBottom: 8,
          }}
        >
          {msg}
        </div>
      )}

      {cred === undefined ? (
        <div style={{fontSize: 13, color: muted}}>Loading…</div>
      ) : cred ? (
        <>
          <div style={{fontSize: 13, marginBottom: 8}}>
            <div>
              <strong style={{color: heading}}>{cred.serviceAccountEmail}</strong>
            </div>
            <div style={{color: muted, fontSize: 12}}>
              project {cred.gcpProjectId || '—'} ·{' '}
              {cred.validatedAt
                ? `validated ${new Date(cred.validatedAt).toLocaleDateString()}`
                : 'not validated'}
            </div>
          </div>
          {validation && (
            <div
              style={{
                fontSize: 12.5,
                marginBottom: 8,
                color: validation.valid
                  ? ide
                    ? 'var(--ide-green)'
                    : 'var(--signal-mint-text)'
                  : ide
                    ? 'var(--ide-red)'
                    : 'var(--signal-coral)',
              }}
            >
              {validation.valid ? '✓ Credential valid' : '✗ Issues found'}
              {validation.issues?.length > 0 && (
                <ul style={{margin: '4px 0 0', paddingLeft: 18}}>
                  {validation.issues.map((iss, i) => (
                    <li key={i}>{iss}</li>
                  ))}
                </ul>
              )}
            </div>
          )}
          {ide ? (
            <div style={{display: 'flex', gap: 6}}>
              <button
                className='ide-btn ghost'
                style={{padding: '4px 10px'}}
                disabled={busy === 'val'}
                onClick={validate}
              >
                {busy === 'val' ? 'Validating…' : 'Validate'}
              </button>
              <button
                className='ide-btn ghost'
                style={{padding: '4px 10px', color: 'var(--ide-red)'}}
                disabled={busy === 'del'}
                onClick={() => setConfirmDisconnect(true)}
              >
                Disconnect
              </button>
            </div>
          ) : (
            <div style={{display: 'flex', gap: 6}}>
              <Button kind='secondary' sm onClick={validate} disabled={busy === 'val'}>
                {busy === 'val' ? 'Validating…' : 'Validate'}
              </Button>
              <Button
                kind='ghost'
                sm
                onClick={() => setConfirmDisconnect(true)}
                disabled={busy === 'del'}
              >
                Disconnect
              </Button>
            </div>
          )}
        </>
      ) : editing ? (
        <>
          <textarea
            value={json}
            onChange={(e) => setJson(e.target.value)}
            rows={6}
            placeholder='Paste your GCP service-account JSON key here…'
            spellCheck={false}
            style={{
              width: '100%',
              boxSizing: 'border-box',
              borderRadius: 8,
              border: `1px solid ${ide ? 'var(--ide-border)' : 'var(--signal-hairline)'}`,
              background: ide ? 'var(--ide-bg-input)' : 'var(--signal-bg)',
              color: ide ? 'var(--ide-fg)' : 'var(--signal-ink)',
              padding: 10,
              fontSize: 12,
              fontFamily: "'JetBrains Mono', ui-monospace, monospace",
              resize: 'vertical',
            }}
          />
          <div style={{display: 'flex', gap: 6, marginTop: 6}}>
            {ide ? (
              <>
                <button
                  className='ide-btn'
                  disabled={busy === 'save' || !json.trim()}
                  onClick={connect}
                >
                  {busy === 'save' ? 'Saving…' : 'Save credential'}
                </button>
                <button className='ide-btn ghost' onClick={() => setEditing(false)}>
                  Cancel
                </button>
              </>
            ) : (
              <>
                <Button
                  kind='secondary'
                  sm
                  onClick={connect}
                  disabled={busy === 'save' || !json.trim()}
                >
                  {busy === 'save' ? 'Saving…' : 'Save credential'}
                </Button>
                <Button kind='ghost' sm onClick={() => setEditing(false)}>
                  Cancel
                </Button>
              </>
            )}
          </div>
        </>
      ) : (
        <>
          <div style={{fontSize: 13, color: muted, marginBottom: 8}}>No credential connected.</div>
          {ide ? (
            <button className='ide-btn' onClick={() => setEditing(true)}>
              Connect GCP
            </button>
          ) : (
            <Button kind='secondary' sm onClick={() => setEditing(true)}>
              Connect GCP
            </Button>
          )}
        </>
      )}

      <ConfirmModal
        open={confirmDisconnect}
        title='Disconnect GCP credential'
        body='Disconnect the GCP deploy credential?'
        confirmLabel='Disconnect'
        danger
        busy={busy === 'del'}
        onConfirm={doDisconnect}
        onClose={() => setConfirmDisconnect(false)}
      />
    </div>
  );
}
