/*
 * /benchmarks/ renderer — fetches /data/benchmarks.json (the single source of
 * truth for results; publishing a result = flipping `publishable` to true
 * there) and renders cards into <main data-benchmarks>. Zero dependencies.
 *
 * Anti-drift rules (docs/plans/website-refactor-benchmarks-2026-06-11.md §3.1):
 *  1. Only `publishable === true && mode === 'blind'` entries render as
 *     results — oracle-mode or unapproved numbers can never appear.
 *  2. Every card carries mode, slice, model, run date — no naked percentages.
 *  3. Derived numbers (resolved %, cost per resolved) are computed here,
 *     never hand-entered.
 *  4. `publishable: false` entries with a `status` string render as a
 *     name-only "pending" row — no numbers.
 */
(function () {
  var root = document.querySelector('[data-benchmarks]');
  if (!root) return;

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function renderFailure() {
    root.textContent = '';
    var p = el('p', 'bench-empty', 'Results could not be loaded right now. The raw data lives at ');
    var a = el('a', null, '/data/benchmarks.json');
    a.href = '/data/benchmarks.json';
    p.appendChild(a);
    p.appendChild(document.createTextNode('.'));
    root.appendChild(p);
  }

  function card(s) {
    var c = el('article', 'bench-card');
    var head = el('div', 'bench-card-head');
    head.appendChild(el('h3', 'bench-name', s.name));
    head.appendChild(el('span', 'bench-badge', String(s.mode).toUpperCase()));
    c.appendChild(head);

    var pct = Math.round((s.resolved / s.total) * 100);
    c.appendChild(el('div', 'bench-score mono', s.resolved + '/' + s.total + ' resolved (' + pct + '%)'));
    var bar = el('div', 'bench-bar');
    var fill = el('div', 'bench-bar-fill');
    fill.style.width = pct + '%';
    bar.appendChild(fill);
    c.appendChild(bar);

    var meta = [
      ['Model', s.model],
      ['Slice', s.slice],
      ['Run date', s.run_date],
      ['Total cost', typeof s.cost_total_usd === 'number' ? '$' + s.cost_total_usd.toFixed(2) : null],
      ['Cost / resolved', typeof s.cost_total_usd === 'number' && s.resolved > 0 ? '$' + (s.cost_total_usd / s.resolved).toFixed(3) : null],
      ['Wall clock', s.wall_clock],
      ['Harness', s.harness]
    ];
    var dl = el('dl', 'bench-meta');
    meta.forEach(function (row) {
      if (row[1] == null) return;
      dl.appendChild(el('dt', null, row[0]));
      dl.appendChild(el('dd', 'mono', row[1]));
    });
    c.appendChild(dl);

    if (s.baseline && typeof s.baseline.resolved === 'number') {
      c.appendChild(el('p', 'bench-baseline', s.baseline.label + ' ' + s.baseline.resolved + '/' + s.baseline.total + ' → pipeline ' + s.resolved + '/' + s.total));
    }
    if (s.notes) c.appendChild(el('p', 'bench-notes', s.notes));
    [['Evidence', s.evidence_url], ['Leaderboard', s.leaderboard_url]].forEach(function (lnk) {
      if (!lnk[1]) return;
      var a = el('a', 'bench-link', lnk[0] + ' ↗');
      a.href = lnk[1];
      a.rel = 'noopener';
      c.appendChild(a);
    });
    return c;
  }

  fetch('../data/benchmarks.json')
    .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
    .then(function (data) {
      var suites = (data && data.suites) || [];
      var published = suites.filter(function (s) {
        return s.publishable === true && s.mode === 'blind' &&
          typeof s.resolved === 'number' && typeof s.total === 'number' && s.total > 0;
      });
      var pending = suites.filter(function (s) { return s.publishable !== true && s.status; });

      root.textContent = '';
      var updated = document.querySelector('[data-benchmarks-updated]');
      if (updated && data && data.updated) updated.textContent = 'Updated ' + data.updated;

      if (published.length) {
        var grid = el('div', 'bench-grid');
        published.forEach(function (s) { grid.appendChild(card(s)); });
        root.appendChild(grid);
      } else {
        root.appendChild(el('p', 'bench-empty', 'No published results yet — the first runs are awaiting operator sign-off. Suites in progress are listed below; we list pending suites by name only, never as estimates.'));
      }
      if (pending.length) {
        root.appendChild(el('h3', 'bench-pending-title', 'In progress — numbers withheld until final'));
        var ul = el('ul', 'bench-pending');
        pending.forEach(function (s) { ul.appendChild(el('li', null, s.name + ' — ' + s.status)); });
        root.appendChild(ul);
      }
    })
    .catch(renderFailure);
})();
