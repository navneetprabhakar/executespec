import React from 'react';

type State = {error: Error | null};

export default class AppErrorBoundary extends React.Component<React.PropsWithChildren, State> {
  state: State = {error: null};

  static getDerivedStateFromError(error: Error): State {
    return {error};
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error('Uncaught app render error', error, info);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <main
        className='signal-app'
        style={{
          minHeight: '100vh',
          display: 'grid',
          placeItems: 'center',
          background: 'var(--signal-bg)',
          color: 'var(--signal-ink)',
          padding: 24,
        }}
      >
        <section
          style={{
            width: 'min(460px, 100%)',
            border: '1px solid var(--signal-hairline)',
            borderRadius: 8,
            background: 'var(--signal-card-bg)',
            padding: 20,
          }}
        >
          <h1 style={{fontSize: 18, margin: '0 0 8px'}}>Something broke</h1>
          <p style={{fontSize: 14, color: 'var(--signal-text-mute)', margin: '0 0 16px'}}>
            Reload the page. If it happens again, send the current URL to support.
          </p>
          <button
            className='signal-btn signal-btn-primary'
            onClick={() => window.location.reload()}
            type='button'
          >
            Reload
          </button>
        </section>
      </main>
    );
  }
}
