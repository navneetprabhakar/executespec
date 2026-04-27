import { useParams, Link } from 'react-router-dom';

/**
 * R-124 — temporary v3 project home.
 *
 * Placeholder until R-120 (the real chat-shaped project home) lands.
 * Renders the slug from the URL so the switcher's navigation can be
 * verified end-to-end.
 */
export default function V3ProjectHomePlaceholder() {
  const { slug } = useParams<{ slug: string }>();
  return (
    <div className="v3-placeholder" style={{ textAlign: 'left' }}>
      <h1 style={{ textAlign: 'center' }}>Project · {slug?.slice(0, 8)}</h1>
      <p style={{ textAlign: 'center' }}>
        Route <code>/v3/p/{slug}</code> mounted.
      </p>
      <p style={{ textAlign: 'center', marginTop: 16, fontSize: 12 }}>
        Real chat-shaped project home lands at <code>R-120</code> (next).
      </p>
      <p style={{ textAlign: 'center', marginTop: 24 }}>
        <Link
          to="/v3/home"
          style={{ color: 'var(--v3-brand)', textDecoration: 'none', fontSize: 12 }}
        >
          ← Back to v3 home
        </Link>
      </p>
    </div>
  );
}
