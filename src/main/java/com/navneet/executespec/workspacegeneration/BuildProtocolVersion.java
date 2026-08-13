package com.navneet.executespec.workspacegeneration;

/** Immutable per-run BUILD protocol selected once at admission. */
public enum BuildProtocolVersion {
  LEGACY_V1(false),
  SHARED_KERNEL_V2(true),
  LEGACY_UNKNOWN(false);

  private final boolean sharedKernel;

  BuildProtocolVersion(boolean sharedKernel) {
    this.sharedKernel = sharedKernel;
  }

  public boolean sharedKernel() {
    return sharedKernel;
  }

  public static BuildProtocolVersion parse(String value) {
    if (value == null || value.isBlank()) return LEGACY_UNKNOWN;
    try {
      return valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("BUILD_PROTOCOL_VERSION_UNSUPPORTED:" + value, invalid);
    }
  }

  public void requireExecutable() {
    if (this == LEGACY_UNKNOWN) {
      throw new IllegalStateException("BUILD_PROTOCOL_VERSION_UNPINNED");
    }
  }
}
