package com.navneet.executespec.continuationfixture;

import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Provider-free Web/CLI continuation fixture. This is operator test infrastructure, never a
 * customer run-creation surface.
 */
@Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v2/test/continuation-fixtures")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class ContinuationFixtureController {

  private final ContinuationFixtureService service;

  public record StartRequest(ContinuationFixtureScenario scenario) {}

  @PostMapping
  public ContinuationFixtureService.FixtureResult start(
      @AuthenticationPrincipal AppUserDetails actor, @RequestBody StartRequest request) {
    if (actor == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
    }
    if (request == null || request.scenario() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scenario required");
    }
    return service.create(request.scenario(), actor);
  }
}
