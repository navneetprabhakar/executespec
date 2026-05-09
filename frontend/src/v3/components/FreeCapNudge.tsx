import {useEffect, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {X} from 'lucide-react';
import {getV3Entitlements, sendV3Referral, type V3Entitlements} from '../lib/api';
import {useV3Ui} from '../stores/useV3Ui';
import {trackV3, V3_EVENTS} from '../lib/events';
import {useModalA11y} from '@/hooks/useModalA11y';

/**
 * R-163 — free-cap upgrade-or-invite nudge.
 *
 * Defaults applied per D-163 (greenfield §7.6):
 *   - Toast at lifetime balance ≤ 1 (one-time per page-mount).
 *   - Blocking modal at balance == 0 BEFORE the next composer submit.
 *
 * Today (Wave 2) ships the toast + modal shapes against the live
 * EntitlementResolver (R-142). The composer hook that *blocks* the
 * Submit button when balance is 0 lands inline below — Composer.tsx
 * checks the same store before firing createRun.
 *
 * Conversion event: V3_TIER_UPGRADED fires on Get-Pro click.
 */

interface _CapState {
  balance: number;
  cap: number;
  tierKey: string;
  isUnlimited: boolean;
}

export function useFreeCap() {
  const setCap = useV3Ui((s) => s.setCap);
  const cap = useV3Ui((s) => s.cap);

  useEffect(() => {
    let cancelled = false;
    getV3Entitlements()
      .then((e: V3Entitlements) => {
        if (cancelled) return;
        setCap({
          balance: e.lifetimeRunCreditBalance,
          cap: e.lifetimeRunCreditCap,
          tierKey: e.tierKey,
          isUnlimited: e.isUnlimited,
        });
      })
      .catch(() => {
        // On error, leave cap untouched — never block a submit on a
        // network blip.
      });
    return () => {
      cancelled = true;
    };
  }, [setCap]);

  return cap;
}

// ─── Toast (balance ≤ 1, free tier only) ────────────────────────────────────

export function FreeCapToast() {
  const cap = useFreeCap();
  const dismissed = useV3Ui((s) => s.capToastDismissed);
  const dismiss = useV3Ui((s) => s.dismissCapToast);
  const navigate = useNavigate();

  if (!cap || cap.tierKey !== 'FREE' || cap.isUnlimited) return null;
  if (cap.balance > 1) return null;
  if (dismissed) return null;
  if (cap.balance < 0) return null;

  const message =
    cap.balance === 1
      ? '1 free run left — invite a friend for +5 or upgrade to Pro for unlimited.'
      : "You're out of free runs — upgrade to Pro or invite a friend for +5.";

  return (
    <div
      role='status'
      style={{
        position: 'fixed',
        bottom: 24,
        left: '50%',
        transform: 'translateX(-50%)',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '10px 14px',
        background: 'var(--v3-surface-elevated)',
        border: '1px solid var(--v3-warning)',
        borderRadius: 8,
        maxWidth: 'min(90vw, 480px)',
        fontSize: 12,
        color: 'var(--v3-text)',
        zIndex: 90,
        boxShadow: '0 12px 32px rgba(0,0,0,0.45)',
      }}
    >
      <span style={{flex: 1, lineHeight: 1.5}}>{message}</span>
      <button
        type='button'
        onClick={() => {
          trackV3(V3_EVENTS.TIER_UPGRADED, {fromTier: 'FREE', toTier: 'PRO', source: 'cap_toast'});
          navigate('/billing');
        }}
        style={{
          padding: '4px 10px',
          fontSize: 11.5,
          fontWeight: 600,
          background: 'var(--v3-brand)',
          color: 'var(--v3-text-on-brand)',
          border: 'none',
          borderRadius: 4,
          cursor: 'pointer',
          fontFamily: 'inherit',
        }}
      >
        Upgrade
      </button>
      <button
        type='button'
        onClick={dismiss}
        aria-label='Dismiss'
        style={{
          padding: '4px 6px',
          background: 'transparent',
          color: 'var(--v3-text-subtle)',
          border: 'none',
          cursor: 'pointer',
          fontSize: 14,
        }}
      >
        ×
      </button>
    </div>
  );
}

// ─── Blocking modal (balance == 0, before submit) ───────────────────────────

interface FreeCapBlockingModalProps {
  open: boolean;
  onClose: () => void;
}

export function FreeCapBlockingModal({open, onClose}: FreeCapBlockingModalProps) {
  const cap = useFreeCap();
  const navigate = useNavigate();
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteSent, setInviteSent] = useState(false);

  useEffect(() => {
    if (open) {
      setInviteEmail('');
      setInviteSent(false);
    }
  }, [open]);

  // Esc handling moved to useModalA11y above (F-01).

  // R-09 lint cleanup — useState must be called unconditionally before the
  // early return below. Pre-fix this hook sat after `if (!open || !cap)
  // return null` which violated rules-of-hooks (the hook order changed
  // once cap loaded).
  const [inviteError, setInviteError] = useState<string | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  // F-01 fix (2026-05-09) — focus trap + body scroll lock + Esc-close
  // (replaces the inline Esc handler added in F-23) + focus restore on
  // close.
  useModalA11y(open, onClose, {panelRef});

  if (!open || !cap) return null;

  function onUpgrade() {
    trackV3(V3_EVENTS.TIER_UPGRADED, {fromTier: 'FREE', toTier: 'PRO', source: 'cap_modal'});
    navigate('/billing');
    onClose();
  }

  async function onInvite(e: React.FormEvent) {
    e.preventDefault();
    if (!inviteEmail.trim()) return;
    setInviteError(null);
    try {
      const rec = await sendV3Referral(inviteEmail.trim());
      trackV3(V3_EVENTS.REFERRAL_SENT, {
        email: inviteEmail,
        status: rec.status,
      });
      setInviteSent(true);
    } catch (e) {
      setInviteError((e as Error)?.message ?? 'Invite failed');
    }
  }

  return (
    <div
      role='dialog'
      aria-modal='true'
      aria-label='Free runs exhausted'
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.6)',
        zIndex: 100,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
      }}
    >
      <div
        ref={panelRef}
        style={{
          width: 'min(440px, 92vw)',
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border-strong)',
          borderRadius: 10,
          padding: 20,
          position: 'relative',
        }}
      >
        {/* F-23 fix (2026-05-09) — top-right close affordance. The audit
            flagged this modal had no X button — only backdrop-click and
            Upgrade-CTA exits. */}
        <button
          type='button'
          onClick={onClose}
          aria-label='Close'
          style={{
            position: 'absolute',
            top: 10,
            right: 10,
            width: 28,
            height: 28,
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'transparent',
            color: 'var(--v3-text-subtle)',
            border: 'none',
            borderRadius: 4,
            cursor: 'pointer',
          }}
        >
          <X size={16} aria-hidden='true' />
        </button>
        <h2
          style={{
            margin: '0 0 6px',
            fontSize: 18,
            fontWeight: 600,
            color: 'var(--v3-text)',
          }}
        >
          That was your last free run.
        </h2>
        <p style={{margin: 0, fontSize: 12.5, color: 'var(--v3-text-muted)', lineHeight: 1.55}}>
          Get unlimited runs for $20/month — cancel any time. Or invite a friend; you both get +5
          runs.
        </p>

        <button
          type='button'
          onClick={onUpgrade}
          style={{
            width: '100%',
            marginTop: 14,
            padding: '10px 14px',
            background: 'var(--v3-brand)',
            color: 'var(--v3-text-on-brand)',
            border: 'none',
            borderRadius: 6,
            cursor: 'pointer',
            fontSize: 13,
            fontWeight: 600,
            fontFamily: 'inherit',
          }}
        >
          Upgrade to Pro — $20 / month
        </button>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            margin: '14px 0 10px',
            color: 'var(--v3-text-subtle)',
            fontSize: 10.5,
          }}
        >
          <div style={{flex: 1, height: 1, background: 'var(--v3-border)'}} />
          <span>or invite a friend</span>
          <div style={{flex: 1, height: 1, background: 'var(--v3-border)'}} />
        </div>

        {!inviteSent ? (
          <>
            <form onSubmit={onInvite} style={{display: 'flex', gap: 6}}>
              <input
                type='email'
                autoComplete='email'
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
                placeholder='friend@example.com'
                aria-label='Invite a friend by email'
                style={{
                  flex: 1,
                  padding: '8px 10px',
                  background: 'var(--v3-bg)',
                  color: 'var(--v3-text)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 6,
                  fontSize: 12.5,
                  outline: 'none',
                  fontFamily: 'inherit',
                }}
              />
              <button
                type='submit'
                disabled={!inviteEmail.trim()}
                style={{
                  padding: '8px 14px',
                  background: inviteEmail.trim()
                    ? 'var(--v3-surface-elevated)'
                    : 'var(--v3-surface)',
                  color: inviteEmail.trim() ? 'var(--v3-text)' : 'var(--v3-text-subtle)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 6,
                  cursor: inviteEmail.trim() ? 'pointer' : 'not-allowed',
                  fontSize: 12.5,
                  fontWeight: 600,
                  fontFamily: 'inherit',
                }}
              >
                Send
              </button>
            </form>
            {inviteError && (
              <p style={{margin: '6px 0 0', fontSize: 11, color: 'var(--v3-error)'}}>
                {inviteError}
              </p>
            )}
          </>
        ) : (
          <p style={{margin: 0, fontSize: 12, color: 'var(--v3-success)'}}>
            ✓ Invite sent. You'll see +5 runs once they ship their first run.
          </p>
        )}

        <p
          style={{
            margin: '14px 0 0',
            fontSize: 10.5,
            color: 'var(--v3-text-subtle)',
            textAlign: 'center',
          }}
        >
          Cap is {cap.cap} lifetime runs (5 base + 4 invites × 5).{' '}
          <button
            type='button'
            onClick={onClose}
            style={{
              background: 'transparent',
              border: 'none',
              color: 'var(--v3-text-subtle)',
              cursor: 'pointer',
              textDecoration: 'underline',
              fontSize: 10.5,
              fontFamily: 'inherit',
              padding: 0,
            }}
          >
            Close
          </button>
        </p>
      </div>
    </div>
  );
}
