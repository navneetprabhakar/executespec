import {ReactNode} from 'react';

const MERGE_RAIL_MARK = '/assets/brand/merge-rail/executespec-merge-rail-mark-light.svg';

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
              <img className='logo' src={MERGE_RAIL_MARK} alt='' />
              <span className='word'>ExecuteSpec</span>
            </div>
            <h2 className='auth-brand-headline'>
              Plan, code, review, and merge from one controlled rail.
            </h2>
            <p className='auth-brand-lede'>
              ExecuteSpec turns a requested change into a gated implementation path: scope the work,
              confirm the plan, watch execution, and merge with a verified score attached.
            </p>
          </div>

          <div className='auth-console-mock'>
            <div className='auth-console-topbar'>
              <img src={MERGE_RAIL_MARK} alt='' />
              <span>ExecuteSpec Console</span>
              <span className='auth-console-state'>verified work</span>
            </div>
            <div className='auth-console-tabs'>
              <span>Mission control</span>
            </div>
            <div className='auth-console-lines'>
              {[
                {
                  n: 1,
                  t: (
                    <>
                      <span className='auth-line-blue'>Awaiting approval</span>
                      <span className='auth-line-muted'> · 3 plans need a decision</span>
                    </>
                  ),
                },
                {
                  n: 2,
                  t: <span className='auth-line-teal'>Live work · 2 runs coding and testing</span>,
                },
                {
                  n: 3,
                  t: (
                    <span className='auth-line-violet'>
                      Project health · PRs, debt, architecture
                    </span>
                  ),
                },
                {n: 4, t: <span />},
                {
                  n: 5,
                  t: (
                    <>
                      <span className='auth-line-teal'>plan</span>
                      <span className='auth-line-muted'> · </span>
                      <span className='auth-line-teal'>code</span>
                      <span className='auth-line-muted'> · </span>
                      <span className='auth-line-teal'>test</span>
                      <span className='auth-line-muted'> · </span>
                      <span className='auth-line-blue'>PR ready</span>
                    </>
                  ),
                },
              ].map((r) => (
                <div key={r.n} className='auth-console-line'>
                  <span>{r.n}</span>
                  <span>{r.t}</span>
                </div>
              ))}
            </div>
            <div className='auth-console-footer'>
              <span>Plan gate ready</span>
              <span style={{flex: 1}} />
              <span>Verified 94</span>
              <span>$0.42</span>
            </div>
          </div>

          <ul className='auth-brand-bullets'>
            <li>
              <span className='dot teal' />
              Real PRs, not diff suggestions
            </li>
            <li>
              <span className='dot blue' />
              You approve the plan and price first
            </li>
            <li>
              <span className='dot violet' />
              Deterministic verified score on every PR
            </li>
          </ul>
        </div>
      </aside>
    </div>
  );
}
