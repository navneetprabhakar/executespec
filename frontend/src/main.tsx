import React from 'react';
import ReactDOM from 'react-dom/client';
import {BrowserRouter} from 'react-router-dom';
import App from './App';
import './index.css';
// Signal is the only design — load its tokens + component CSS globally
// so public routes (login, pricing, trust, etc.) get the chrome too.
import '@/signal/signal.css';
// Syntax highlighting theme for the IDE file viewer (Code tab)
import 'highlight.js/styles/github-dark-dimmed.css';
// OpenTelemetry browser telemetry (traces + fetch instrumentation)
import {initTelemetry} from '@/lib/telemetry';

// Initialize OTel before rendering — instruments all subsequent fetch() calls
initTelemetry();

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
    const saved = localStorage.getItem('signal:theme');
    const sysDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.setAttribute(
      'data-signal-theme',
      saved || (sysDark ? 'dark' : 'light'),
    );
  } catch {
    /* ignore */
  }
}

// eslint-disable-next-line @typescript-eslint/no-non-null-assertion
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter future={{v7_startTransition: true, v7_relativeSplatPath: true}}>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
);
