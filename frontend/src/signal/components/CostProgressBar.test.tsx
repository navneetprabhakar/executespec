import {renderToStaticMarkup} from 'react-dom/server';
import {describe, expect, it} from 'vitest';
import {CostProgressBar} from './CostProgressBar';

describe('CostProgressBar', () => {
  it('shows the raw over-cap percentage and amount while clamping only the fill', () => {
    const html = renderToStaticMarkup(<CostProgressBar spent={5.16534} cap={5} />);

    expect(html).toContain('$5.1653 / $5.0000 · 103%');
    expect(html).toContain('Exceeded by $0.1653');
    expect(html).toContain('aria-valuenow="100"');
    expect(html).toContain('aria-valuetext="103%, exceeded by $0.1653"');
    expect(html).toContain('width:100%');
  });

  it('does not show an overage below the cap', () => {
    const html = renderToStaticMarkup(<CostProgressBar spent={4} cap={5} />);

    expect(html).toContain('$4.0000 / $5.0000 · 80%');
    expect(html).not.toContain('Exceeded by');
    expect(html).toContain('width:80%');
  });
});
