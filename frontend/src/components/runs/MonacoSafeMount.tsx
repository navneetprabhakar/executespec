import {Component, type ErrorInfo, type ReactNode} from 'react';
import {errMsg} from '@/lib/utils';
import {isMonacoDisposeRace as matchDisposeRaceMessage} from '@/lib/monacoErrorPatterns';

/**
 * Error boundary specifically for Monaco editor disposal races.
 *
 * Symptom (run a076c876 apply path, 2026-05-03 console; reproduced again
 * on dev 2026-05-04 with the V3 RunDetailPage diff viewer):
 *   "Uncaught Error: TextModel got disposed before DiffEditorWidget model got reset"
 *
 * Root cause: React 18 strict-mode double-cleanup can drive `@monaco-editor/react`'s
 * unmount path twice on the same DiffEditor, and Monaco's internal event queue can
 * fire a model-reset deliver AFTER the underlying TextModel was disposed by the
 * first cleanup pass. The exception is uncatchable from a useEffect cleanup
 * because it's thrown out of Monaco's own deferred event loop, not the React
 * unmount call. Bubbles up as an "Uncaught Error" that detonates the whole
 * subtree, which in turn tears down any in-flight fetch/SSE channels (this is
 * how it killed the apply-result stream three seconds in).
 *
 * The boundary catches the exception, classifies it by message, and ONLY swallows
 * the known-benign disposal race. Anything else re-throws so genuine bugs surface.
 *
 * NOTE: The setTimeout-deferred path (most common — Monaco's `onUnexpectedError`
 * re-throws inside `setTimeout(..., 0)` so the error reaches the console past
 * any sync handler) escapes the React render tree entirely and CANNOT be caught
 * here. That path is handled by the window-level handler installed in
 * `lib/monacoSetup.ts`. This boundary remains as the inner layer for the rarer
 * in-render disposal path (e.g. model.getValue() called mid-render after dispose).
 *
 * Reset strategy: the boundary remounts a fresh subtree every time `resetKey`
 * changes (i.e., when the user picks a different file). This way the user can
 * recover by clicking another file even after a single race tripped the boundary.
 */
type Props = {
  children: ReactNode;
  /** Bumping this value forces the boundary to remount its children fresh. */
  resetKey?: string;
  /** Optional fallback rendered when a real (non-Monaco-disposal) error escapes. */
  fallback?: ReactNode;
};

type State = {hasError: boolean; lastResetKey: string | undefined};

/**
 * Pattern source of truth lives in `lib/monacoErrorPatterns.ts` so the boundary
 * here and the window-level handler in `lib/monacoSetup.ts` share one list.
 */
function isMonacoDisposeRace(err: unknown): boolean {
  if (!(err instanceof Error)) return false;
  return matchDisposeRaceMessage(errMsg(err));
}

export class MonacoSafeMount extends Component<Props, State> {
  state: State = {hasError: false, lastResetKey: undefined};

  static getDerivedStateFromError(err: unknown): Partial<State> {
    if (isMonacoDisposeRace(err)) {
      // Boundary will rerender children; the disposal race left no usable
      // editor so we still need to reset on next file pick (resetKey change).
      return {hasError: true};
    }
    // Re-throw real errors by leaving hasError unset so React keeps unwinding.
    throw err;
  }

  componentDidCatch(err: Error, info: ErrorInfo) {
    if (!isMonacoDisposeRace(err)) return;
    // Quiet logging — these are noisy and we don't want to drown the console.
    // Two lines: one summary + the location so the user can grep if needed.
    console.warn('[MonacoSafeMount] swallowed disposal race:', errMsg(err));
    if (info?.componentStack) {
      console.warn('[MonacoSafeMount]', info.componentStack.trim().split('\n')[0]);
    }
  }

  componentDidUpdate(prevProps: Props) {
    if (prevProps.resetKey !== this.props.resetKey && this.state.hasError) {
      // Caller picked a different file — clear the error and let the next mount
      // attempt (with the new resetKey) re-render Monaco.
      this.setState({hasError: false, lastResetKey: this.props.resetKey});
    }
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback ?? null;
    }
    // The outer div key is the second layer of defense: when resetKey changes,
    // React unmounts the entire subtree and remounts fresh. This is what
    // actually prevents the race from compounding across rapid file switches —
    // by the time Monaco's deferred event fires for the OLD file, its parent
    // is gone and the throw happens against an unmounted subtree (where the
    // boundary's getDerivedStateFromError catches it cleanly).
    return <div key={this.props.resetKey ?? 'monaco-safe-default'}>{this.props.children}</div>;
  }
}

export default MonacoSafeMount;
