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
 * The right panel mirrors the protected Console shell so auth does not feel
 * like a separate marketing site.
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
              Start with the change, keep control at the gate.
            </h2>
            <p className='auth-brand-lede'>
              The app opens on Start. From there you pick context, review the plan, then let the
              worker produce a verified pull request.
            </p>
          </div>

          <div className='auth-console-mock'>
            <div className='auth-console-topbar'>
              <img src={MERGE_RAIL_MARK} alt='' />
              <span>ExecuteSpec</span>
              <span className='auth-console-state'>1 awaiting approval</span>
            </div>
            <div className='auth-console-tabs'>
              <span>Start</span>
              <span>Work</span>
              <span>Projects</span>
              <span>Setup</span>
            </div>
            <div className='auth-console-lines'>
              {[
                {
                  n: 1,
                  t: (
                    <>
                      <span className='auth-line-blue'>Request</span>
                      <span className='auth-line-muted'> · add billing webhook replay</span>
                    </>
                  ),
                },
                {
                  n: 2,
                  t: <span className='auth-line-teal'>Context · payments-service, web-console</span>,
                },
                {
                  n: 3,
                  t: <span className='auth-line-violet'>Plan gate · review cost and scope</span>,
                },
                {
                  n: 4,
                  t: (
                    <>
                      <span className='auth-line-teal'>code</span>
                      <span className='auth-line-muted'> · </span>
                      <span className='auth-line-teal'>test</span>
                      <span className='auth-line-muted'> · </span>
                      <span className='auth-line-blue'>pull request</span>
                    </>
                  ),
                },
                {n: 5, t: <span className='auth-line-muted'>Setup · team, providers, integrations</span>},
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
              Start work without learning a command surface
            </li>
            <li>
              <span className='dot blue' />
              Work is gated before code changes begin
            </li>
            <li>
              <span className='dot violet' />
              Setup is one place for team, providers, and integrations
            </li>
          </ul>
        </div>
      </aside>
    </div>
  );
}
