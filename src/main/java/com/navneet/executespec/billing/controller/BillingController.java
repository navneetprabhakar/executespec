package com.navneet.executespec.billing.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.dto.*;
import com.navneet.executespec.billing.entity.TopupPackage;
import com.navneet.executespec.billing.service.BillingService;
import com.navneet.executespec.billing.service.RunTopupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Slf4j
public class BillingController {

    private final BillingService billingService;
    private final RunTopupService runTopupService;

    /**
     * GET /api/billing/plans
     * List all active plans for the pricing page. Public-ish (requires auth but no specific role).
     */
    @GetMapping("/plans")
    public ResponseEntity<List<PlanResponse>> listPlans(
            @RequestParam(required = false) String keyMode) {
        return ResponseEntity.ok(billingService.listPlans(keyMode));
    }

    /**
     * GET /api/billing/status
     * Current subscription + plan + usage for the authenticated user.
     */
    @GetMapping("/status")
    public ResponseEntity<BillingStatusResponse> getStatus(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        return ResponseEntity.ok(billingService.getStatus(userDetails.getUser().getId()));
    }

    /**
     * POST /api/billing/subscribe
     * Initiate a subscription. Returns Razorpay checkout data.
     */
    @PostMapping("/subscribe")
    public ResponseEntity<SubscriptionInitResult> subscribe(
            @RequestBody CreateSubscriptionRequest request,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        SubscriptionInitResult result = billingService.subscribe(
                userDetails.getUser().getId(), request.planId(),
                request.gateway(), request.successUrl(), request.cancelUrl());
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/billing/verify
     * Verify payment signature after Razorpay checkout completes.
     * Activates subscription and upgrades tier on success.
     */
    @PostMapping("/verify")
    public ResponseEntity<BillingStatusResponse> verify(
            @RequestBody VerifyPaymentRequest request,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        BillingStatusResponse status = billingService.verifyAndActivate(
                userDetails.getUser().getId(), request);
        return ResponseEntity.ok(status);
    }

    /**
     * POST /api/billing/cancel
     * Cancel subscription at end of current billing period.
     */
    @PostMapping("/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal AppUserDetails userDetails) {
        billingService.cancel(userDetails.getUser().getId());
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/billing/seats
     * Update seat count (Pro tier only). Takes effect at next billing cycle.
     */
    @PostMapping("/seats")
    public ResponseEntity<BillingStatusResponse> updateSeats(
            @RequestBody UpdateSeatsRequest request,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        BillingStatusResponse status = billingService.updateSeats(
                userDetails.getUser().getId(), request.totalSeats());
        return ResponseEntity.ok(status);
    }

    // ── Run Top-Up Endpoints ────────────────────────────────────────────────────

    /**
     * GET /api/billing/topup/packages
     * List available top-up packages for purchase.
     */
    @GetMapping("/topup/packages")
    public ResponseEntity<List<TopupPackage>> listTopupPackages() {
        return ResponseEntity.ok(runTopupService.getPackages());
    }

    /**
     * POST /api/billing/topup/purchase
     * Initiate a top-up purchase. Returns Razorpay order data for checkout.
     */
    @PostMapping("/topup/purchase")
    public ResponseEntity<TopupOrderInitResult> purchaseTopup(
            @RequestBody TopupPurchaseRequest request,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        TopupOrderInitResult result = runTopupService.initiatePurchase(
                userDetails.getUser().getId(), request.packageId());
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/billing/topup/verify
     * Verify payment after Razorpay checkout. Credits runs on success.
     */
    @PostMapping("/topup/verify")
    public ResponseEntity<BillingStatusResponse> verifyTopup(
            @RequestBody TopupVerifyRequest request,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        String userId = userDetails.getUser().getId();
        runTopupService.verifyAndCredit(userId,
                request.razorpayOrderId(), request.razorpayPaymentId(), request.razorpaySignature());
        return ResponseEntity.ok(billingService.getStatus(userId));
    }
}
