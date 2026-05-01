package com.navneet.executespec.deployment.preview;

import lombok.Getter;

@Getter
public enum PreviewProjectType {
  NODE_FRONTEND(5173),
  NODE_BACKEND(3000),
  SPRING_BOOT(8080),
  JAVA(8080),
  PYTHON(8000),
  GO(8080),
  RUST(8080),
  FULL_STACK(5173), // primary preview port is the frontend
  STATIC_HTML(8080), // simple static files served via nginx/serve
  /**
   * React Native + Expo (managed workflow). v1 preview runs {@code expo export} in a builder stage
   * and serves the resulting {@code dist/} via nginx on port 8080 with a landing page explaining
   * the bundle. Full Expo Go QR pairing (Expo update protocol v2) is a v2 follow-up.
   */
  REACT_NATIVE_EXPO(8080);

  private final int containerPort;

  PreviewProjectType(int containerPort) {
    this.containerPort = containerPort;
  }
}
