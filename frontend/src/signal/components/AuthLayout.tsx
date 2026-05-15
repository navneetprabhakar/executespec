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
              AI that ships <em>pull requests</em> your team actually merges.
            </h2>
            <p className='auth-brand-lede'>
              Write a one-line spec. We plan it, code it, test it, and open a PR with a verdict attached. <strong>You merge it.</strong>
            </p>
          </div>

          <div className='auth-preview-card'>
            <div className='preview-ribbon'>
              <span className='blink' />
              Running now · R-4429
            </div>
            <div className='preview-title'>Refactor session-store to Redis</div>
            <div className='preview-bar'>
              <div className='preview-bar-fill' />
            </div>
            <div className='preview-stats'>
              <div>
                <div className='v signal'>3/4</div>
                <div className='l'>tasks</div>
              </div>
              <div>
                <div className='v'>14m</div>
                <div className='l'>elapsed</div>
              </div>
              <div>
                <div className='v mint'>$0.42</div>
                <div className='l'>spent</div>
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
              Plan-gate before any LLM tokens spend
            </li>
            <li>
              <span className='dot coral' />
              BYOK bypasses the cost cap
            </li>
          </ul>
        </div>
      </aside>
    </div>
  );
}
