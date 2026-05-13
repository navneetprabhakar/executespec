// ExecuteSpec ESLint config (Google TypeScript Style + documented deviations).
//
// What this enforces (the Google TS rules that are mechanically checkable):
//   - No `var` (use const / let)
//   - Strict equality (`===`, `!==`)
//   - No-unused-vars (warn, not error — easier migration on existing code)
//   - React hooks rules (rules-of-hooks + exhaustive-deps as warn)
//   - Naming conventions (UpperCamelCase types, lowerCamelCase funcs/vars,
//                        UPPER_CASE constants, no leading/trailing underscore)
//   - Type assertions via `as` only (no angle brackets)
//   - No `any` (warning — see D-T2 in docs/design/code-style.md)
//
// What's deliberately off (see docs/design/code-style.md):
//   - import/no-default-export — D-T1 (default exports OK for *.tsx)
//   - @typescript-eslint/no-unsafe-* — D-T2 (existing `any` soup)
//   - @typescript-eslint/explicit-function-return-type — Google guide says
//     return types not required; we follow that.
//
// Prettier handles all formatting; eslint-config-prettier turns off rules
// that would conflict with it.

module.exports = {
  root: true,
  env: {browser: true, es2022: true, node: true},
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: {jsx: true},
  },
  plugins: ['@typescript-eslint', 'react', 'react-hooks'],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react/recommended',
    'plugin:react/jsx-runtime',
    'plugin:react-hooks/recommended',
    'prettier',
  ],
  settings: {
    react: {version: '18'},
  },
  rules: {
    // Google TS §6.1 — no var
    'no-var': 'error',
    'prefer-const': ['warn', {destructuring: 'all'}],

    // Google TS §6.13 — strict equality
    eqeqeq: ['error', 'always', {null: 'ignore'}],

    // Google TS §3.8 — no `eval`, no `new Function(...string)`
    'no-eval': 'error',
    'no-implied-eval': 'error',
    'no-new-func': 'error',

    // Google TS §3.10 — no `with`
    'no-with': 'error',

    // Google TS §3.5 — no `debugger` in production code
    'no-debugger': 'error',

    // Google TS §6.5 — array constructor banned
    'no-array-constructor': 'error',
    '@typescript-eslint/no-array-constructor': 'error',

    // Google TS §6.6 — Object() constructor banned
    'no-new-object': 'error',

    // Google TS §6.7 — wrapper objects banned
    'no-new-wrappers': 'error',

    // Google TS §6.16 — equality of -0
    'no-compare-neg-zero': 'error',

    // Google TS §6.10 — switch must have default
    'default-case': 'warn',

    // ── TypeScript-specific ────────────────────────────────────────────
    // D-T2 (deviations doc): warn on `any`, don't error.
    '@typescript-eslint/no-explicit-any': 'warn',
    '@typescript-eslint/no-unused-vars': [
      'warn',
      {argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_'},
    ],

    // D-T2 — disable strict unsafe rules until existing `any` soup is cleaned.
    '@typescript-eslint/no-unsafe-argument': 'off',
    '@typescript-eslint/no-unsafe-assignment': 'off',
    '@typescript-eslint/no-unsafe-call': 'off',
    '@typescript-eslint/no-unsafe-member-access': 'off',
    '@typescript-eslint/no-unsafe-return': 'off',

    // Google TS §3.10 — non-null assertion needs careful review; warn.
    '@typescript-eslint/no-non-null-assertion': 'warn',

    // Google TS §4.5.2 — type assertion via `as`, not angle brackets.
    '@typescript-eslint/consistent-type-assertions': [
      'error',
      {assertionStyle: 'as', objectLiteralTypeAssertions: 'allow-as-parameter'},
    ],

    // Empty catches are allowed (analytics-fire-and-forget pattern is
    // common in the codebase). Other empty blocks are flagged.
    'no-empty': ['error', {allowEmptyCatch: true}],

    // ── React ──────────────────────────────────────────────────────────
    'react/prop-types': 'off', // We use TypeScript types instead.
    'react/display-name': 'off', // Forwardrefs / memos are common; not blocking.
    // rules-of-hooks: downgraded to warn because the existing codebase has
    // ~6 violations (hooks called after conditional return) that pre-date
    // this refactor — surfacing them as warnings makes them visible without
    // blocking the format pass. Tracked for cleanup in a follow-up PR.
    'react-hooks/rules-of-hooks': 'warn',
    'react-hooks/exhaustive-deps': 'warn',

    // Google TS §3.4 — React JSX uses concise empty elements.
    'react/self-closing-comp': 'warn',

    // Off: `react/no-unescaped-entities` flags raw apostrophes in JSX text
    // ("don't" → "don&apos;t"). React renders raw apostrophes correctly;
    // the rule is a hold-over from older HTML linting and forcing the
    // entity makes UI strings less readable.
    'react/no-unescaped-entities': 'off',

    // Off: `no-useless-escape` flags `\[` inside `[...]` character classes
    // and `\/` in regex literals — both are valid and harmless, and removing
    // them would not change semantics. The Google TS guide is silent on this.
    'no-useless-escape': 'off',

    // Off: `no-misleading-character-class` flags emoji regex patterns that
    // span surrogate pairs. The codebase's emoji-stripping regex in LiveLog
    // is intentional + correct under the `u` flag where needed; the lint
    // false-positives where actual usage is fine.
    'no-misleading-character-class': 'off',

    // PR-L (2026-05-14) — async onClick handlers without loading state are
    // the most-common UX bug we've shipped (user complaint 2026-05-13: "we
    // just submit and wait"). Catch them at lint time by warning on inline
    // `onClick={async () => {...}}` JSX. Suggested fix: use the
    // `useAsyncAction` hook (frontend/src/hooks/useAsyncAction.ts) which
    // exposes a `[pending, run]` tuple and handles disable + double-tap
    // protection. Existing call sites that intentionally don't need a
    // loading indicator (one-shot toggles, navigation-only handlers) can
    // suppress with `// eslint-disable-next-line no-restricted-syntax`
    // and a one-line rationale.
    //
    // Set to 'warn' (not 'error') so existing un-migrated surfaces don't
    // block the format pass. The lint:fix workflow lists the warning
    // count so reviewers see the trend.
    'no-restricted-syntax': [
      'warn',
      {
        selector:
          "JSXAttribute[name.name='onClick'] > JSXExpressionContainer > ArrowFunctionExpression[async=true]",
        message:
          'Inline async onClick without loading state. Use useAsyncAction() hook' +
          ' from @/hooks/useAsyncAction so the button shows pending state' +
          ' and is debounced against double-tap, OR add an eslint-disable' +
          ' line with a one-line rationale.',
      },
      {
        selector:
          "JSXAttribute[name.name='onSubmit'] > JSXExpressionContainer > ArrowFunctionExpression[async=true]",
        message:
          'Inline async onSubmit without loading state. Same recommendation' +
          ' as onClick — use useAsyncAction() or an explicit pending state.',
      },
    ],
  },
  overrides: [
    {
      // Helper / lib code: stricter — no default exports.
      files: ['src/lib/**/*.ts', 'src/hooks/**/*.ts', 'src/stores/**/*.ts'],
      rules: {
        // D-T1 lives only on .tsx; .ts library code should follow Google strictly.
        // (Currently no enforcement — leaving as a placeholder for an explicit
        // `import/no-default-export` rule once `eslint-plugin-import` is wired.)
      },
    },
    {
      // Test files — `any` and unused are fine in tests.
      files: ['**/*.test.{ts,tsx}', '**/*.spec.{ts,tsx}'],
      rules: {
        '@typescript-eslint/no-explicit-any': 'off',
        '@typescript-eslint/no-unused-vars': 'off',
      },
    },
  ],
  ignorePatterns: [
    'node_modules/',
    'dist/',
    'build/',
    '.vite/',
    '*.config.{ts,js,cjs}',
    '*.config.*.{ts,js,cjs}',
    '*.d.ts',
    'src/v3/lib/tierDisplay.test.ts', // dormant vitest fixture
    'src/design-system/contrast.test.ts', // dormant vitest fixture
    // Build output mirrored into Spring static dir
    '../src/main/resources/static',
  ],
};
