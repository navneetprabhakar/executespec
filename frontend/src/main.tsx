import React from 'react';
import ReactDOM from 'react-dom/client';
import {BrowserRouter} from 'react-router-dom';
import App from './App';
import './index.css';
import AppErrorBoundary from '@/signal/components/AppErrorBoundary';
// Signal is the only design — load its tokens + component CSS globally
// so public routes (login, pricing, trust, etc.) get the chrome too.
import '@/signal/signal.css';
// Syntax highlighting theme for the IDE file viewer (Code tab)
import 'highlight.js/styles/github-dark-dimmed.css';
// RR-1534 — browser OpenTelemetry was removed. `lib/telemetry.ts` built a WebTracerProvider and
// called `provider.register(...)` but never called `registerInstrumentations`, never imported
// `@opentelemetry/auto-instrumentations-web`, and created no manual spans, so it produced no
// traces even in principle. Its only gate, `VITE_OTEL_ENDPOINT`, was set by no `.env`, no Vite
// define, and no CI or deploy script, so `initTelemetry()` early-returned in every environment.
// Meanwhile the static import here plus the `node_modules → vendor` chunking in vite.config.ts
// shipped six @opentelemetry packages in the first-paint chunk for code that could not execute,
// and the comments above it documented active fetch instrumentation that never existed.
//
// Deletion over wiring: making it real needs an instrumentation registration, a
// `propagateTraceHeaderCorsUrls` allowlist scoped to the API origin, a dynamic import behind the
// endpoint check, and an OTLP endpoint configured per deploy environment — none of which can be
// verified from this repo alone, so shipping that would have replaced silently-inert telemetry
// with unverified telemetry. Backend tracing (Micrometer + OTel → Tempo / Cloud Trace) is
// unaffected. Re-introducing browser tracing should start from the real deploy-env endpoint.

// R-171 — register the v3 service worker so iOS / Android can install
// the app from the home screen. Production-only so Vite HMR isn't
// disrupted in dev. Safe-by-default: any registration error is
// swallowed (the app still works without a SW).
if (
  typeof window !== 'undefined' &&
  'serviceWorker' in navigator &&
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (import.meta as any).env?.PROD
) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  });
}

// Signal is the only design — apply the .signal-app token scope to <html>
// + restore the saved theme before first paint so there's no FOUC.
if (typeof document !== 'undefined') {
  document.documentElement.classList.add('signal-app');
  try {
    const saved = localStorage.getItem('es:theme') || localStorage.getItem('signal:theme');
    const theme = saved === 'light' || saved === 'system' ? saved : 'dark';
    const resolved =
      theme === 'system'
        ? window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
          ? 'dark'
          : 'light'
        : theme;
    document.documentElement.setAttribute('data-theme', resolved);
    document.documentElement.setAttribute('data-signal-theme', resolved);
  } catch {
    /* ignore */
  }
}

// eslint-disable-next-line @typescript-eslint/no-non-null-assertion
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppErrorBoundary>
      <BrowserRouter future={{v7_startTransition: true, v7_relativeSplatPath: true}}>
        <App />
      </BrowserRouter>
    </AppErrorBoundary>
  </React.StrictMode>,
);
