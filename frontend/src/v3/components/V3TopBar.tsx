import { Link } from 'react-router-dom';

/**
 * R-101 — top-bar placeholder.
 *
 * R-102 fills in the project switcher (centre) and avatar menu (right).
 * R-103 wires the ⌘K button. For now the placeholders are visible
 * dashed-outline chips so the route shell is honest about what's
 * coming and what isn't.
 */
export default function V3TopBar() {
  return (
    <header className="v3-topbar" role="banner">
      <Link to="/v3/home" className="v3-topbar__brand" aria-label="ExecuteSpec home">
        <span className="v3-topbar__brand-mark" aria-hidden="true">
          ES
        </span>
        ExecuteSpec
      </Link>
      <div className="v3-topbar__center">
        <span className="v3-topbar__placeholder" aria-hidden="true">
          project switcher · R-124
        </span>
      </div>
      <div className="v3-topbar__right">
        <span className="v3-topbar__placeholder" aria-hidden="true">
          ⌘K · R-103
        </span>
        <span className="v3-topbar__placeholder" aria-hidden="true">
          avatar · R-104
        </span>
      </div>
    </header>
  );
}
