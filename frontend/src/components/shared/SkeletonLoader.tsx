import {cn} from '@/lib/utils';

interface SkeletonProps {
  className?: string;
}

export function Skeleton({className}: SkeletonProps) {
  return <div className={cn('shimmer rounded-md bg-[var(--gray-200)]', className)} />;
}

export function RunCardSkeleton() {
  return (
    <div className='glass-panel p-4 flex items-start gap-3'>
      <Skeleton className='h-2.5 w-2.5 rounded-full mt-1 flex-shrink-0' />
      <div className='flex-1 space-y-2'>
        <Skeleton className='h-4 w-24' />
        <Skeleton className='h-3 w-full' />
        <Skeleton className='h-3 w-3/4' />
        <div className='flex gap-2 mt-1'>
          <Skeleton className='h-3 w-16' />
          <Skeleton className='h-3 w-12' />
        </div>
      </div>
    </div>
  );
}
