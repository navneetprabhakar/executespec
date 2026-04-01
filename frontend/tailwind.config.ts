import type { Config } from 'tailwindcss';

export default {
  darkMode: 'class',
  content: ['./src/**/*.{ts,tsx}', './index.html'],
  theme: {
    extend: {
      colors: {
        // Geist gray scale (via CSS vars for single-source-of-truth)
        geist: {
          bg: 'var(--geist-bg)',
          'bg-secondary': 'var(--geist-bg-secondary)',
        },
        gray: {
          100: 'var(--gray-100)',
          200: 'var(--gray-200)',
          300: 'var(--gray-300)',
          400: 'var(--gray-400)',
          500: 'var(--gray-500)',
          600: 'var(--gray-600)',
          700: 'var(--gray-700)',
          800: 'var(--gray-800)',
          900: 'var(--gray-900)',
          1000: 'var(--gray-1000)',
        },
        // Semantic accent — color only for meaning
        accent: {
          blue: 'var(--accent-blue)',
          'blue-hover': 'var(--accent-blue-hover)',
          red: 'var(--status-error)',
          amber: 'var(--status-building)',
          green: '#17B169',
        },
        // Status colors
        status: {
          success: 'var(--status-success)',
          building: 'var(--status-building)',
          error: 'var(--status-error)',
          queued: 'var(--status-queued)',
        },
        // Border
        border: {
          default: 'var(--border-default)',
          hover: 'var(--border-hover)',
        },
      },
      textColor: {
        primary: 'var(--text-primary)',
        secondary: 'var(--text-secondary)',
        muted: 'var(--text-muted)',
      },
      height: {
        dvh: '100dvh',
      },
      minHeight: {
        dvh: '100dvh',
      },
      maxHeight: {
        dvh: '100dvh',
      },
      fontFamily: {
        sans: ['Geist Sans', 'system-ui', 'sans-serif'],
        mono: ['Geist Mono', 'monospace'],
      },
      spacing: {
        'sp-1': '4px',
        'sp-2': '8px',
        'sp-3': '12px',
        'sp-4': '16px',
        'sp-6': '24px',
        'sp-8': '32px',
        'sp-12': '48px',
        'sp-16': '64px',
        'sp-24': '96px',
        'sp-32': '128px',
      },
      borderRadius: {
        sm: 'var(--radius-sm)',
        md: 'var(--radius-md)',
        lg: 'var(--radius-lg)',
      },
      animation: {
        'slide-in-right': 'slide-in-right 0.3s ease-out',
        'fade-in-up': 'fade-in-up 0.4s ease-out',
        shimmer: 'shimmer 2s linear infinite',
        'log-line': 'log-line 0.15s ease-out',
        'status-pulse': 'status-pulse 2s ease-in-out infinite',
      },
      keyframes: {
        'slide-in-right': {
          '0%': { transform: 'translateX(100%)', opacity: '0' },
          '100%': { transform: 'translateX(0)', opacity: '1' },
        },
        'fade-in-up': {
          '0%': { transform: 'translateY(8px)', opacity: '0' },
          '100%': { transform: 'translateY(0)', opacity: '1' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
        'log-line': {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        'status-pulse': {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.4' },
        },
      },
    },
  },
  plugins: [],
} satisfies Config;
