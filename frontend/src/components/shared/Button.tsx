import {forwardRef, type ButtonHTMLAttributes} from 'react';
import {cva, type VariantProps} from 'class-variance-authority';
import {cn} from '@/lib/utils';

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-1.5 font-medium rounded-md transition-colors select-none disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-blue)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--geist-bg)]',
  {
    variants: {
      variant: {
        primary:
          'bg-[var(--accent-blue)] text-white hover:bg-[var(--accent-blue-hover)] disabled:hover:bg-[var(--accent-blue)]',
        secondary:
          'bg-[var(--gray-200)] text-[var(--text-primary)] border border-[var(--border-default)] hover:bg-[var(--gray-300)] hover:border-[var(--border-hover)]',
        tertiary:
          'bg-transparent text-[var(--text-primary)] border border-[var(--border-default)] hover:bg-[var(--gray-200)] hover:border-[var(--border-hover)]',
        ghost:
          'bg-transparent text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--gray-200)]',
        danger:
          'bg-[var(--status-error)] text-white hover:bg-status-error disabled:hover:bg-[var(--status-error)]',
        success:
          'bg-[var(--status-success)] text-white hover:bg-status-success disabled:hover:bg-[var(--status-success)]',
      },
      size: {
        sm: 'text-xs px-2.5 py-1 h-7',
        md: 'text-sm px-3 py-1.5 h-8',
        lg: 'text-sm px-4 py-2 h-10',
        icon: 'h-8 w-8 p-0',
      },
      fullWidth: {
        true: 'w-full',
      },
    },
    defaultVariants: {
      variant: 'primary',
      size: 'md',
    },
  },
);

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof buttonVariants> {
  loading?: boolean;
}

const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({className, variant, size, fullWidth, loading, disabled, children, ...props}, ref) => {
    return (
      <button
        ref={ref}
        className={cn(buttonVariants({variant, size, fullWidth}), className)}
        disabled={disabled || loading}
        {...props}
      >
        {loading ? (
          <>
            <span className='inline-block w-3 h-3 rounded-full border-2 border-current border-t-transparent animate-spin' />
            <span className='opacity-70'>{children}</span>
          </>
        ) : (
          children
        )}
      </button>
    );
  },
);

Button.displayName = 'Button';

export default Button;
export {Button, buttonVariants};
