import type {StackInfo} from '@/types/project';

export default function StackBadge({stack}: {stack: StackInfo | null}) {
  if (!stack) {
    return (
      <span className='inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-600'>
        stack unknown
      </span>
    );
  }
  const label = [stack.language, stack.framework, stack.version].filter(Boolean).join(' · ');
  return (
    <span className='inline-flex items-center rounded-full bg-blue-50 px-2 py-0.5 text-xs text-blue-700'>
      {label || 'stack unknown'}
    </span>
  );
}
