import type { editor } from 'monaco-editor';

/**
 * Geist-inspired Monaco editor theme matching Vercel's code viewer.
 * Pure black background, subtle gray gutters, restrained syntax coloring.
 */
export const GEIST_THEME_NAME = 'geist-dark';

export const geistDarkTheme: editor.IStandaloneThemeData = {
  base: 'vs-dark',
  inherit: true,
  rules: [
    // Comments — muted
    { token: 'comment', foreground: '555555', fontStyle: 'italic' },
    { token: 'comment.block', foreground: '555555', fontStyle: 'italic' },

    // Keywords — white (not colored)
    { token: 'keyword', foreground: 'ededed' },
    { token: 'keyword.control', foreground: 'ededed' },
    { token: 'storage', foreground: 'ededed' },
    { token: 'storage.type', foreground: 'ededed' },

    // Strings — subtle blue
    { token: 'string', foreground: '79c0ff' },
    { token: 'string.escape', foreground: '79c0ff' },

    // Numbers
    { token: 'number', foreground: '79c0ff' },
    { token: 'constant.numeric', foreground: '79c0ff' },

    // Types — secondary gray
    { token: 'type', foreground: 'a1a1a1' },
    { token: 'type.identifier', foreground: 'a1a1a1' },
    { token: 'entity.name.type', foreground: 'a1a1a1' },
    { token: 'support.type', foreground: 'a1a1a1' },

    // Functions — primary white
    { token: 'entity.name.function', foreground: 'ededed' },
    { token: 'support.function', foreground: 'ededed' },

    // Variables
    { token: 'variable', foreground: 'ededed' },
    { token: 'variable.parameter', foreground: 'a1a1a1' },

    // Tags (HTML/JSX)
    { token: 'tag', foreground: 'ededed' },
    { token: 'metatag', foreground: '666666' },

    // Attributes
    { token: 'attribute.name', foreground: 'a1a1a1' },
    { token: 'attribute.value', foreground: '79c0ff' },

    // Operators & punctuation
    { token: 'delimiter', foreground: '666666' },
    { token: 'delimiter.bracket', foreground: '888888' },
    { token: 'operator', foreground: '888888' },

    // Constants
    { token: 'constant', foreground: '79c0ff' },
    { token: 'constant.language', foreground: 'ededed' },

    // Annotations / decorators
    { token: 'annotation', foreground: '888888' },

    // Regex
    { token: 'regexp', foreground: '79c0ff' },
  ],
  colors: {
    // Editor background — pure black
    'editor.background': '#000000',
    'editor.foreground': '#ededed',

    // Gutter / line numbers
    'editorLineNumber.foreground': '#333333',
    'editorLineNumber.activeForeground': '#666666',
    'editorGutter.background': '#000000',

    // Cursor & selection
    'editorCursor.foreground': '#ededed',
    'editor.selectionBackground': '#1a1a1a',
    'editor.inactiveSelectionBackground': '#111111',
    'editor.selectionHighlightBackground': '#1a1a1a',

    // Line highlight
    'editor.lineHighlightBackground': '#0a0a0a',
    'editor.lineHighlightBorder': '#00000000',

    // Matching brackets
    'editorBracketMatch.background': '#1a1a1a',
    'editorBracketMatch.border': '#333333',

    // Indent guides
    'editorIndentGuide.background': '#1a1a1a',
    'editorIndentGuide.activeBackground': '#333333',

    // Scrollbar
    'scrollbar.shadow': '#00000000',
    'scrollbarSlider.background': '#25252580',
    'scrollbarSlider.hoverBackground': '#33333380',
    'scrollbarSlider.activeBackground': '#55555580',

    // Widget (find, suggest)
    'editorWidget.background': '#111111',
    'editorWidget.border': '#252525',

    // Overview ruler
    'editorOverviewRuler.border': '#00000000',

    // Folding
    'editorGutter.foldingControlForeground': '#555555',

    // Borders
    'contrastBorder': '#00000000',
    'focusBorder': '#0070F3',
  },
};
