import {Component, type ErrorInfo, type ReactNode} from 'react';
import {errMsg} from '@/lib/utils';

/**
 * Page-level error boundary — Task 2 of the 2026-05-01 fix list.
 *
 * Wraps the V2 AppLayout and V3 Shell main outlets. Without this,
 * any uncaught render error in any page (e.g. the AuditPage hooks-order
 * violation) drops the user onto a fully blank dark canvas with no
 * recovery affordance. The fallback below preserves the surrounding
 * chrome (header, sidebar, breadcrumbs) and offers Retry + Go home.
 *
 * Logging: errors flow to console.error and a global custom event
 * (`executespec:error-boundary`) so the existing telemetry pipeline
 * can subscribe without coupling to this component.
 */
interface ErrorBoundaryProps {
  children: ReactNode;
  /** Optional override for the fallback UI. Receives the error and a
   * reset callback that re-mounts children. */
  fallback?: (error: Error, reset: () => void) => ReactNode;
  /** Region label for telemetry — e.g. "v3-shell" or "v2-app". */
  region?: string;
}

interface ErrorBoundaryState {
  error: Error | null;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = {error: null};

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return {error};
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Local breadcrumb for live debugging in the browser console.
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary]', this.props.region ?? 'unknown', error, info.componentStack);
    // Best-effort dispatch for the telemetry/monitoring pipeline. Wrapped
    // so a missing window (SSR / tests) doesn't double-fault.
    try {
      if (typeof window !== 'undefined') {
        window.dispatchEvent(
          new CustomEvent('executespec:error-boundary', {
            detail: {
              region: this.props.region ?? 'unknown',
              message: errMsg(error),
              stack: error.stack,
              componentStack: info.componentStack,
            },
          }),
        );
      }
    } catch {
      // ignore — telemetry is best-effort
    }
  }

  reset = () => this.setState({error: null});

  render() {
    if (this.state.error) {
      if (this.props.fallback) return this.props.fallback(this.state.error, this.reset);
      return <DefaultFallback error={this.state.error} onReset={this.reset} />;
    }
    return this.props.children;
  }
}

function DefaultFallback({error, onReset}: {error: Error; onReset: () => void}) {
  // Inline styles intentionally — this fallback must render even if
  // the CSS pipeline is part of what crashed.
  return (
    <div
      role='alert'
      style={{
        minHeight: '60vh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        padding: 32,
        color: '#ECEEF2',
        background: 'transparent',
        fontFamily: '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace',
      }}
    >
      <div style={{textAlign: 'center', maxWidth: 480}}>
        <h2 style={{fontSize: 18, fontWeight: 600, margin: 0, color: '#ECEEF2'}}>
          Something went wrong on this page.
        </h2>
        <p style={{fontSize: 13, color: '#8A94A6', margin: '8px 0 0', lineHeight: 1.5}}>
          The rest of the app is fine — you can retry below or head home and try again. If this
          keeps happening, share the error message with support.
        </p>
        <code
          style={{
            display: 'block',
            marginTop: 16,
            padding: '8px 12px',
            background: '#14171C',
            border: '1px solid #242A33',
            borderRadius: 6,
            fontSize: 11.5,
            color: '#FCA5A5',
            wordBreak: 'break-word',
            textAlign: 'left',
          }}
        >
          {errMsg(error) || 'Unknown error'}
        </code>
      </div>
      <div style={{display: 'flex', gap: 8}}>
        <button
          type='button'
          onClick={onReset}
          style={{
            padding: '8px 16px',
            fontSize: 13,
            fontWeight: 600,
            background: '#5B8DEF',
            color: '#0B0D10',
            border: 'none',
            borderRadius: 6,
            cursor: 'pointer',
            fontFamily: 'inherit',
          }}
        >
          Retry
        </button>
        <a
          href='/'
          style={{
            padding: '8px 16px',
            fontSize: 13,
            fontWeight: 500,
            background: '#1B1F26',
            color: '#ECEEF2',
            border: '1px solid #242A33',
            borderRadius: 6,
            textDecoration: 'none',
            fontFamily: 'inherit',
          }}
        >
          Go home
        </a>
      </div>
    </div>
  );
}

export default ErrorBoundary;
