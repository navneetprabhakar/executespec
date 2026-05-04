import {useEffect, useState} from 'react';
import {Rocket, Sparkles, Cloud, Server, Globe} from 'lucide-react';
import {useNavigate} from 'react-router-dom';
import DeploymentCard from '@/components/deployments/DeploymentCard';
import DeployWizardModal from '@/components/deployments/DeployWizardModal';
import {getDeployments} from '@/lib/api';
import type {DeploymentSummary} from '@/lib/api';
import {Skeleton} from '@/components/shared/SkeletonLoader';
import EmptyState from '@/components/shared/EmptyState';
import PageShell from '@/components/shared/PageShell';
import {useTierPermissions} from '@/hooks/useTierPermissions';
import RoleGuard from '@/components/auth/RoleGuard';

function DeploymentsPageInner() {
  const [deployments, setDeployments] = useState<DeploymentSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [showWizard, setShowWizard] = useState(false);
  const permissions = useTierPermissions();
  const navigate = useNavigate();

  const canDeploy = permissions?.canDeploy;

  const load = async () => {
    if (!canDeploy) return;
    setLoading(true);
    try {
      setDeployments(await getDeployments());
    } catch {
      setDeployments([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (canDeploy) {
      load();
      const i = setInterval(() => {
        if (!document.hidden) load();
      }, 30000);
      return () => clearInterval(i);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canDeploy]);

  // Upgrade gate for FREE users
  if (permissions && !canDeploy) {
    return (
      <div className='h-full flex items-center justify-center p-4'>
        <div className='text-center space-y-6 max-w-md'>
          <div className='relative mx-auto w-fit'>
            <div className='h-16 w-16 rounded-sm bg-gradient-to-br from-accent-blue/20 to-accent-blue/20 border border-[var(--border-default)] flex items-center justify-center'>
              <Rocket className='h-8 w-8 text-accent-blue' />
            </div>
            <div className='absolute -top-1 -right-1 h-5 w-5 rounded-full bg-accent-amber flex items-center justify-center'>
              <Sparkles className='h-3 w-3 text-primary' />
            </div>
          </div>
          <div>
            <h2 className='text-base font-semibold text-primary'>Deploy Engine</h2>
            <p className='text-sm text-secondary mt-2 leading-relaxed'>
              Deploy your code directly to GCP Cloud Run with one click. Available on{' '}
              <span className='text-accent-blue font-medium'>PRO</span> and{' '}
              <span className='text-secondary font-medium'>Enterprise</span> plans.
            </p>
          </div>
          <div className='grid grid-cols-3 gap-3 text-center'>
            <div className='p-3 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)]'>
              <Cloud className='h-4 w-4 text-accent-blue mx-auto mb-1' />
              <p className='text-[10px] text-secondary'>Cloud Run</p>
            </div>
            <div className='p-3 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)]'>
              <Server className='h-4 w-4 text-secondary mx-auto mb-1' />
              <p className='text-[10px] text-secondary'>Auto-scaling</p>
            </div>
            <div className='p-3 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)]'>
              <Globe className='h-4 w-4 text-accent-green mx-auto mb-1' />
              <p className='text-[10px] text-secondary'>Your GCP Project</p>
            </div>
          </div>
          <button
            onClick={() => navigate('/billing')}
            className='px-6 py-2.5 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 transition-colors'
          >
            Upgrade to PRO
          </button>
        </div>
      </div>
    );
  }

  return (
    <PageShell
      eyebrow='Deployments · Beta'
      title='Ship runs to Cloud Run.'
      accent='Cloud Run'
      description='Pin your favourite runs as permanent deployments on your own GCP project.'
      hero
      actions={
        <button
          onClick={() => setShowWizard(true)}
          className='inline-flex items-center justify-center gap-2 px-3.5 py-2 rounded-md bg-accent-blue text-primary text-sm font-medium hover:bg-[var(--accent-blue-hover)] transition-colors'
        >
          <Rocket className='h-4 w-4' /> Deploy run
        </button>
      }
    >
      {loading ? (
        <div className='grid grid-cols-1 md:grid-cols-2 gap-3'>
          {Array.from({length: 4}).map((_, i) => (
            <Skeleton key={i} className='h-36' />
          ))}
        </div>
      ) : deployments.length === 0 ? (
        <EmptyState
          title='No deployments yet'
          description='Deploy a completed run to your GCP Cloud Run project'
          action={
            <button
              onClick={() => setShowWizard(true)}
              className='px-4 py-2 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80'
            >
              Deploy Run
            </button>
          }
        />
      ) : (
        <div className='grid grid-cols-1 md:grid-cols-2 gap-3'>
          {deployments.map((d) => (
            <DeploymentCard key={d.id} deployment={d} onRefresh={load} />
          ))}
        </div>
      )}

      {showWizard && <DeployWizardModal onClose={() => setShowWizard(false)} onRefresh={load} />}
    </PageShell>
  );
}

export default function DeploymentsPage() {
  return (
    <RoleGuard requireSuperAdmin>
      <DeploymentsPageInner />
    </RoleGuard>
  );
}
