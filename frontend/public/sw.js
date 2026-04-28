/**
 * R-171 — minimal service worker for the v3 shell.
 *
 * Goals (Wave 3):
 *   1. Make the v3 shell installable on iOS / Android via the
 *      browser's "Add to Home Screen" affordance.
 *   2. Provide a tiny offline fallback so a tap on the home-screen
 *      icon launches *something* (the app shell + an "offline"
 *      message) rather than the OS error page when there's no
 *      network.
 *
 * Explicitly NOT goals here:
 *   - Caching API responses (they're either auth-gated or
 *     time-sensitive — every backend call goes to the network).
 *   - Caching the Vite dev bundle (HMR breaks if we cache; dev
 *     uses `?dev` query to bypass install).
 *   - Push notifications (Wave 3+ work; needs backend).
 *
 * The cache version is bumped manually when the shell HTML changes.
 * Older caches are pruned on activate so a redeploy doesn't leave
 * users on stale shells.
 */

const SHELL_CACHE = 'v3-shell-v1';
const SHELL_ASSETS = [
  '/',
  '/index.html',
  '/logo.png',
  '/manifest.webmanifest',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) => cache.addAll(SHELL_ASSETS).catch(() => {}))
  );
  // Activate immediately so the next page load is on the new SW.
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== SHELL_CACHE).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  // Only cache GETs on same-origin top-level navigations.
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  // Never serve API calls from cache — auth-gated + time-sensitive.
  if (url.pathname.startsWith('/api/')) return;

  // Navigation requests (HTML page loads) → network-first with
  // cached shell as offline fallback.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).catch(() => caches.match('/index.html').then((r) => r || new Response('Offline', { status: 503 })))
    );
    return;
  }

  // Static assets (logo, manifest) → cache-first with network update.
  if (SHELL_ASSETS.includes(url.pathname)) {
    event.respondWith(
      caches.match(req).then((cached) => cached || fetch(req).then((res) => {
        if (res.ok) caches.open(SHELL_CACHE).then((cache) => cache.put(req, res.clone()));
        return res;
      }).catch(() => cached || new Response('Offline', { status: 503 })))
    );
  }
});
