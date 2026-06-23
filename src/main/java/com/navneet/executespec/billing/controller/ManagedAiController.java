package com.navneet.executespec.billing.controller;

import java.util.List;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiBalanceResponse;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiPolicyResponse;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiUsageEventResponse;
import com.navneet.executespec.billing.service.ManagedAiUsageService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/billing/managed-ai")
@RequiredArgsConstructor
public class ManagedAiController {

  private final ManagedAiUsageService managedAiUsageService;

  @GetMapping("/balance")
  public ResponseEntity<ManagedAiBalanceResponse> balance(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    return ResponseEntity.ok(managedAiUsageService.balance(userDetails));
  }

  @GetMapping("/events")
  public ResponseEntity<List<ManagedAiUsageEventResponse>> events(
      @RequestParam(required = false, defaultValue = "current") String period,
      @RequestParam(required = false, defaultValue = "50") int limit,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (!"current".equalsIgnoreCase(period)) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(managedAiUsageService.currentEvents(userDetails, limit));
  }

  @GetMapping("/policy")
  public ResponseEntity<ManagedAiPolicyResponse> policy(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    return ResponseEntity.ok(managedAiUsageService.policy(userDetails));
  }
}
