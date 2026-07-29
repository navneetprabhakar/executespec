package com.navneet.executespec.repositoryintel;

/** Fail-closed repository authority/materialization refusal with a secret-free reason. */
public final class RepositoryAuthorityException extends RuntimeException {

  public enum Reason {
    ACTOR_UNAVAILABLE,
    ACTOR_INACTIVE,
    ACTOR_MEMBERSHIP_UNAVAILABLE,
    TENANT_UNAVAILABLE,
    TENANT_INACTIVE,
    PROJECT_UNAVAILABLE,
    PROJECT_IDENTITY_MISMATCH,
    PROJECT_INACTIVE,
    PROJECT_ACCESS_DENIED,
    TENANT_MISMATCH,
    REPOSITORY_ID_INVALID,
    REPOSITORY_UNAVAILABLE,
    REPOSITORY_IDENTITY_MISMATCH,
    REPOSITORY_NOT_LINKED,
    REPOSITORY_OWNER_MISMATCH,
    REPOSITORY_OWNER_INACTIVE,
    REPOSITORY_SOURCE_UNSUPPORTED,
    REPOSITORY_NOT_READY,
    SNAPSHOT_SOURCE_MISMATCH,
    SNAPSHOT_DIRTY_WORKTREE,
    MATERIALIZATION_UNAVAILABLE,
    MATERIALIZATION_OUTSIDE_WORKSPACE,
    MATERIALIZATION_SYMLINK_REJECTED,
    MATERIALIZATION_NOT_REPOSITORY,
    HANDLE_IDENTITY_MISMATCH
  }

  private final Reason reason;

  public RepositoryAuthorityException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public RepositoryAuthorityException(Reason reason, Throwable cause) {
    super(reason.name(), cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
