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

          {/* VS Code-style editor mock — previews the IDE workspace the user
              is signing into (the product is IDE-only since 2026-05-22). Hard-
              coded Dark+ palette so it reads as a real editor regardless of the
              auth page's token context. */}
          <div
            className='auth-ide-mock'
            style={{
              borderRadius: 12,
              overflow: 'hidden',
              border: '1px solid rgba(255, 255, 255, 0.1)',
              background: '#1e1e1e',
              boxShadow: '0 18px 50px -22px rgba(0, 0, 0, 0.65)',
              fontFamily: "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace",
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 7,
                padding: '8px 12px',
                background: '#323233',
              }}
            >
              <span style={{width: 11, height: 11, borderRadius: 999, background: '#ff5f57'}} />
              <span style={{width: 11, height: 11, borderRadius: 999, background: '#febc2e'}} />
              <span style={{width: 11, height: 11, borderRadius: 999, background: '#28c840'}} />
              <span style={{marginLeft: 8, fontSize: 11.5, color: '#9d9d9d'}}>
                spec.md — ExecuteSpec
              </span>
            </div>
            <div style={{display: 'flex', background: '#252526'}}>
              <span
                style={{
                  padding: '6px 16px',
                  fontSize: 11.5,
                  color: '#ffffff',
                  background: '#1e1e1e',
                  boxShadow: 'inset 0 2px 0 #007acc',
                }}
              >
                spec.md
              </span>
            </div>
            <div style={{padding: '12px 0', fontSize: 12.5, lineHeight: 1.75, color: '#cccccc'}}>
              {[
                {
                  n: 1,
                  t: (
                    <>
                      <span style={{color: '#569cd6'}}># </span>
                      <span style={{color: '#9cdcfe'}}>Add a /health endpoint</span>
                    </>
                  ),
                },
                {n: 2, t: <span style={{color: '#6a9955'}}>returns build SHA + uptime,</span>},
                {n: 3, t: <span style={{color: '#6a9955'}}>with a unit test.</span>},
                {n: 4, t: <span />},
                {
                  n: 5,
                  t: (
                    <>
                      <span style={{color: '#4ec9b0'}}>→ plan</span>
                      <span style={{color: '#858585'}}> · </span>
                      <span style={{color: '#4ec9b0'}}>code</span>
                      <span style={{color: '#858585'}}> · </span>
                      <span style={{color: '#4ec9b0'}}>test</span>
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
                padding: '5px 12px',
                background: '#007acc',
                color: '#ffffff',
                fontSize: 11,
              }}
            >
              <span>⎇ main</span>
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
