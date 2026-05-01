import {useNavigate} from 'react-router-dom';

interface UpgradeNudgeProps {
  isOpen: boolean;
  onClose: () => void;
  currentTier: string;
  metric: string; // RUNS, PROJECTS, STORAGE_MB
  current: number;
  max: number;
}

export function UpgradeNudge({
  isOpen,
  onClose,
  currentTier,
  metric,
  current,
  max,
}: UpgradeNudgeProps) {
  const navigate = useNavigate();
  if (!isOpen) return null;

  const metricLabel = metric === 'RUNS' ? 'runs' : metric === 'PROJECTS' ? 'projects' : 'storage';

  const nextTier = currentTier === 'FREE' ? 'Starter' : currentTier === 'STARTER' ? 'Pro' : 'Pro';
  const nextPrice = currentTier === 'FREE' ? '\u20B91,499' : '\u20B99,999';
  const nextLimit = currentTier === 'FREE' ? 50 : 500;

  return (
    <div className='fixed inset-0 z-50 flex items-center justify-center p-2 md:p-4 bg-[var(--gray-200)]'>
      <div
        className='bg-[var(--gray-100)] border border-[var(--border-default)] rounded-md max-w-md w-full mx-4 p-5 sm:p-8 text-center overflow-y-auto'
        style={{maxHeight: '95dvh'}}
      >
        <div className='text-4xl mb-4'>{'\u{1F680}'}</div>
        <h2 className='text-xl font-bold text-primary'>You've used all your {metricLabel}</h2>
        <p className='mt-2 text-secondary'>
          {current}/{max} {metricLabel} used this month.
        </p>

        <div className='mt-6 p-4 bg-[var(--gray-100)] rounded-sm'>
          <p className='text-sm font-semibold text-primary'>
            Upgrade to {nextTier} for {nextLimit} {metricLabel}/month
          </p>
          <p className='text-2xl font-bold text-accent-blue mt-1'>
            {nextPrice}
            <span className='text-sm font-normal'>/mo</span>
          </p>
        </div>

        <div className='mt-6 flex gap-3'>
          <button
            onClick={onClose}
            className='flex-1 px-4 py-2.5 text-sm text-secondary bg-[var(--gray-200)] rounded-sm hover:bg-[var(--gray-300)]'
          >
            Not now
          </button>
          <button
            onClick={() => navigate('/plans')}
            className='flex-1 px-4 py-2.5 text-sm text-primary bg-accent-blue rounded-sm hover:bg-accent-blue-hover font-semibold'
          >
            Upgrade Now
          </button>
        </div>
      </div>
    </div>
  );
}
