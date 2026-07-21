package com.navneet.executespec.repo;

import java.util.List;

/** Typed source inventory; empty-success is distinct from authentication/transport failure. */
public record ScmSourceInventory(
    List<String> files,
    boolean successful,
    boolean truncated,
    String errorCode,
    Integer upstreamStatus) {
  public ScmSourceInventory {
    files = files == null ? List.of() : List.copyOf(files);
  }

  public static ScmSourceInventory success(List<String> files, boolean truncated) {
    return new ScmSourceInventory(files, true, truncated, null, 200);
  }

  public static ScmSourceInventory failed(String code, Integer status) {
    return new ScmSourceInventory(List.of(), false, false, code, status);
  }
}
