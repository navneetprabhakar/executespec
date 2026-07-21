package com.navneet.executespec.specchat.policy;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Fail-closed denial from chat policy resolution or revalidation. */
public final class ProjectChatPolicyDeniedException extends ResponseStatusException {
  public ProjectChatPolicyDeniedException(String reason) {
    super(HttpStatus.FORBIDDEN, reason);
  }
}
