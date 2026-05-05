import {DiffEditor, type DiffOnMount} from '@monaco-editor/react';
import {useLayoutEffect, useRef} from 'react';

import {GEIST_THEME_NAME} from '@/lib/monacoGeistTheme';

/**
 * Wrapper around `<DiffEditor>` that fixes the disposal race at the source
 * instead of swallowing it.
 *
 * **The race.** `@monaco-editor/react`'s unmount cleanup (a `useEffect`
 * cleanup) does, in order:
 *
 *   1. `original.dispose()` — fires Monaco's `onModelWillDispose` event
 *   2. `modified.dispose()` — same
 *   3. `editor.dispose()` — disposes the widget
 *
 * Between (1) and (3), the `DiffEditorWidget`'s internal listener for
 * `onModelWillDispose` fires while the widget still references the
 * about-to-be-disposed model, lands inside `_setModel(...)` with stale
 * state, and throws "TextModel got disposed before DiffEditorWidget
 * model got reset". The throw bubbles past React's render tree because
 * Monaco re-throws it inside a `setTimeout(..., 0)`, so neither
 * `componentDidCatch` nor a `useEffect` cleanup can intercept it.
 *
 * **The fix.** Detach the models from the widget *before* Monaco's
 * `useEffect` cleanup fires. `useLayoutEffect` cleanups run during the
 * commit phase, strictly before `useEffect` cleanups (which run after
 * commit). So a cleanup registered here on the wrapper component fires
 * before the inner `<DiffEditor>`'s cleanup. We call
 * `editor.setModel({original: null, modified: null})` — that detaches
 * the widget's listeners from the models. When Monaco then disposes the
 * models in step (1)/(2), no widget listener reacts; when it disposes
 * the editor in step (3), it has no models to fall over.
 *
 * Net effect: zero "TextModel got disposed before DiffEditorWidget
 * model got reset" warnings in the console (down from 1–2 per file
 * switch + every navigation away from a diff view). The window-level
 * suppressor in `lib/monacoSetup.ts` stays as a belt-and-suspenders
 * fallback for any future variation of the race we haven't seen yet.
 */
type Props = React.ComponentProps<typeof DiffEditor>;

export default function SafeDiffEditor(props: Props) {
  const editorRef = useRef<Parameters<DiffOnMount>[0] | null>(null);

  // Cleanup runs during the commit phase — strictly BEFORE the inner
  // DiffEditor's useEffect cleanup that disposes the models + editor.
  useLayoutEffect(() => {
    return () => {
      const editor = editorRef.current;
      if (!editor) return;
      try {
        // Detach the diff model from the widget. `IDiffEditor.setModel`
        // accepts `null` to clear; this is documented behaviour. After
        // this, the imminent model dispose (in @monaco-editor/react's
        // own useEffect cleanup) won't fire a "model got disposed
        // before widget reset" event because the widget no longer
        // holds them.
        editor.setModel(null);
      } catch {
        // Editor already torn down for some other reason — nothing to do.
        // Falls through to the window-level suppressor as a safety net.
      }
    };
  }, []);

  const handleMount: DiffOnMount = (editor, monaco) => {
    editorRef.current = editor;
    props.onMount?.(editor, monaco);
  };

  return <DiffEditor theme={GEIST_THEME_NAME} {...props} onMount={handleMount} />;
}
