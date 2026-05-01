import {loader} from '@monaco-editor/react';
import {GEIST_THEME_NAME, geistDarkTheme} from './monacoGeistTheme';

/**
 * Centralized Monaco editor initialization.
 * Configures the CDN loader and registers the Geist theme.
 * Safe to import from multiple files — init() is idempotent.
 */

// Configure the loader to use a specific CDN version for reliability
loader.config({
  paths: {
    vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min/vs',
  },
});

export const monacoReady: Promise<typeof import('monaco-editor')> = loader
  .init()
  .then((monaco) => {
    monaco.editor.defineTheme(GEIST_THEME_NAME, geistDarkTheme);
    return monaco;
  })
  .catch((err) => {
    console.warn('Monaco editor failed to load from CDN:', err);
    throw err;
  });
