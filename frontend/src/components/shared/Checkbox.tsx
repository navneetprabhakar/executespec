import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react';
import { Check } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface CheckboxProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> {
  label?: ReactNode;
  description?: ReactNode;
  size?: 'sm' | 'md';
}

const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(
  ({ className, label, description, size = 'md', checked, disabled, id, ...props }, ref) => {
    const dim = size === 'sm' ? 'w-3.5 h-3.5' : 'w-4 h-4';
    const iconDim = size === 'sm' ? 'w-2.5 h-2.5' : 'w-3 h-3';
    const control = (
      <span
        className={cn(
          'relative inline-flex items-center justify-center rounded border transition-colors shrink-0',
          dim,
          checked
            ? 'bg-[var(--accent-blue)] border-[var(--accent-blue)]'
            : 'bg-transparent border-[var(--border-default)] hover:border-[var(--border-hover)]',
          disabled && 'opacity-50',
        )}
      >
        <input
          ref={ref}
          id={id}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          className="absolute inset-0 opacity-0 cursor-pointer disabled:cursor-not-allowed"
          {...props}
        />
        {checked && <Check className={cn(iconDim, 'text-white pointer-events-none')} strokeWidth={3} />}
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
        <span className="flex flex-col gap-0.5 leading-tight">
          {label && <span className="text-sm text-[var(--text-primary)]">{label}</span>}
          {description && (
            <span className="text-xs text-[var(--text-secondary)]">{description}</span>
          )}
        </span>
      </label>
    );
  },
);

Checkbox.displayName = 'Checkbox';

export default Checkbox;
export { Checkbox };
