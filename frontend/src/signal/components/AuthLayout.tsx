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

          {/* Console preview - mirrors the control-plane shell after sign-in. */}
          <div
            className='auth-console-mock'
            style={{
              borderRadius: 8,
              overflow: 'hidden',
              border: '1px solid rgba(255, 255, 255, 0.12)',
              background: '#141511',
              boxShadow: '0 18px 50px -22px rgba(0, 0, 0, 0.65)',
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '10px 12px',
                background: '#1d1e19',
                borderBottom: '1px solid rgba(255,255,255,0.08)',
              }}
            >
              <span
                style={{
                  width: 22,
                  height: 22,
                  borderRadius: 6,
                  display: 'grid',
                  placeItems: 'center',
                  background: '#d6fe51',
                  color: '#15170e',
                  fontWeight: 800,
                  fontSize: 12,
                }}
              >
                E
              </span>
              <span style={{fontSize: 12, color: '#f1f2ea', fontWeight: 700}}>
                ExecuteSpec Console
              </span>
              <span style={{marginLeft: 'auto', fontSize: 11, color: '#a0a395'}}>
                verified work
              </span>
            </div>
            <div style={{display: 'flex', background: '#20221c'}}>
              <span
                style={{
                  padding: '7px 14px',
                  fontSize: 11.5,
                  color: '#f1f2ea',
                  background: '#141511',
                  boxShadow: 'inset 0 2px 0 #d6fe51',
                }}
              >
                Mission control
              </span>
            </div>
            <div style={{padding: '12px 0', fontSize: 12.5, lineHeight: 1.75, color: '#dfe4d0'}}>
              {[
                {
                  n: 1,
                  t: (
                    <>
                      <span style={{color: '#d6fe51'}}>Awaiting approval</span>
                      <span style={{color: '#858b7a'}}> · 3 plans need a decision</span>
                    </>
                  ),
                },
                {
                  n: 2,
                  t: <span style={{color: '#7ce3b4'}}>Live work · 2 runs coding and testing</span>,
                },
                {
                  n: 3,
                  t: (
                    <span style={{color: '#9db7ff'}}>Project health · PRs, debt, architecture</span>
                  ),
                },
                {n: 4, t: <span />},
                {
                  n: 5,
                  t: (
                    <>
                      <span style={{color: '#7ce3b4'}}>plan</span>
                      <span style={{color: '#858585'}}> · </span>
                      <span style={{color: '#7ce3b4'}}>code</span>
                      <span style={{color: '#858585'}}> · </span>
                      <span style={{color: '#7ce3b4'}}>test</span>
                      <span style={{color: '#858585'}}> · </span>
                      <span style={{color: '#dcdcaa'}}>PR ✓</span>
                    </>
                  ),
                },
              ].map((r) => (
                <div key={r.n} style={{display: 'flex', gap: 14, padding: '0 14px'}}>
                  <span
                    style={{color: '#858585', width: 14, textAlign: 'right', userSelect: 'none'}}
                  >
                    {r.n}
                  </span>
                  <span>{r.t}</span>
                </div>
              ))}
            </div>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                padding: '8px 12px',
                background: '#20221c',
                borderTop: '1px solid rgba(255,255,255,0.08)',
                color: '#dfe4d0',
                fontSize: 11,
              }}
            >
              <span>Plan gate ready</span>
              <span style={{flex: 1}} />
              <span>Verified 94</span>
              <span>$0.42</span>
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
