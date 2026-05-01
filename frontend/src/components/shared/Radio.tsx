import {forwardRef, type InputHTMLAttributes, type ReactNode} from 'react';
import {cn} from '@/lib/utils';

export interface RadioProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> {
  label?: ReactNode;
  description?: ReactNode;
  size?: 'sm' | 'md';
}

const Radio = forwardRef<HTMLInputElement, RadioProps>(
  ({className, label, description, size = 'md', checked, disabled, id, ...props}, ref) => {
    const dim = size === 'sm' ? 'w-3.5 h-3.5' : 'w-4 h-4';
    const dotDim = size === 'sm' ? 'w-1.5 h-1.5' : 'w-2 h-2';
    const control = (
      <span
        className={cn(
          'relative inline-flex items-center justify-center rounded-full border transition-colors shrink-0',
          dim,
          checked
            ? 'border-[var(--accent-blue)]'
            : 'border-[var(--border-default)] hover:border-[var(--border-hover)]',
          disabled && 'opacity-50',
        )}
      >
        <input
          ref={ref}
          id={id}
          type='radio'
          checked={checked}
          disabled={disabled}
          className='absolute inset-0 opacity-0 cursor-pointer disabled:cursor-not-allowed'
          {...props}
        />
        {checked && (
          <span
            className={cn(dotDim, 'rounded-full bg-[var(--accent-blue)] pointer-events-none')}
          />
        )}
      </span>
    );

    if (!label && !description) {
      return <span className={cn('inline-flex', className)}>{control}</span>;
    }

    return (
      <label
        htmlFor={id}
        className={cn(
          'inline-flex items-start gap-2 cursor-pointer select-none',
          disabled && 'cursor-not-allowed opacity-60',
          className,
        )}
      >
        {control}
        <span className='flex flex-col gap-0.5 leading-tight'>
          {label && <span className='text-sm text-[var(--text-primary)]'>{label}</span>}
          {description && (
            <span className='text-xs text-[var(--text-secondary)]'>{description}</span>
          )}
        </span>
      </label>
    );
  },
);

Radio.displayName = 'Radio';

export default Radio;
export {Radio};
