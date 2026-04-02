package com.navneet.executespec.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.billing.dto.*;
import com.navneet.executespec.billing.entity.*;
import com.navneet.executespec.billing.exception.BillingException;
import com.navneet.executespec.billing.gateway.BillingGateway;
import com.navneet.executespec.billing.gateway.PaymentGatewayRouter;
import com.navneet.executespec.billing.repository.*;
import com.navneet.executespec.billing.security.WebhookSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class BillingService {

    private final BillingGateway razorpayGateway;
    private final BillingPlanRepository planRepo;
    private final BillingPlanLimitRepository limitRepo;
    private final BillingSubscriptionRepository subRepo;
    private final BillingWebhookEventRepository webhookRepo;
    private final UsageTrackingService usageService;
    private final WebhookSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;

    // EXISTING repositories
    private final AppUserRepository userRepo;
    private final OrgRepository orgRepo;

    // Notification service (V1.1)
    private final BillingNotificationService notificationService;

    // Run top-up (V1.2)
    private final RunTopupRepository runTopupRepo;

    // Invoicing (V1.3)
    private final InvoiceService invoiceService;

    // Email service for welcome/upgrade emails
    private final MailService mailService;

    // Multi-gateway router (Stripe + Razorpay)
    private final PaymentGatewayRouter gatewayRouter;

    // Billing entity resolver (user vs org)
    private final com.navneet.executespec.billing.gateway.BillingEntityResolver billingEntityResolver;

    // Payment audit trail
    private final com.navneet.executespec.auth.service.PaymentAuditService paymentAuditService;

    @Value("${billing.terms-version:2026-03-v1}")
    private String currentTermsVersion;

    @Value("${billing.founder-pricing.max-slots:100}")
    private int founderMaxSlots;

    // ─────────────────────────────────────────────────────────────
    // LIST PLANS (for pricing page)
    // ─────────────────────────────────────────────────────────────

    public List<PlanResponse> listPlans(String keyMode) {
        List<BillingPlan> plans;
        if (keyMode != null && !keyMode.isBlank()) {
            plans = planRepo.findByActiveTrueAndKeyMode(keyMode);
        } else {
            plans = planRepo.findByActiveTrue();
        }
        return plans.stream()
                .map(p -> PlanResponse.from(p, limitRepo.findByBillingPlanId(p.getId())))
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────
    // CREATE SUBSCRIPTION
    // ─────────────────────────────────────────────────────────────

    public SubscriptionInitResult subscribe(String userId, UUID planId) {
        return subscribe(userId, planId, null, null, null);
    }

    public SubscriptionInitResult subscribe(String userId, UUID planId,
                                              String preferredGateway, String successUrl, String cancelUrl) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        BillingPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found"));

        if (!plan.isActive()) {
            throw new BillingException("This plan is no longer available.");
        }

        if ("FREE".equals(plan.getTier())) {
            throw new BillingException("Cannot subscribe to the free plan via checkout.");
        }

        // Check for existing active subscription
        subRepo.findActiveByUserId(userId).ifPresent(existing -> {
            if (!"FREE".equals(existing.getStatus())) {
                throw new BillingException("You already have an active subscription. Cancel it first.");
            }
        });

        // Check founder pricing availability
        boolean isFounder = false;
        if (plan.isFounderPricing()) {
            long currentFounders = subRepo.countActiveFounderSubscriptions();
            if (currentFounders >= founderMaxSlots) {
                throw new BillingException("Founder pricing is no longer available (all slots taken).");
            }
            isFounder = true;
        }

        // Resolve billing entity and gateway
        var billingEntity = billingEntityResolver.resolve(user);
        String gatewayName = preferredGateway != null ? preferredGateway.toLowerCase()
                : (billingEntity.getPaymentGateway() != null ? billingEntity.getPaymentGateway() : null);
        var gateway = gatewayName != null ? gatewayRouter.resolveByName(gatewayName) : gatewayRouter.resolve(billingEntity);

        SubscriptionInitResult result;

        if ("STRIPE".equalsIgnoreCase(gateway.gatewayType())) {
            // Stripe: create checkout session
            var checkout = gateway.createCheckoutSession(
                    billingEntity, plan.getId().toString(),
                    successUrl != null ? successUrl : "/billing?status=success",
                    cancelUrl != null ? cancelUrl : "/billing?status=cancelled");

            result = new SubscriptionInitResult(
                    checkout.sessionId(), null, null, null,
                    "STRIPE", checkout.url(), checkout.sessionId());

            // Persist subscription with status CREATED
            BillingSubscription sub = BillingSubscription.builder()
                    .userId(userId)
                    .orgId(user.getOrgId())
                    .billingPlanId(plan.getId())
                    .paymentGateway("STRIPE")
                    .gatewaySubscriptionId(checkout.sessionId())
                    .status("CREATED")
                    .totalSeats(plan.getIncludedSeats())
                    .extraSeats(0)
                    .cancelAtPeriodEnd(false)
                    .termsAcceptedAt(Instant.now())
                    .termsVersion(currentTermsVersion)
                    .founderPricing(isFounder)
                    .build();
            subRepo.save(sub);

        } else {
            // Razorpay: use legacy gateway
            String userName = user.getFirstName() + " " + user.getLastName();
            result = razorpayGateway.createSubscription(
                    userId, userName, user.getEmail(), user.getOrgId(), plan);

            // Persist subscription with status CREATED
            BillingSubscription sub = BillingSubscription.builder()
                    .userId(userId)
                    .orgId(user.getOrgId())
                    .billingPlanId(plan.getId())
                    .paymentGateway("RAZORPAY")
                    .gatewaySubscriptionId(result.gatewaySubscriptionId())
                    .gatewayCustomerId(result.gatewayCustomerId())
                    .status("CREATED")
                    .totalSeats(plan.getIncludedSeats())
                    .extraSeats(0)
                    .cancelAtPeriodEnd(false)
                    .termsAcceptedAt(Instant.now())
                    .termsVersion(currentTermsVersion)
                    .founderPricing(isFounder)
                    .build();
            subRepo.save(sub);
        }

        log.info("Subscription created: user={}, plan={}, gateway={}",
                userId, plan.getName(), gateway.gatewayType());

        paymentAuditService.record("USER", userId, "SUBSCRIPTION_CREATED",
                user.getTier(), plan.getTier(), userId, "Plan: " + plan.getName(),
                gateway.gatewayType(), null, null, null, null, userId, user.getOrgId());

        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // VERIFY PAYMENT (post-checkout callback)
    // ─────────────────────────────────────────────────────────────

    public BillingStatusResponse verifyAndActivate(String userId, VerifyPaymentRequest request) {
        if (!razorpayGateway.verifyPayment(request)) {
            log.warn("Invalid payment signature: userId={}, subId={}",
                    userId, request.razorpaySubscriptionId());
            throw new SecurityException("Invalid payment signature. Payment verification failed.");
        }

        BillingSubscription sub = subRepo.findByGatewaySubscriptionId(request.razorpaySubscriptionId())
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));

        // Verify this subscription belongs to the authenticated user
        if (!sub.getUserId().equals(userId)) {
            throw new SecurityException("Subscription does not belong to this user.");
        }

        BillingPlan plan = planRepo.findById(sub.getBillingPlanId())
                .orElseThrow(() -> new IllegalStateException("Plan not found for subscription"));

        // Activate
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodStart(Instant.now());
        sub.setCurrentPeriodEnd(computePeriodEnd(plan.getPeriod()));
        subRepo.save(sub);

        // *** CRITICAL: Bridge to existing auth system ***
        upgradeTier(sub.getUserId(), sub.getOrgId(), plan);

        log.info("Subscription activated: user={}, plan={}, tier={}, entityId={}",
                userId, plan.getName(), plan.getTier(), sub.getGatewaySubscriptionId());

        paymentAuditService.record("USER", userId, "SUBSCRIPTION_ACTIVATED",
                null, plan.getTier(), userId, "Plan: " + plan.getName(),
                sub.getPaymentGateway(), null, request.razorpayPaymentId(),
                "INR", null, userId, sub.getOrgId());

        // V1.1: Notify user
        AppUser activatedUser = userRepo.findById(userId).orElse(null);
        if (activatedUser != null) {
            notificationService.sendSubscriptionActivated(activatedUser.getEmail(), plan.getName());
        }

        // V1.3: Generate invoice
        try {
            invoiceService.generateSubscriptionInvoice(sub, plan, request.razorpayPaymentId());
        } catch (Exception e) {
            log.warn("Invoice generation failed for subscription activation (non-blocking): {}", e.getMessage());
        }

        return getStatus(userId);
    }

    // ─────────────────────────────────────────────────────────────
    // CANCEL SUBSCRIPTION
    // ─────────────────────────────────────────────────────────────

    public void cancel(String userId) {
        BillingSubscription sub = subRepo.findActiveByUserId(userId)
                .orElseThrow(() -> new BillingException("No active subscription to cancel."));

        if ("FREE".equals(sub.getStatus())) {
            throw new BillingException("Cannot cancel the free tier.");
        }

        if (sub.getGatewaySubscriptionId() != null) {
            String gw = sub.getPaymentGateway();
            if ("STRIPE".equalsIgnoreCase(gw)) {
                gatewayRouter.stripe().cancelSubscription(sub.getGatewaySubscriptionId());
            } else {
                razorpayGateway.cancelAtPeriodEnd(sub.getGatewaySubscriptionId());
            }
        }

        sub.setCancelAtPeriodEnd(true);
        sub.setCancelledAt(Instant.now());
        subRepo.save(sub);

        log.info("Subscription cancelled (at period end): user={}, periodEnd={}",
                userId, sub.getCurrentPeriodEnd());

        BillingPlan cancelledPlan = planRepo.findById(sub.getBillingPlanId()).orElse(null);
        paymentAuditService.record("USER", userId, "SUBSCRIPTION_CANCELLED",
                cancelledPlan != null ? cancelledPlan.getTier() : null, "FREE",
                userId, "Cancels at period end: " + sub.getCurrentPeriodEnd(),
                sub.getPaymentGateway(), null, null, null, null, userId, sub.getOrgId());

        // V1.1: Notify user
        AppUser cancelledUser = userRepo.findById(userId).orElse(null);
        BillingPlan cancelPlan = planRepo.findById(sub.getBillingPlanId()).orElse(null);
        if (cancelledUser != null) {
            notificationService.sendCancellationConfirmed(
                    cancelledUser.getEmail(),
                    cancelPlan != null ? cancelPlan.getName() : "",
                    sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd().toString() : "N/A");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UPDATE SEATS (Pro tier only, takes effect next cycle)
    // ─────────────────────────────────────────────────────────────

    public BillingStatusResponse updateSeats(String userId, int newTotalSeats) {
        BillingSubscription sub = subRepo.findActiveByUserId(userId)
                .orElseThrow(() -> new BillingException("No active subscription."));

        BillingPlan plan = planRepo.findById(sub.getBillingPlanId()).orElseThrow();

        if (newTotalSeats < plan.getIncludedSeats()) {
            throw new BillingException("Cannot reduce below included seats (" + plan.getIncludedSeats() + ").");
        }

        int extraSeats = newTotalSeats - plan.getIncludedSeats();
        sub.setTotalSeats(newTotalSeats);
        sub.setExtraSeats(extraSeats);
        subRepo.save(sub);

        log.info("Seats updated: user={}, totalSeats={}, extraSeats={} (effective next cycle)",
                userId, newTotalSeats, extraSeats);

        return getStatus(userId);
    }

    // ─────────────────────────────────────────────────────────────
    // GET BILLING STATUS
    // ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BillingStatusResponse getStatus(String userId) {
        BillingSubscription sub = subRepo.findActiveByUserId(userId).orElse(null);

        BillingPlan plan;
        if (sub != null) {
            plan = planRepo.findById(sub.getBillingPlanId()).orElse(null);
        } else {
            // No subscription — show free plan info
            plan = planRepo.findByTierAndActiveTrue("FREE").stream().findFirst().orElse(null);
        }

        // Build subscription info
        BillingStatusResponse.SubscriptionInfo subInfo = sub != null
                ? new BillingStatusResponse.SubscriptionInfo(
                    sub.getId(), sub.getStatus(), sub.getPaymentGateway(),
                    sub.getCurrentPeriodEnd(), sub.getTotalSeats(), sub.getExtraSeats(),
                    sub.isCancelAtPeriodEnd(), sub.isFounderPricing(), sub.getTermsVersion())
                : new BillingStatusResponse.SubscriptionInfo(
                    null, "FREE", "NONE", null, 1, 0, false, false, null);

        // Build usage info
        List<BillingUsage> usageList = usageService.getCurrentUsage(userId);
        List<BillingPlanLimit> limits = plan != null
                ? limitRepo.findByBillingPlanId(plan.getId())
                : List.of();

        List<BillingStatusResponse.UsageInfo> usageInfo = limits.stream()
                .map(limit -> {
                    long current = usageList.stream()
                            .filter(u -> u.getMetric().equals(limit.getMetric()))
                            .findFirst()
                            .map(BillingUsage::getCurrentValue)
                            .orElse(0L);
                    double pct = limit.getMaxValue() > 0
                            ? (double) current / limit.getMaxValue() * 100
                            : 0;
                    return new BillingStatusResponse.UsageInfo(
                            limit.getMetric(), current, limit.getMaxValue(), pct);
                })
                .collect(Collectors.toList());

        PlanResponse planResponse = plan != null
                ? PlanResponse.from(plan, limits)
                : null;

        // Build bonus runs info
        Instant now = Instant.now();
        long userBonus = runTopupRepo.sumRemainingByUserId(userId, now);
        // Also include org bonus if user is in an org
        AppUser statusUser = userRepo.findById(userId).orElse(null);
        long orgBonus = statusUser != null && statusUser.getOrgId() != null
                ? runTopupRepo.sumRemainingByOrgId(statusUser.getOrgId(), now) : 0;
        long totalBonus = userBonus + orgBonus;

        List<RunTopup> activeTopups = runTopupRepo.findActiveByUserId(userId, now);
        List<BillingStatusResponse.TopupInfo> topupInfos = activeTopups.stream()
                .map(t -> new BillingStatusResponse.TopupInfo(
                        t.getId(), t.getQuantity(), t.getRemaining(),
                        t.getSource(), t.getStatus(), t.getExpiresAt(), t.getCreatedAt()))
                .collect(Collectors.toList());

        BillingStatusResponse.BonusRunsInfo bonusRunsInfo = totalBonus > 0 || !topupInfos.isEmpty()
                ? new BillingStatusResponse.BonusRunsInfo(totalBonus, topupInfos)
                : null;

        // Adjust RUNS usage max to include bonus
        List<BillingStatusResponse.UsageInfo> adjustedUsage = usageInfo.stream()
                .map(u -> {
                    if ("RUNS".equals(u.metric()) && totalBonus > 0) {
                        long effectiveMax = u.max() + totalBonus;
                        double pct = effectiveMax > 0 ? (double) u.current() / effectiveMax * 100 : 0;
                        return new BillingStatusResponse.UsageInfo(u.metric(), u.current(), effectiveMax, pct);
                    }
                    return u;
                })
                .collect(Collectors.toList());

        return new BillingStatusResponse(subInfo, planResponse, adjustedUsage, bonusRunsInfo);
    }

    // ─────────────────────────────────────────────────────────────
    // WEBHOOK PROCESSING
    // ─────────────────────────────────────────────────────────────

    public void processRazorpayWebhook(String rawPayload, String signature) {
        // 1. Verify signature
        if (!signatureVerifier.verifyWebhookSignature(rawPayload, signature)) {
            log.warn("Rejected webhook: invalid signature");
            throw new SecurityException("Invalid webhook signature");
        }

        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            String eventType = payload.get("event").asText();

            // Build dedup key — works for both subscriptions and orders
            String subEntityId = payload.path("payload").path("subscription")
                    .path("entity").path("id").asText("");
            if (subEntityId.isBlank()) {
                // Fallback: try order or payment entity
                subEntityId = payload.path("payload").path("order")
                        .path("entity").path("id").asText(
                                payload.path("payload").path("payment")
                                        .path("entity").path("id").asText("unknown"));
            }
            String createdAt = payload.has("created_at")
                    ? payload.get("created_at").asText()
                    : String.valueOf(System.currentTimeMillis());
            String eventId = eventType + "_" + subEntityId + "_" + createdAt;

            // 2. Idempotency check
            if (webhookRepo.existsByGatewayAndEventId("RAZORPAY", eventId)) {
                log.debug("Duplicate webhook skipped: {}", eventId);
                return;
            }

            // 3. Store raw event
            BillingWebhookEvent event = BillingWebhookEvent.builder()
                    .gateway("RAZORPAY")
                    .eventId(eventId)
                    .eventType(eventType)
                    .rawPayload(rawPayload)
                    .processed(false)
                    .build();
            webhookRepo.save(event);

            // 4. Process
            processWebhookEvent(eventType, subEntityId, payload);

            event.setProcessed(true);
            event.setProcessedAt(Instant.now());
            webhookRepo.save(event);

        } catch (SecurityException e) {
            throw e; // re-throw signature failures
        } catch (Exception e) {
            log.error("Webhook processing error: {}", e.getMessage(), e);
            // Don't rethrow — we want to return 200 to Razorpay to prevent retries
            // The raw payload is stored and can be reprocessed manually
        }
    }

    private void processWebhookEvent(String eventType, String entityId, JsonNode payload) {
        // Handle order-related events (top-up purchases)
        if ("order.paid".equals(eventType) || "payment.captured".equals(eventType)) {
            handleOrderPaymentWebhook(eventType, entityId, payload);
            return;
        }

        if (entityId == null || entityId.isBlank()) {
            log.warn("Webhook event with no entity ID: {}", eventType);
            return;
        }

        BillingSubscription sub = subRepo.findByGatewaySubscriptionId(entityId).orElse(null);
        if (sub == null) {
            log.warn("Webhook for unknown subscription: {} (event={})", entityId, eventType);
            return;
        }

        switch (eventType) {
            case "subscription.authenticated" -> {
                sub.setStatus("ACTIVE");
                subRepo.save(sub);
                log.info("Webhook: subscription authenticated, subId={}", entityId);
            }
            case "subscription.charged" -> {
                BillingPlan plan = planRepo.findById(sub.getBillingPlanId()).orElseThrow();
                sub.setCurrentPeriodStart(Instant.now());
                sub.setCurrentPeriodEnd(computePeriodEnd(plan.getPeriod()));
                sub.setStatus("ACTIVE");
                subRepo.save(sub);

                // Ensure tier is correct (in case of previous PAST_DUE)
                upgradeTier(sub.getUserId(), sub.getOrgId(), plan);

                log.info("Webhook: subscription charged and extended, user={}, periodEnd={}",
                        sub.getUserId(), sub.getCurrentPeriodEnd());

                // Generate renewal invoice
                try {
                    String renewalPaymentId = payload.path("payload").path("payment")
                            .path("entity").path("id").asText("");
                    invoiceService.generateSubscriptionInvoice(sub, plan, renewalPaymentId);
                } catch (Exception e) {
                    log.warn("Invoice generation failed for renewal (non-blocking): {}", e.getMessage());
                }
            }
            case "subscription.pending" -> {
                sub.setStatus("PAST_DUE");
                subRepo.save(sub);
                log.warn("Webhook: subscription pending (payment issue), user={}", sub.getUserId());
                // V1.1: Payment failed notification
                AppUser pendingUser = userRepo.findById(sub.getUserId()).orElse(null);
                if (pendingUser != null) {
                    BillingPlan pendingPlan = planRepo.findById(sub.getBillingPlanId()).orElse(null);
                    notificationService.sendPaymentFailed(pendingUser.getEmail(),
                            pendingPlan != null ? pendingPlan.getName() : "");
                }
            }
            case "subscription.halted" -> {
                // *** ALL RETRIES EXHAUSTED — DOWNGRADE TO FREE ***
                downgradeToFree(sub);
                log.warn("Webhook: subscription halted → downgraded to free, user={}", sub.getUserId());
            }
            case "subscription.cancelled" -> {
                sub.setStatus("CANCELLED");
                sub.setCancelledAt(Instant.now());
                subRepo.save(sub);

                // If past period end already, downgrade now
                if (sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isBefore(Instant.now())) {
                    downgradeToFree(sub);
                }
                log.info("Webhook: subscription cancelled, user={}", sub.getUserId());
            }
            case "subscription.completed" -> {
                downgradeToFree(sub);
                log.info("Webhook: subscription completed → downgraded, user={}", sub.getUserId());
            }
            default -> log.debug("Webhook: unhandled event type: {}", eventType);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ORDER PAYMENT WEBHOOK (top-up purchases)
    // ─────────────────────────────────────────────────────────────

    private void handleOrderPaymentWebhook(String eventType, String entityId, JsonNode payload) {
        // Extract order_id from the payload
        String orderId = payload.path("payload").path("order")
                .path("entity").path("id").asText("");
        if (orderId.isBlank()) {
            // Try payment entity's order_id
            orderId = payload.path("payload").path("payment")
                    .path("entity").path("order_id").asText("");
        }
        if (orderId.isBlank()) {
            log.debug("Order payment webhook with no order_id — not a top-up: {}", eventType);
            return;
        }

        RunTopup topup = runTopupRepo.findByGatewayOrderId(orderId).orElse(null);
        if (topup == null) {
            log.debug("Order payment webhook for unknown order {} — not a top-up", orderId);
            return;
        }

        if (!"PENDING".equals(topup.getStatus())) {
            log.debug("Top-up already processed via webhook: orderId={}, status={}", orderId, topup.getStatus());
            return;
        }

        // Credit the runs
        String paymentId = payload.path("payload").path("payment")
                .path("entity").path("id").asText("");
        topup.setGatewayPaymentId(paymentId);
        topup.setRemaining(topup.getQuantity());
        topup.setStatus("ACTIVE");
        runTopupRepo.save(topup);

        log.info("Top-up credited via webhook: orderId={}, quantity={}, userId={}",
                orderId, topup.getQuantity(), topup.getUserId());

        // Generate invoice
        try {
            invoiceService.generateTopupInvoice(topup, null, paymentId);
        } catch (Exception e) {
            log.warn("Invoice generation failed for top-up webhook (non-blocking): {}", e.getMessage());
        }

        // Notify user
        if (topup.getUserId() != null) {
            AppUser user = userRepo.findById(topup.getUserId()).orElse(null);
            if (user != null) {
                notificationService.sendTopupCredited(user.getEmail(), topup.getQuantity());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // STRIPE WEBHOOK PROCESSING
    // ─────────────────────────────────────────────────────────────

    /**
     * Process a validated Stripe webhook event.
     * Called by BillingWebhookController after signature verification by StripePaymentGateway.
     */
    public void processStripeWebhook(String rawPayload, String signature, String eventType) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            String eventId = payload.has("id") ? payload.get("id").asText() : "stripe_" + System.currentTimeMillis();

            // Idempotency check
            if (webhookRepo.existsByGatewayAndEventId("STRIPE", eventId)) {
                log.debug("Duplicate Stripe webhook skipped: {}", eventId);
                return;
            }

            // Store raw event
            BillingWebhookEvent event = BillingWebhookEvent.builder()
                    .gateway("STRIPE")
                    .eventId(eventId)
                    .eventType(eventType)
                    .rawPayload(rawPayload)
                    .processed(false)
                    .build();
            webhookRepo.save(event);

            // Process based on event type
            JsonNode dataObject = payload.path("data").path("object");

            switch (eventType) {
                case "checkout.session.completed" -> handleStripeCheckoutCompleted(dataObject);
                case "invoice.paid" -> handleStripeInvoicePaid(dataObject);
                case "invoice.payment_failed" -> handleStripeInvoicePaymentFailed(dataObject);
                case "customer.subscription.deleted" -> handleStripeSubscriptionDeleted(dataObject);
                default -> log.debug("Stripe webhook: unhandled event type: {}", eventType);
            }

            event.setProcessed(true);
            event.setProcessedAt(Instant.now());
            webhookRepo.save(event);

        } catch (Exception e) {
            log.error("Stripe webhook processing error: {}", e.getMessage(), e);
        }
    }

    private void handleStripeCheckoutCompleted(JsonNode session) {
        String stripeSubscriptionId = session.path("subscription").asText("");
        String stripeCustomerId = session.path("customer").asText("");
        String billingEntityId = session.path("metadata").path("billing_entity_id").asText("");
        String billingEntityType = session.path("metadata").path("billing_entity_type").asText("");
        String planId = session.path("metadata").path("plan_id").asText("");

        if (stripeSubscriptionId.isBlank()) {
            log.warn("Stripe checkout.session.completed without subscription ID");
            return;
        }

        // Update the Stripe customer ID on the billing entity
        if ("ORG".equals(billingEntityType) && !billingEntityId.isBlank()) {
            orgRepo.findById(billingEntityId).ifPresent(org -> {
                org.setStripeCustomerId(stripeCustomerId);
                org.setPaymentGateway("stripe");
                orgRepo.save(org);
            });
        } else if (!billingEntityId.isBlank()) {
            userRepo.findById(billingEntityId).ifPresent(user -> {
                user.setStripeCustomerId(stripeCustomerId);
                user.setPaymentGateway("stripe");
                userRepo.save(user);
            });
        }

        // Find subscription by gateway subscription ID
        BillingSubscription sub = subRepo.findByGatewaySubscriptionId(stripeSubscriptionId).orElse(null);
        if (sub == null) {
            // Create subscription record if checkout was initiated outside our flow
            log.info("Stripe checkout completed for new subscription: subId={}, entityId={}",
                    stripeSubscriptionId, billingEntityId);
            // Subscription will be fully set up by invoice.paid event
            return;
        }

        sub.setStatus("ACTIVE");
        sub.setGatewayCustomerId(stripeCustomerId);
        sub.setCurrentPeriodStart(Instant.now());
        subRepo.save(sub);

        log.info("Stripe checkout completed: subId={}, entityId={}", stripeSubscriptionId, billingEntityId);
    }

    private void handleStripeInvoicePaid(JsonNode invoice) {
        String stripeSubscriptionId = invoice.path("subscription").asText("");
        String stripeInvoiceId = invoice.path("id").asText("");
        String invoicePdfUrl = invoice.path("invoice_pdf").asText("");

        if (stripeSubscriptionId.isBlank()) {
            log.debug("Stripe invoice.paid for non-subscription invoice: {}", stripeInvoiceId);
            return;
        }

        BillingSubscription sub = subRepo.findByGatewaySubscriptionId(stripeSubscriptionId).orElse(null);
        if (sub == null) {
            log.warn("Stripe invoice.paid for unknown subscription: {}", stripeSubscriptionId);
            return;
        }

        BillingPlan plan = planRepo.findById(sub.getBillingPlanId()).orElse(null);
        if (plan == null) {
            log.warn("Plan not found for subscription: {}", sub.getBillingPlanId());
            return;
        }

        // Extend subscription period
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodStart(Instant.now());
        sub.setCurrentPeriodEnd(computePeriodEnd(plan.getPeriod()));
        subRepo.save(sub);

        // Ensure tier is correct
        upgradeTier(sub.getUserId(), sub.getOrgId(), plan);

        // Generate invoice with Stripe references
        try {
            invoiceService.generateStripeSubscriptionInvoice(sub, plan, stripeInvoiceId, invoicePdfUrl);
        } catch (Exception e) {
            log.warn("Invoice generation failed for Stripe payment (non-blocking): {}", e.getMessage());
        }

        log.info("Stripe invoice.paid: subId={}, invoiceId={}", stripeSubscriptionId, stripeInvoiceId);
    }

    private void handleStripeInvoicePaymentFailed(JsonNode invoice) {
        String stripeSubscriptionId = invoice.path("subscription").asText("");

        if (stripeSubscriptionId.isBlank()) return;

        BillingSubscription sub = subRepo.findByGatewaySubscriptionId(stripeSubscriptionId).orElse(null);
        if (sub == null) return;

        sub.setStatus("PAST_DUE");
        subRepo.save(sub);

        log.warn("Stripe invoice payment failed: subId={}, user={}", stripeSubscriptionId, sub.getUserId());

        // Notify user
        AppUser user = userRepo.findById(sub.getUserId()).orElse(null);
        if (user != null) {
            BillingPlan plan = planRepo.findById(sub.getBillingPlanId()).orElse(null);
            notificationService.sendPaymentFailed(user.getEmail(),
                    plan != null ? plan.getName() : "");
        }
    }

    private void handleStripeSubscriptionDeleted(JsonNode subscription) {
        String stripeSubscriptionId = subscription.path("id").asText("");

        if (stripeSubscriptionId.isBlank()) return;

        BillingSubscription sub = subRepo.findByGatewaySubscriptionId(stripeSubscriptionId).orElse(null);
        if (sub == null) {
            log.warn("Stripe subscription.deleted for unknown subscription: {}", stripeSubscriptionId);
            return;
        }

        downgradeToFree(sub);
        log.info("Stripe subscription deleted → downgraded to free: subId={}, user={}",
                stripeSubscriptionId, sub.getUserId());
    }

    // ─────────────────────────────────────────────────────────────
    // TIER BRIDGE (billing → existing auth system)
    // ─────────────────────────────────────────────────────────────

    private void upgradeTier(String userId, String orgId, BillingPlan plan) {
        AppUser user = userRepo.findById(userId).orElseThrow();

        String authTier = switch (plan.getTier()) {
            case "STARTER" -> "FREE";       // Same access as free, billing controls run limit
            case "PRO" -> "PRO";            // Unlocks org features
            case "ENTERPRISE" -> "ENTERPRISE";
            default -> "FREE";
        };
        user.setTier(authTier);
        userRepo.save(user);

        // Set key source mode on org if applicable
        if (orgId != null) {
            orgRepo.findById(orgId).ifPresent(org -> {
                String keyMode = switch (plan.getKeyMode()) {
                    case "BYOK" -> "OWN_KEYS";
                    case "PLATFORM" -> "SYSTEM";
                    default -> "HYBRID";
                };
                org.setKeySourceMode(keyMode);
                orgRepo.save(org);
            });
        }

        log.info("Tier upgraded: user={}, authTier={}, planTier={}, keyMode={}",
                userId, authTier, plan.getTier(), plan.getKeyMode());

        // Send upgrade email
        try {
            mailService.sendTierUpgradeEmail(user.getEmail(), user.getFirstName(),
                    plan.getName(), authTier);
        } catch (Exception e) {
            log.warn("Failed to send tier upgrade email (non-blocking): {}", e.getMessage());
        }
    }

    private void downgradeToFree(BillingSubscription sub) {
        sub.setStatus("DOWNGRADED");
        sub.setDowngradedAt(Instant.now());
        subRepo.save(sub);

        AppUser user = userRepo.findById(sub.getUserId()).orElseThrow();
        user.setTier("FREE");
        userRepo.save(user);

        if (sub.getOrgId() != null) {
            orgRepo.findById(sub.getOrgId()).ifPresent(org -> {
                org.setKeySourceMode("SYSTEM");
                orgRepo.save(org);
            });
        }

        log.warn("Downgraded to free: user={}, previousPlan={}", sub.getUserId(), sub.getBillingPlanId());

        // V1.1: Downgrade notification
        BillingPlan downgradedPlan = planRepo.findById(sub.getBillingPlanId()).orElse(null);
        notificationService.sendDowngradedToFree(user.getEmail(),
                downgradedPlan != null ? downgradedPlan.getName() : "");
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private Instant computePeriodEnd(String period) {
        return switch (period) {
            case "ANNUAL" -> Instant.now().plus(365, ChronoUnit.DAYS);
            default -> Instant.now().plus(30, ChronoUnit.DAYS); // MONTHLY
        };
    }
}
