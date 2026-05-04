import {useEffect} from 'react';

/**
 * On iOS Safari, the soft keyboard pushes the viewport up but doesn't scroll
 * the focused input into view reliably. This hook uses the VisualViewport API
 * to detect keyboard appearance and scroll the active element into view.
 *
 * Only activates on touch devices (no-op on desktop).
 */
export function useKeyboardScroll() {
  useEffect(() => {
    // Skip on desktop or if VisualViewport API is not available
    if (!('ontouchstart' in window) || !window.visualViewport) return;

    // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
    const vv = window.visualViewport!;

    let lastHeight = vv.height;

    const handleResize = () => {
      const currentHeight = vv.height;
      const heightDiff = lastHeight - currentHeight;

      // Keyboard opened (viewport shrank by > 100px)
      if (heightDiff > 100) {
        // Give the browser a tick to finish layout, then scroll focused element into view
        requestAnimationFrame(() => {
          const el = document.activeElement;
          if (
            el &&
            (el instanceof HTMLInputElement ||
              el instanceof HTMLTextAreaElement ||
              el instanceof HTMLSelectElement)
          ) {
            el.scrollIntoView({behavior: 'smooth', block: 'center'});
          }
        });
      }

      lastHeight = currentHeight;
    };

    vv.addEventListener('resize', handleResize);
    return () => vv.removeEventListener('resize', handleResize);
  }, []);
}
