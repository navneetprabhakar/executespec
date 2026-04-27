import { Link } from 'react-router-dom';
import { useV3Ui } from '../stores/useV3Ui';
import AvatarMenu from './AvatarMenu';
import ProjectSwitcher from './ProjectSwitcher';

/**
 * R-101 — top-bar shell.
 * R-103 — wires the ⌘K button to the command palette.
 *
 * R-104/R-124 fill in the avatar menu + project switcher; the
 * placeholder chips are still here for those.
 */
export default function V3TopBar() {
  const openCmdK = useV3Ui((s) => s.openCmdK);

  return (
    <header className="v3-topbar" role="banner">
      <Link to="/v3/home" className="v3-topbar__brand" aria-label="ExecuteSpec home">
        <span className="v3-topbar__brand-mark" aria-hidden="true">
          ES
        </span>
        ExecuteSpec
      </Link>
      <div className="v3-topbar__center">
        <ProjectSwitcher />
      </div>
      <div className="v3-topbar__right">
        <button
          type="button"
          onClick={openCmdK}
          aria-label="Open command palette (⌘K)"
          title="Search & actions (⌘K)"
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            padding: '4px 10px',
            background: 'var(--v3-surface)',
            color: 'var(--v3-text-muted)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            cursor: 'pointer',
            fontSize: 12,
            fontFamily: 'inherit',
          }}
        >
          <span style={{ fontSize: 11 }}>Search</span>
          <kbd
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              padding: '0 5px',
              height: 18,
              borderRadius: 4,
              background: 'var(--v3-bg)',
              border: '1px solid var(--v3-border)',
              fontSize: 10,
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
              color: 'var(--v3-text-muted)',
            }}
          >
            ⌘K
          </kbd>
        </button>
        <AvatarMenu />
      </div>
    </header>
  );
}
