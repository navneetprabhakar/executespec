import {ReactNode} from 'react';

/**
 * AuthLayout — responsive shell for splash-style auth pages.
 *
 *   Mobile (<= 920 px): single column · form fills the viewport · brand
 *     panel hidden (the form already carries the brand strip at top).
 *   Desktop (> 920 px): 2-column · form scrolls on the left, a brand +
 *     preview panel sits on the right.
 *
 * The right panel is decorative + reinforces the value prop. It shows
 * a small live-run preview card that mirrors what the user will see in
 * the app once they're signed in.
 */
export default function AuthLayout({children}: {children: ReactNode}) {
  return (
    <div className='signal-auth-layout'>
      <div className='auth-form-col'>
        <div className='auth-form-inner'>{children}</div>
      </div>
      <aside className='auth-brand-col' aria-hidden='true'>
        <div className='auth-brand-inner'>
          <div className='auth-brand-top'>
            <div className='brand-line'>
              <span className='logo'>E</span>
              <span className='word'>ExecuteSpec</span>
            </div>
            <h2 className='auth-brand-headline'>
              An <em>agentic</em> engineer that ships PRs your team can actually merge.
            </h2>
            <p className='auth-brand-lede'>
              Describe the change. ExecuteSpec classifies, plans, codes across one repo or twenty,
              runs your tests, files the PR — and attaches a verified score so you know whether to
              merge before you open the diff.
            </p>
          </div>

          <div className='auth-console-mock'>
            <div className='auth-console-top'>
              <span>Mission control</span>
              <strong>New work</strong>
            </div>
            <div className='auth-console-body'>
              <div className='auth-console-row hot'>
                <span>Awaiting decisions</span>
                <strong>2</strong>
              </div>
              <div className='auth-console-row'>
                <span>Live work</span>
                <strong>4</strong>
              </div>
              <div className='auth-console-run'>
                <span className='auth-console-dot' />
                <div>
                  <strong>Add health endpoint</strong>
                  <span>Plan gate · verified estimate ready</span>
                </div>
              </div>
              <div className='auth-console-run'>
                <span className='auth-console-dot green' />
                <div>
                  <strong>Refactor billing webhook</strong>
                  <span>PR opened · score 94 · $0.42</span>
                </div>
              </div>
            </div>
          </div>

          <ul className='auth-brand-bullets'>
            <li>
              <span className='dot mint' />
              Real PRs, not diff suggestions
            </li>
            <li>
              <span className='dot signal' />
              You approve the plan — and the price — first
            </li>
            <li>
              <span className='dot coral' />
              Deterministic verified score on every PR
            </li>
          </ul>
        </div>
      </aside>
    </div>
  );
}
