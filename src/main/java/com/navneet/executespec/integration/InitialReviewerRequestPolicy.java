package com.navneet.executespec.integration;

import com.navneet.executespec.reviewer.ReviewerRequest;

/** Constructs the ordinary BUILD reviewer boundary without workspace mutation authority. */
final class InitialReviewerRequestPolicy {
  private InitialReviewerRequestPolicy() {}

  static ReviewerRequest.ReviewerRequestBuilder builder() {
    return ReviewerRequest.builder().readOnly(true);
  }
}
