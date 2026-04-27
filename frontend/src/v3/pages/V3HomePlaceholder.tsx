/**
 * R-101 — temporary v3 home placeholder.
 *
 * Lives at /v3/home. Replaced by R-120 (the real chat-shaped project
 * home). Keeps the route alive so we can sanity-check the shell + the
 * theme lock without needing the full dependency chain.
 */
export default function V3HomePlaceholder() {
  return (
    <div className="v3-placeholder">
      <h1>v3 shell · ready</h1>
      <p>
        Route <code>/v3/home</code> mounted via <code>V3Shell</code>.
      </p>
      <p style={{ marginTop: 12 }}>
        Project home (<code>R-120</code>) lands next. Top-bar
        placeholders fill in via <code>R-102</code>, <code>R-103</code>,{' '}
        <code>R-104</code>, <code>R-124</code>.
      </p>
    </div>
  );
}
