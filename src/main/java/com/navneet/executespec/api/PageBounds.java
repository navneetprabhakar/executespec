package com.navneet.executespec.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared fail-closed validation for externally supplied page/size parameters. */
public final class PageBounds {

  private PageBounds() {}

  public static PageRequest request(int page, int size, int maxSize) {
    if (page < 0) {
      throw badRequest("page must be >= 0");
    }
    if (size < 1 || size > maxSize) {
      throw badRequest("size must be between 1 and " + maxSize);
    }
    // Spring Data ultimately converts page * size to a long offset. Reject inputs that cannot be
    // represented by the integer-indexed response adapters used by legacy merged-list endpoints.
    if ((long) page * size > Integer.MAX_VALUE) {
      throw badRequest("page offset is too large");
    }
    return PageRequest.of(page, size);
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
