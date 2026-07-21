package com.navneet.executespec.previewrender;

import java.util.List;

/**
 * One renderable screen discovered in a run workspace.
 *
 * <p>Discovery is deterministic — no model decides what is a screen. The agent only ever sees
 * surfaces this record describes, which keeps the render bounded and reproducible.
 *
 * @param id stable slug derived from the path; used as the URL segment for the rendered bundle
 * @param path workspace-relative path of the screen file
 * @param displayName human label for the surface switcher
 * @param target UI technology, which selects the prompt variant and viewport
 * @param source full text of the screen file
 * @param supporting related local sources (child components, theme/token files) the render needs
 * @param score discovery confidence that this is a top-level screen rather than a leaf component
 */
public record PreviewSurface(
    String id,
    String path,
    String displayName,
    PreviewRenderTarget target,
    String source,
    List<SupportingSource> supporting,
    int score) {

  /** A local file pulled in to give the render agent enough context to lay the screen out. */
  public record SupportingSource(String path, String content) {}

  /** Total characters this surface contributes to the prompt. Used to enforce the token budget. */
  public int characterCost() {
    int total = source == null ? 0 : source.length();
    for (SupportingSource s : supporting) {
      total += s.content() == null ? 0 : s.content().length();
    }
    return total;
  }
}
