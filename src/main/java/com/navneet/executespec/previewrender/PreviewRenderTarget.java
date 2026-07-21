package com.navneet.executespec.previewrender;

/**
 * The UI technology a discovered surface is written in.
 *
 * <p>This drives both the render prompt and the honesty label shown in the UI. The two native
 * targets have no runtime mapping to the browser — the agent reads layout intent and approximates
 * it — so they are marked lower fidelity and must never be presented as a running app.
 */
public enum PreviewRenderTarget {

  /** React / Vue / Svelte / plain HTML. Renders close to the real thing. */
  WEB(Fidelity.CLOSE, 1280, 800),

  /**
   * React Native or Expo. React Native's layout primitives map onto flexbox with well-understood
   * semantics, so this is a faithful structural render inside a phone frame — but it is still the
   * web box model, not Yoga on a device.
   */
  REACT_NATIVE(Fidelity.STRUCTURAL, 390, 844),

  /** Kotlin Compose or Android XML layouts. Interpreted, not executed. */
  ANDROID_NATIVE(Fidelity.APPROXIMATE, 412, 915),

  /** SwiftUI or UIKit. Interpreted, not executed. */
  IOS_NATIVE(Fidelity.APPROXIMATE, 393, 852);

  /**
   * How much trust the rendered output earns. Surfaced verbatim to the operator so nobody mistakes
   * an interpreted Swift layout for a build artifact.
   */
  public enum Fidelity {
    /** Same rendering engine the real product uses. Differences come from stubbed data only. */
    CLOSE,
    /** Different engine, equivalent layout model. Structure is trustworthy; pixels are not. */
    STRUCTURAL,
    /** No shared engine. The agent inferred the layout from source. Treat as a sketch. */
    APPROXIMATE
  }

  private final Fidelity fidelity;
  private final int defaultWidth;
  private final int defaultHeight;

  PreviewRenderTarget(Fidelity fidelity, int defaultWidth, int defaultHeight) {
    this.fidelity = fidelity;
    this.defaultWidth = defaultWidth;
    this.defaultHeight = defaultHeight;
  }

  public Fidelity fidelity() {
    return fidelity;
  }

  public int defaultWidth() {
    return defaultWidth;
  }

  public int defaultHeight() {
    return defaultHeight;
  }

  /** True when the surface should be rendered inside a phone chrome rather than a browser frame. */
  public boolean isMobile() {
    return this != WEB;
  }
}
