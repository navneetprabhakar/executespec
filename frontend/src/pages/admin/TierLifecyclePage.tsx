import {errMsg} from '@/lib/utils';
import {useState, useEffect, useCallback} from 'react';
import {AlertTriangle, Layers} from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import RoleGuard from '@/components/auth/RoleGuard';
import {
  fetchTiersLifecycle,
  updateTierLifecycle,
  type TierLifecycleDto,
  type LifecycleState,
} from '@/lib/api';

/**
 * v2.6.0 — tier lifecycle admin page. SUPER_ADMIN only.
 *
 * Implements the 3-state state machine (LISTED / HIDDEN / SUNSETTING) with
 * an MRR-aware confirmation modal for SUNSETTING transitions. Full design
 * at docs/plans/tier-lifecycle-plan.md.
 *
 * Task 21 (2026-05-01 fix list) — wrapped with RoleGuard so the page
 * doesn't bounce to "/" (and thence to the home-redirect's first-project
 * dispatch) before the auth store hydrates. The previous gate
 * (`if (!isSuperAdmin) return <Navigate to="/" replace />`) fired on the
 * very first render when `user` was still null, treating "loading" as
 * "denied" and breaking direct deep-links to /admin/tiers.
 */
export default function TierLifecyclePage() {
  return (
    <RoleGuard requireSuperAdmin featureName='Tier lifecycle'>
      <TierLifecyclePageInner />
    </RoleGuard>
  );
}

function TierLifecyclePageInner() {
  const [tiers, setTiers] = useState<TierLifecycleDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);

  const [modalTier, setModalTier] = useState<TierLifecycleDto | null>(null);
  const [modalTargetState, setModalTargetState] = useState<LifecycleState>('HIDDEN');
  const [modalReason, setModalReason] = useState('');
  const [modalConfirm, setModalConfirm] = useState('');
  const [modalBusy, setModalBusy] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const list = await fetchTiersLifecycle();
      setTiers(list);
    } catch (e) {
      console.error('Lifecycle fetch failed', e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData, reload]);

  const openTransition = (tier: TierLifecycleDto, target: LifecycleState) => {
    setModalTier(tier);
    setModalTargetState(target);
    setModalReason('');
    setModalConfirm('');
    setModalError(null);
  };

  const closeModal = () => {
    setModalTier(null);
    setModalError(null);
  };

  const submit = async () => {
    if (!modalTier) return;
    if (!modalReason.trim()) {
      setModalError('Reason is required — it is logged in audit.');
      return;
    }
    if (modalTargetState === 'SUNSETTING' && modalConfirm !== modalTier.tier) {
      setModalError(`Type "${modalTier.tier}" to confirm the SUNSETTING transition.`);
      return;
    }
    setModalBusy(true);
    setModalError(null);
    try {
      await updateTierLifecycle(modalTier.tier, {
        state: modalTargetState,
        reason: modalReason.trim(),
        confirmTierName: modalTargetState === 'SUNSETTING' ? modalConfirm : undefined,
      });
      closeModal();
      setReload((r) => r + 1);
    } catch (e) {
      setModalError(errMsg(e) ?? 'Transition failed');
    } finally {
      setModalBusy(false);
    }
  };

  return (
    <div className='px-4 sm:px-6 lg:px-8 py-6 space-y-6 max-w-6xl mx-auto'>
      <header className='flex items-center gap-3'>
        <Layers className='h-5 w-5 text-secondary' />
        <h1 className='text-lg font-semibold text-primary'>Tier lifecycle</h1>
        <span className='text-xs text-muted font-mono uppercase tracking-wide'>SUPER_ADMIN</span>
      </header>

      <p className='text-sm text-secondary max-w-2xl'>
        Manage tier visibility and retirement. <strong>LISTED</strong> shows the tier on public
        pricing pages and accepts new signups. <strong>HIDDEN</strong> is invisible to new buyers
        but existing subscribers continue to renew. <strong>SUNSETTING</strong> queues
        cancel-at-period-end on the gateway for every active subscription — fully executes only when
        the feature flag{' '}
        <code className='text-[11px] bg-[var(--gray-200)] px-1 py-0.5 rounded-sm'>
          billing.lifecycle.sunsetting.enabled
        </code>{' '}
        is on.
      </p>

      <GlassPanel className='p-4'>
        {loading ? (
          <p className='text-xs text-muted py-4 text-center'>Loading…</p>
        ) : (
          <table className='w-full text-sm'>
            <thead>
              <tr className='text-[10px] text-muted uppercase tracking-wider border-b border-[var(--border-default)]'>
                <th className='text-left py-2 px-2'>Tier</th>
                <th className='text-left py-2 px-2'>State</th>
                <th className='text-right py-2 px-2'>Subscribers</th>
                <th className='text-right py-2 px-2'>MRR (USD)</th>
                <th className='text-left py-2 px-2'>Changed</th>
                <th className='text-right py-2 px-2'>Actions</th>
              </tr>
            </thead>
            <tbody>
              {tiers.map((t) => {
                const mrr = (t.monthlyRecurringRevenueUsdCents / 100).toFixed(0);
                const changedAt = t.changedAt ? new Date(t.changedAt).toLocaleDateString() : '—';
                return (
                  <tr
                    key={t.tier}
                    className='border-b border-[var(--border-default)]/50 text-secondary'
                  >
                    <td className='py-2 px-2'>
                      <div className='font-medium text-primary'>{t.displayName ?? t.tier}</div>
                      <div className='text-[10px] text-muted font-mono'>{t.tier}</div>
                    </td>
                    <td className='py-2 px-2'>
                      <span
                        className={`inline-block px-2 py-0.5 rounded text-[10px] font-semibold uppercase ${
                          t.state === 'LISTED'
                            ? 'bg-status-success/15 text-status-success'
                            : t.state === 'HIDDEN'
                              ? 'bg-status-waiting/15 text-status-waiting'
                              : 'bg-status-error/15 text-status-error'
                        }`}
                      >
                        {t.state}
                      </span>
                    </td>
                    <td className='py-2 px-2 text-right font-mono'>{t.activeSubscribers}</td>
                    <td className='py-2 px-2 text-right font-mono'>${mrr}</td>
                    {/* F-20 (round-3 audit) — show date only by default,
                        reason moved to hover. The previous inline-truncated
                        reason ("TEAM tier reinstated (V144) — pro...")
                        leaked migration-version churn into screenshots
                        whenever a screen-cap of this admin view escaped
                        SUPER_ADMIN scope. The full reason stays accessible
                        via tooltip for operators who actually need it. */}
                    <td className='py-2 px-2 text-[11px] text-muted'>
                      <span
                        title={t.reason || undefined}
                        className={
                          t.reason
                            ? 'border-b border-dashed border-[var(--border-default)] cursor-help'
                            : ''
                        }
                      >
                        {changedAt}
                      </span>
                    </td>
                    <td className='py-2 px-2 text-right space-x-1'>
                      {t.state !== 'LISTED' && (
                        <button
                          onClick={() => openTransition(t, 'LISTED')}
                          className='px-2 py-1 text-[11px] bg-[var(--gray-300)] text-primary rounded-sm hover:bg-[var(--gray-400)]'
                        >
                          List
                        </button>
                      )}
                      {t.state !== 'HIDDEN' && (
                        <button
                          onClick={() => openTransition(t, 'HIDDEN')}
                          className='px-2 py-1 text-[11px] bg-[var(--gray-300)] text-primary rounded-sm hover:bg-[var(--gray-400)]'
                        >
                          Hide
                        </button>
                      )}
                      {t.state === 'HIDDEN' && (
                        <button
                          onClick={() => openTransition(t, 'SUNSETTING')}
                          className='px-2 py-1 text-[11px] bg-status-error/15 text-status-error rounded-sm hover:bg-status-error/25'
                        >
                          Sunset
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </GlassPanel>

      {modalTier && (
        <div
          className='fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4'
          onClick={closeModal}
        >
          <div
            className='bg-[var(--gray-100)] border border-[var(--border-default)] rounded-md max-w-lg w-full p-5 space-y-4'
            onClick={(e) => e.stopPropagation()}
            role='dialog'
            aria-modal='true'
          >
            <div className='flex items-center gap-2'>
              {modalTargetState === 'SUNSETTING' && (
                <AlertTriangle className='h-5 w-5 text-status-error' />
              )}
              <h2 className='text-base font-semibold text-primary'>
                Move {modalTier.displayName ?? modalTier.tier} to {modalTargetState}
              </h2>
            </div>

            {modalTargetState === 'SUNSETTING' && (
              <div className='text-sm text-secondary bg-status-error/10 border border-status-error/30 rounded-sm p-3 space-y-2'>
                <p>
                  This will queue cancellation at period end for {modalTier.activeSubscribers}{' '}
                  active subscription(s), totalling{' '}
                  <strong>
                    ${(modalTier.monthlyRecurringRevenueUsdCents / 100).toFixed(0)}/month
                  </strong>{' '}
                  in recurring revenue.
                </p>
                <p className='text-xs text-muted'>
                  Partially reversible — un-cancelling individual subscriptions requires
                  per-customer Stripe API calls after this executes.
                </p>
                <p className='text-xs text-muted'>
                  Gateway side-effects fire only if the{' '}
                  <code>billing.lifecycle.sunsetting.enabled</code> flag is on. Until then, this
                  only flips the DB column + writes audit.
                </p>
              </div>
            )}

            {modalTargetState === 'HIDDEN' && (
              <div className='text-sm text-secondary'>
                Hides {modalTier.tier} from PlansPage / UpgradeModal / PricingPage. Existing
                subscribers continue untouched. Instant, fully reversible.
              </div>
            )}

            {modalTargetState === 'LISTED' && (
              <div className='text-sm text-secondary'>
                Restores {modalTier.tier} to the public pricing surface. Accepts new signups again.
              </div>
            )}

            <div>
              <label className='text-xs text-muted font-mono uppercase tracking-wide'>
                Reason (required — logged in audit)
              </label>
              <textarea
                value={modalReason}
                onChange={(e) => setModalReason(e.target.value)}
                rows={2}
                placeholder='e.g. TEAM launch; PRO grandfathered'
                className='mt-1 w-full px-2 py-1.5 bg-[var(--gray-200)] border border-[var(--border-default)] rounded-sm text-sm text-primary focus:outline-none focus:ring-1 focus:ring-accent-blue'
                disabled={modalBusy}
              />
            </div>

            {modalTargetState === 'SUNSETTING' && (
              <div>
                <label className='text-xs text-muted font-mono uppercase tracking-wide'>
                  Type <code>{modalTier.tier}</code> to confirm
                </label>
                <input
                  type='text'
                  value={modalConfirm}
                  onChange={(e) => setModalConfirm(e.target.value)}
                  className='mt-1 w-full px-2 py-1.5 bg-[var(--gray-200)] border border-[var(--border-default)] rounded-sm text-sm text-primary focus:outline-none focus:ring-1 focus:ring-status-error'
                  disabled={modalBusy}
                />
              </div>
            )}

            {modalError && <p className='text-xs text-status-error'>{modalError}</p>}

            <div className='flex justify-end gap-2 pt-2'>
              <button
                onClick={closeModal}
                className='px-3 py-1.5 text-sm bg-[var(--gray-300)] text-primary rounded-sm hover:bg-[var(--gray-400)]'
                disabled={modalBusy}
              >
                Cancel
              </button>
              <button
                onClick={submit}
                disabled={modalBusy}
                className={`px-3 py-1.5 text-sm rounded-sm ${
                  modalTargetState === 'SUNSETTING'
                    ? 'bg-status-error text-white hover:bg-status-error/80'
                    : 'bg-accent-blue text-primary hover:bg-accent-blue-hover'
                } disabled:opacity-50`}
              >
                {modalBusy ? 'Applying…' : `Move to ${modalTargetState}`}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
