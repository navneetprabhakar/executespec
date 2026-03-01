import type { Config } from 'tailwindcss';

export default {
  darkMode: 'class',
  content: ['./src/**/*.{ts,tsx}', './index.html'],
  theme: {
    extend: {
      colors: {
        surface: {
          0: '#0A0A0F',
          1: '#111118',
          2: '#1A1A24',
          3: '#242432',
          4: '#2E2E3E',
        },
        accent: {
          violet: '#8B5CF6',
          emerald: '#10B981',
          amber: '#F59E0B',
          rose: '#F43F5E',
          cyan: '#06B6D4',
        },
      },
      fontFamily: {
        sans: ['Geist Sans', 'system-ui', 'sans-serif'],
        mono: ['Geist Mono', 'JetBrains Mono', 'monospace'],
      },
      animation: {
        'glow-pulse': 'glow-pulse 2s ease-in-out infinite',
        'slide-in-right': 'slide-in-right 0.3s ease-out',
        'fade-in-up': 'fade-in-up 0.4s ease-out',
        shimmer: 'shimmer 2s linear infinite',
      },
      keyframes: {
        'glow-pulse': {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.5' },
        },
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
      },
      boxShadow: {
        'glow-violet': '0 0 12px rgba(139, 92, 246, 0.25)',
        'glow-emerald': '0 0 12px rgba(16, 185, 129, 0.25)',
        'glow-amber': '0 0 12px rgba(245, 158, 11, 0.25)',
        'glow-rose': '0 0 12px rgba(244, 63, 94, 0.25)',
        'glow-cyan': '0 0 12px rgba(6, 182, 212, 0.25)',
        glass: 'inset 0 1px 0 0 rgba(255, 255, 255, 0.03)',
      },
    },
  },
  plugins: [],
} satisfies Config;
