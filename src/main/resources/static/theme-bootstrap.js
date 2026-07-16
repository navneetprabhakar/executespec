(function () {
  try {
    var saved = localStorage.getItem('es:theme') || localStorage.getItem('signal:theme');
    var theme = saved === 'light' || saved === 'system' ? saved : 'dark';
    if (theme === 'system') {
      theme =
        window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
          ? 'dark'
          : 'light';
    }
    document.documentElement.setAttribute('data-theme', theme);
    document.documentElement.setAttribute('data-signal-theme', theme);
  } catch (e) {
    document.documentElement.setAttribute('data-theme', 'dark');
    document.documentElement.setAttribute('data-signal-theme', 'dark');
  }
})();
