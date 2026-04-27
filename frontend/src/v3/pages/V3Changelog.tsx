import { Link } from 'react-router-dom';
import Pill from '../components/Pill';

/**
 * R-181 — /v3/changelog page.
 *
 * Greenfield §10.2: a one-page index of every IA change shipped in
 * the v3 redesign so users can scan "what moved" without reading
 * the full design doc. Links into the relevant v3 surfaces inline.
 *
 * Structured by wave so each closeout commit has a corresponding
 * entry. New waves append at the top.
 */
export default function V3Changelog() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, paddingBottom: 32 }}>
      <div>
        <h1 style={{ fontSize: 24, fontWeight: 600, color: 'var(--v3-text)', margin: 0 }}>
          What's new in ExecuteSpec
        </h1>
        <p style={{ fontSize: 12, color: 'var(--v3-text-muted)', margin: '4px 0 0' }}>
          Every IA change in the v3 redesign, with deep-links to the
          new surfaces. Waves close every 1–2 weeks.
        </p>
      </div>

      <Wave title="Wave 3" date="2026-04-27" status="just shipped">
        <Item title="Install ExecuteSpec on your phone">
          The app is now a PWA. On iOS Safari, tap Share → "Add to
          Home Screen" — the icon launches the v3 shell standalone.
          Works offline for the shell + last-visited project.
        </Item>
        <Item title="Old bookmarks redirect">
          Flip the new <Link to="/v3/settings" style={ln}>Settings → Try the new ExecuteSpec everywhere</Link>{' '}
          toggle and your existing v2 URLs (<code>/dashboard</code>,{' '}
          <code>/billing</code>, <code>/integrations</code>, etc.) automatically
          land on their v3 equivalents.
        </Item>
        <Item title="Changelog">
          You're reading it. Linked from the footer of every v3 page.
        </Item>
      </Wave>

      <Wave title="Wave 2" date="2026-04-27" status="shipped">
        <Item title="Honest billing">
          <Link to="/v3/billing" style={ln}>/v3/billing</Link>{' '}
          reads from a single source of truth — the strict-review case
          where a Free label sat on an Enterprise org with a fake
          286%-over-budget banner is now structurally impossible.
        </Item>
        <Item title="Public pricing">
          <Link to="/v3/pricing" style={ln}>/v3/pricing</Link>{' '}
          — Free / Pro / Team grid with USD + INR toggles, monthly +
          annual options, and a 6-question FAQ. Enterprise sits in
          the footer link.
        </Item>
        <Item title="Free-tier nudge">
          Toast at lifetime balance ≤1, blocking modal at 0. Invite
          a friend for +5 (cap 25) — both of you get +5 once they
          ship their first run.
        </Item>
        <Item title="Connect a GitHub repo">
          Project switcher → "+ Connect a GitHub repo" opens an
          in-place popover instead of routing you to a v2 form.
          Pick a repo, we auto-name the project, drop you in the chat.
        </Item>
        <Item title="Integrations grid">
          <Link to="/v3/integrations" style={ln}>/v3/integrations</Link>{' '}
          — 8 cards across Source / Notifications / Ticketing /
          Incident response. Click → connect.
        </Item>
        <Item title="Team page">
          <Link to="/v3/team" style={ln}>/v3/team</Link>{' '}
          — members + seats + invite-by-email + (Enterprise) SSO panel.
        </Item>
        <Item title="Trust page">
          <Link to="/v3/trust" style={ln}>/v3/trust</Link>{' '}
          — public, scrollable, attachable to a vendor risk packet.
          SOC 2 status pill, sub-processor list, Trust Pack download.
        </Item>
        <Item title="Insights">
          <Link to="/v3/insights" style={ln}>/v3/insights</Link>{' '}
          — collapses the v2 Dashboard + Exec Summary + Admin Analytics
          into one screen. Spend by project, top wins, needs-attention.
        </Item>
        <Item title="Help via Cmd-K">
          Hit <code>?</code> anywhere (or <code>⌘K</code> + type{' '}
          <code>?</code>) for a glossary of every product term.
        </Item>
        <Item title="Mobile">
          Every v3 screen reflows cleanly at 390px. Tap-friendly
          composer, two-up tile grid on Insights, single-column
          pricing cards.
        </Item>
      </Wave>

      <Wave title="Wave 1" date="2026-04-27" status="shipped">
        <Item title="The home is a textarea">
          Signed-in users land in a chat-shaped project home. Type
          what you want to build; the result is a draft pull request
          with inline annotations.
        </Item>
        <Item title="Honest verdict, not a fake score">
          Every run shows Build · Tests · Reviewer · Cost as separate
          chips. The verified-score lie (where a no-tests run earned
          a free 10-point bonus) was patched at the API source —
          v2 surfaces benefit too.
        </Item>
        <Item title="One word for the primitive">
          "Run" everywhere. The v2 vocabulary drift (Mission · Spec ·
          Launch · Execution) is gone from v3 surfaces.
        </Item>
        <Item title="No left sidebar">
          Top bar with project switcher + ⌘K + avatar. Settings,
          billing, integrations, team — all reached from the avatar
          dropdown.
        </Item>
        <Item title="Anonymous trial">
          The marketing landing accepts a textarea submit without
          signup. (Real LLM dispatch is gated behind a feature flag
          while the anti-abuse story locks in.)
        </Item>
      </Wave>

      <footer style={{ textAlign: 'center', padding: '12px 0', fontSize: 11, color: 'var(--v3-text-subtle)' }}>
        Want to know more?{' '}
        <Link to="/v3/trust" style={{ color: 'var(--v3-text-subtle)' }}>Trust</Link>
        {' · '}
        <Link to="/v3/pricing" style={{ color: 'var(--v3-text-subtle)' }}>Pricing</Link>
        {' · '}
        <Link to="/v3/home" style={{ color: 'var(--v3-text-subtle)' }}>Home</Link>
      </footer>
    </div>
  );
}

const ln: React.CSSProperties = { color: 'var(--v3-brand)', textDecoration: 'none' };

function Wave({
  title,
  date,
  status,
  children,
}: {
  title: string;
  date: string;
  status: 'just shipped' | 'shipped' | 'in flight';
  children: React.ReactNode;
}) {
  const tone =
    status === 'just shipped' ? 'ok' : status === 'in flight' ? 'info' : 'neutral';
  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <header style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
        <h2 style={{ margin: 0, fontSize: 16, fontWeight: 600, color: 'var(--v3-text)' }}>
          {title}
        </h2>
        <span style={{ fontSize: 11, color: 'var(--v3-text-subtle)' }}>· {date}</span>
        <Pill tone={tone} size="sm">{status}</Pill>
      </header>
      <ul style={{ margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 8 }}>
        {children}
      </ul>
    </section>
  );
}

function Item({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <li
      style={{
        padding: '6px 8px 8px',
        borderLeft: '2px solid var(--v3-brand)',
        background: 'var(--v3-bg)',
        borderRadius: '0 4px 4px 0',
        fontSize: 12,
      }}
    >
      <div style={{ fontWeight: 600, color: 'var(--v3-text)', marginBottom: 2 }}>{title}</div>
      <div style={{ color: 'var(--v3-text-muted)', lineHeight: 1.55 }}>{children}</div>
    </li>
  );
}
