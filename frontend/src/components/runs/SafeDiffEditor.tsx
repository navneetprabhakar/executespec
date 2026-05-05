import {DiffEditor, type DiffOnMount} from '@monaco-editor/react';

import {GEIST_THEME_NAME} from '@/lib/monacoGeistTheme';

/**
 * Wrapper around `<DiffEditor>` that fixes the disposal race at the source.
 *
 * **The race.** `@monaco-editor/react`'s unmount cleanup is a `useEffect`
 * cleanup that does, in order:
 *
 *     original.dispose();   // ← fires onWillDispose on the TextModel
 *     modified.dispose();   // ← same
 *     editor.dispose();     // ← only NOW does the editor tear down
 *
 * Inside Monaco's `DiffEditorWidget` (verified against the Monaco 0.52.2
 * minified bundle on jsDelivr), the constructor registers an
 * `autorunWithStore` that subscribes `onWillDispose` on each TextModel:
 *
 *     for (const te of [Z.model.original, Z.model.modified])
 *       Q.add(te.onWillDispose(() => {
 *         onUnexpectedError(new BugIndicatingError(
 *           "TextModel got disposed before DiffEditorWidget model got reset"));
 *         this.setModel(null);
 *       }));
 *
 * When @monaco-editor/react disposes the *models* before it disposes the
 * *editor*, that listener fires and throws.
 *
 * **What we tried first (didn't work).**
 *   v1: `useLayoutEffect` cleanup → `editor.setModel(null)`, theory was the
 *       autorun would re-run with null and detach its store before the
 *       lib's `useEffect` cleanup. Console kept showing "swallowed
 *       Monaco disposal race" warnings — autorun re-evaluation is
 *       batched / not synchronous from `setModel`.
 *   v2: Use `keepCurrentOriginalModel/Modified` + dispose models in a
 *       `queueMicrotask` after `editor.dispose()`. Same result —
 *       Monaco's editor.dispose() doesn't synchronously tear down the
 *       autorun's listener registration on the underlying TextModels,
 *       so when we call `model.dispose()` in the microtask the listener
 *       is still active and throws.
 *
 * **The fix that works.** Use the library's documented
 * `keepCurrentOriginalModel` + `keepCurrentModifiedModel` flags so the
 * lib's cleanup reduces to just `editor.dispose()` — no model.dispose()
 * calls at all. We deliberately DO NOT dispose the models ourselves
 * either. The TextModels become unreachable as soon as the editor is
 * disposed (no JS code holds a reference to them), so the JavaScript
 * GC reclaims them automatically. Monaco's internal `_modelService`
 * does NOT pin them — it only holds a reference when callers retrieve
 * a model by URI, and we never set explicit URIs (so each mount creates
 * a fresh anonymous model).
 *
 * This eliminates the disposal race entirely: there's no `model.dispose()`
 * to fire the willDispose event, so the listener never throws. The
 * trade-off is that GC reclaims the models lazily instead of synchronously
 * — for the small text models we use (a single file's contents per side)
 * this is negligible (a few KB until the next GC cycle).
 *
 * The window-level suppressor in `lib/monacoSetup.ts` and the
 * `MonacoSafeMount` error boundary stay as defence-in-depth in case a
 * future Monaco internal change opens a new variant of the race.
 */
type Props = React.ComponentProps<typeof DiffEditor>;

export default function SafeDiffEditor(props: Props) {
  const handleMount: DiffOnMount = (editor, monaco) => {
    props.onMount?.(editor, monaco);
  };

  // keepCurrentOriginalModel + keepCurrentModifiedModel reduce the lib's
  // cleanup function to just `editor.dispose()` — no model.dispose() calls,
  // no willDispose listener firing, no race. The TextModels are then
  // unreachable after editor.dispose() and JS GC reclaims them.
  return (
    <DiffEditor
      theme={GEIST_THEME_NAME}
      {...props}
      keepCurrentOriginalModel={true}
      keepCurrentModifiedModel={true}
      onMount={handleMount}
    />
  );
}
