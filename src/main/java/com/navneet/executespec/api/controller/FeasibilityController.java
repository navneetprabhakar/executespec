package com.navneet.executespec.api.controller;

import com.navneet.executespec.api.dto.FeasibilityReport;
import com.navneet.executespec.api.dto.FeasibilityRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

/**
 * Feasibility estimation endpoint — pre-run cost preview driven by V1 PlannerAgent.
 *
 * <p>Retired with deprecation P3. V2 uses {@code RunCostTracker.estimateRunTierAware} at plan
 * approval time to populate {@code cost_estimate_low/high} + {@code creditsCharged} on the run row
 * itself, which is what the UI now reads. Kept as a 410 Gone so the frontend's feasibility
 * estimator card can render a clear "no longer supported" state until it is removed.
 */
@RestController
@RequestMapping("/api/feasibility")
@Slf4j
public class FeasibilityController {

  @PostMapping
  public ResponseEntity<FeasibilityReport> estimate(@RequestBody FeasibilityRequest req) {
    throw new ResponseStatusException(
        HttpStatus.GONE,
        "Feasibility endpoint retired — submit a run to get an actual cost estimate at plan"
            + " approval.");
  }
}
