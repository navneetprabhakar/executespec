// ExecuteSpec marketing site — shared page behavior.
// Extracted from index.html's inline bottom <script> in the 2026-06-11
// website refactor (Phase 1). Loaded with `defer`, so it runs after the
// DOM is parsed (same timing as the old end-of-body inline script).
// Currently: the USD/INR currency toggle on the pricing grid.
(function () {
  var grid = document.querySelector('.pricing-grid');
  var btns = document.querySelectorAll('.cur-toggle button');
  if (!grid || !btns.length) return;
  btns.forEach(function (b) {
    b.addEventListener('click', function () {
      grid.classList.toggle('show-inr', b.getAttribute('data-cur') === 'inr');
      btns.forEach(function (x) { x.classList.toggle('active', x === b); });
    });
  });
})();
