import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import './index.css';
// Syntax highlighting theme for the IDE file viewer (Code tab)
import 'highlight.js/styles/github-dark-dimmed.css';
// OpenTelemetry browser telemetry (traces + fetch instrumentation)
import { initTelemetry } from '@/lib/telemetry';

// Initialize OTel before rendering — instruments all subsequent fetch() calls
initTelemetry();

// Disable right-click context menu across entire UI
document.addEventListener('contextmenu', (e) => e.preventDefault());

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <App />
    </BrowserRouter>
  </React.StrictMode>
);
