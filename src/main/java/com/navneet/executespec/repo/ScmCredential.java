package com.navneet.executespec.repo;

/** Ephemeral credential passed only to an SCM transport. Never serialize or persist this record. */
public record ScmCredential(String username, String secret) {
  public boolean usable() {
    return secret != null && !secret.isBlank();
  }

  @Override
  public String toString() {
    return "ScmCredential[username=" + (username == null ? "" : username) + ", secret=<redacted>]";
  }
}
