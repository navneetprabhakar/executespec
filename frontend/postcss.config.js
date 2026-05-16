// 2026-05-16 (Dependabot #130) — Tailwind 4 moved the PostCSS plugin to a separate
// package (`@tailwindcss/postcss`). Autoprefixer is no longer needed (Tailwind 4
// bakes it in), so dropping it keeps the pipeline simpler.
export default {
  plugins: {
    '@tailwindcss/postcss': {},
  },
};
