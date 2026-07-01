package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.billing.dto.TopupOrderInitResult;
import com.navneet.executespec.billing.dto.TopupPurchaseRequest;
import com.navneet.executespec.billing.entity.RunTopup;
import com.navneet.executespec.billing.entity.TopupPackage;
import com.navneet.executespec.billing.exception.BillingException;
import com.navneet.executespec.billing.gateway.BillingEntityResolver;
import com.navneet.executespec.billing.gateway.BillingGateway;
import com.navneet.executespec.billing.gateway.PaymentGatewayRouter;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.repository.RunTopupRepository;
import com.navneet.executespec.billing.repository.TopupPackageRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RunTopupService {

  private static final long AI_TOPUP_CREDITS_PER_USD = 100;
  private static final long FALLBACK_USD_CENT_TO_INR_PAISE = 85;

  private final RunTopupRepository topupRepo;
  private final TopupPackageRepository packageRepo;
  private final BillingGateway razorpayGateway;
  private final BillingSubscriptionRepository subscriptionRepo;
  private final AppUserRepository userRepo;
  private final ActivityAuditService auditService;
  private final BillingNotificationService notificationService;
  private final InvoiceService invoiceService;
  private final ManagedAiUsageService managedAiUsageService;
  private final BillingEntityResolver billingEntityResolver;
  private final PaymentGatewayRouter gatewayRouter;

  @Transactional(readOnly = true)
  public List<TopupPackage> getPackages() {
    return packageRepo.findByActiveTrueOrderBySortOrder();
  }

  @Transactional(readOnly = true)
  public long getAvailableBonus(String userId) {
    return topupRepo.sumRemainingByUserId(userId, Instant.now());
  }

  @Transactional(readOnly = true)
  public long getAvailableBonusForOrg(String orgId) {
    if (orgId == null) return 0;
    return topupRepo.sumRemainingByOrgId(orgId, Instant.now());
  }

  // Bonus consumption (FIFO)

  /**
   * Consume one bonus run from the user's personal top-ups (FIFO order). Returns true if a bonus
   * run was consumed, false if none available.
   */
  public boolean consumeBonusRun(String userId) {
    Instant now = Instant.now();
    var topup = topupRepo.findOldestActiveByUserId(userId, now);
    if (topup.isEmpty()) return false;

    RunTopup t = topup.get();
    int updated = topupRepo.decrementRemaining(t.getId(), now);
    if (updated == 0) return false;

    // Check if exhausted
    t = topupRepo.findById(t.getId()).orElse(t);
    if (t.getRemaining() <= 0) {
      t.setStatus("EXHAUSTED");
      topupRepo.save(t);
    }

    auditService.record(
        ActivityAudit.builder()
            .eventType("TOPUP_CONSUMED")
            .entityType("BILLING")
            .entityId(t.getId().toString())
            .actorId(userId)
            .newValue(
                "{\"topupId\":\"" + t.getId() + "\",\"remainingAfter\":" + t.getRemaining() + "}")
            .build());

    log.debug(
        "Consumed bonus run: userId={}, topupId={}, remaining={}",
        userId,
        t.getId(),
        t.getRemaining());
    return true;
  }

  /**
   * Consume one bonus run from the org's top-ups (FIFO order). Returns true if a bonus run was
   * consumed, false if none available.
   */
  public boolean consumeBonusRunForOrg(String orgId) {
    if (orgId == null) return false;
    Instant now = Instant.now();
    var topup = topupRepo.findOldestActiveByOrgId(orgId, now);
    if (topup.isEmpty()) return false;

    RunTopup t = topup.get();
    int updated = topupRepo.decrementRemaining(t.getId(), now);
    if (updated == 0) return false;

    t = topupRepo.findById(t.getId()).orElse(t);
    if (t.getRemaining() <= 0) {
      t.setStatus("EXHAUSTED");
      topupRepo.save(t);
    }

    auditService.record(
        ActivityAudit.builder()
            .eventType("TOPUP_CONSUMED")
            .entityType("BILLING")
            .entityId(t.getId().toString())
            .orgId(orgId)
            .newValue(
                "{\"topupId\":\"" + t.getId() + "\",\"remainingAfter\":" + t.getRemaining() + "}")
            .build());

    log.debug(
        "Consumed org bonus run: orgId={}, topupId={}, remaining={}",
        orgId,
        t.getId(),
        t.getRemaining());
    return true;
  }

  /** Initiate a top-up purchase: create PENDING record + Razorpay order. */
  public TopupOrderInitResult initiatePurchase(String userId, UUID packageId) {
    return initiatePurchase(userId, new TopupPurchaseRequest(packageId, null, null, null, null));
  }

  /** Initiate an AI-credit top-up purchase. Package rows and custom USD amounts share this path. */
  public TopupOrderInitResult initiatePurchase(String userId, TopupPurchaseRequest request) {
    TopupIntent intent = resolveIntent(request);
    if ("STRIPE".equalsIgnoreCase(intent.gateway())) {
      return initiateStripePurchase(userId, intent, request);
    }
    return initiateRazorpayPurchase(userId, intent);
  }

  private TopupIntent resolveIntent(TopupPurchaseRequest request) {
    if (request == null) {
      throw new BillingException("Top-up request is required");
    }
    if (request.packageId() != null) {
      return packageIntent(request.packageId(), request.gateway());
    }
    Long amountUsdCents = request.amountUsdCents();
    if (amountUsdCents == null || amountUsdCents < 100) {
      throw new BillingException("Custom top-up amount must be at least $1");
    }
    long wholeDollars = amountUsdCents / 100;
    if (wholeDollars * 100 != amountUsdCents) {
      throw new BillingException("Custom top-up amount must be a whole-dollar amount");
    }
    long credits = wholeDollars * AI_TOPUP_CREDITS_PER_USD;
    long amountInr = amountUsdCents * FALLBACK_USD_CENT_TO_INR_PAISE;
    String gateway = normalizeGateway(request.gateway(), "RAZORPAY");
    return new TopupIntent(
        "Custom " + credits + " AI credits",
        Math.toIntExact(credits),
        amountInr,
        amountUsdCents,
        gateway,
        "custom:" + amountUsdCents);
  }

  private TopupIntent packageIntent(UUID packageId, String requestedGateway) {
    TopupPackage pkg =
        packageRepo
            .findById(packageId)
            .orElseThrow(() -> new BillingException("Top-up package not found"));
    if (!pkg.isActive()) {
      throw new BillingException("This top-up package is no longer available");
    }
    String defaultGateway =
        pkg.getAmountUsd() > 0 && pkg.getAmountInr() <= 0 ? "STRIPE" : "RAZORPAY";
    String gateway = normalizeGateway(requestedGateway, defaultGateway);
    return new TopupIntent(
        pkg.getName(),
        pkg.getRunCount(),
        pkg.getAmountInr(),
        pkg.getAmountUsd(),
        gateway,
        "pkg:" + packageId);
  }

  private TopupOrderInitResult initiateRazorpayPurchase(String userId, TopupIntent intent) {
    if (intent.amountInrPaise() <= 0) {
      throw new BillingException("This top-up is not configured for Razorpay checkout");
    }
    String receiptId = "topup_" + UUID.randomUUID().toString().substring(0, 8);

    // Get customer ID if exists
    String customerId = subscriptionRepo.findLatestCustomerIdByUserId(userId).orElse(null);

    Map<String, String> notes =
        Map.of(
            "user_id", userId,
            "grant_reason", intent.grantReason(),
            "package_name", intent.name(),
            "ai_credits", String.valueOf(intent.aiCredits()));
    if (customerId != null) {
      notes = new java.util.HashMap<>(notes);
      notes.put("customer_id", customerId);
    }

    TopupOrderInitResult orderResult =
        razorpayGateway.createOrder(intent.amountInrPaise(), "INR", receiptId, notes);

    // Create PENDING top-up record
    RunTopup topup =
        RunTopup.builder()
            .userId(userId)
            .quantity(intent.aiCredits())
            .remaining(0)
            .source("AI_CREDIT_PURCHASE")
            .gatewayOrderId(orderResult.orderId())
            .amountPaidInr(intent.amountInrPaise())
            .grantReason(intent.grantReason())
            .status("PENDING")
            .build();
    topupRepo.save(topup);

    log.info(
        "AI-credit top-up purchase initiated: userId={}, package={}, orderId={}, credits={}",
        userId,
        intent.name(),
        orderResult.orderId(),
        intent.aiCredits());

    return new TopupOrderInitResult(
        orderResult.orderId(),
        orderResult.customerId(),
        orderResult.razorpayKeyId(),
        orderResult.amountPaise(),
        "RAZORPAY",
        null,
        null,
        intent.aiCredits());
  }

  private TopupOrderInitResult initiateStripePurchase(
      String userId, TopupIntent intent, TopupPurchaseRequest request) {
    if (intent.amountUsdCents() <= 0) {
      throw new BillingException("This top-up is not configured for Stripe checkout");
    }
    var user = userRepo.findById(userId).orElseThrow(() -> new BillingException("User not found"));
    RunTopup topup =
        RunTopup.builder()
            .userId(userId)
            .orgId(user.getOrgId())
            .quantity(intent.aiCredits())
            .remaining(0)
            .source("AI_CREDIT_PURCHASE")
            .amountPaidInr(0)
            .grantReason(intent.grantReason())
            .status("PENDING")
            .build();
    topupRepo.save(topup);
    var entity = billingEntityResolver.resolve(user);
    var checkout =
        gatewayRouter
            .resolveByName("stripe")
            .createTopupCheckoutSession(
                entity,
                intent.amountUsdCents(),
                intent.aiCredits(),
                topup.getId().toString(),
                request.successUrl() != null ? request.successUrl() : "/billing?topup=success",
                request.cancelUrl() != null ? request.cancelUrl() : "/billing?topup=cancelled");
    topup.setGatewayOrderId(checkout.sessionId());
    topupRepo.save(topup);
    return new TopupOrderInitResult(
        checkout.sessionId(),
        null,
        null,
        0,
        "STRIPE",
        checkout.url(),
        checkout.sessionId(),
        intent.aiCredits());
  }

  /** Verify payment and credit runs after Razorpay checkout. */
  public RunTopup verifyAndCredit(
      String userId, String orderId, String paymentId, String signature) {
    if (!razorpayGateway.verifyOrderPayment(orderId, paymentId, signature)) {
      throw new SecurityException("Invalid order payment signature");
    }

    RunTopup topup =
        topupRepo
            .findByGatewayOrderId(orderId)
            .orElseThrow(
                () -> new BillingException("Top-up record not found for order: " + orderId));
    if (!userId.equals(topup.getUserId())) {
      throw new SecurityException("Top-up order does not belong to this user.");
    }

    if (!"PENDING".equals(topup.getStatus())) {
      log.warn("Top-up already processed: orderId={}, status={}", orderId, topup.getStatus());
      return topup;
    }

    int activated = topupRepo.markPendingPurchaseCredited(orderId, paymentId, Instant.now());
    if (activated == 0) {
      log.warn("Top-up activation lost race: orderId={}", orderId);
      return topupRepo.findByGatewayOrderId(orderId).orElse(topup);
    }
    topup =
        topupRepo
            .findByGatewayOrderId(orderId)
            .orElseThrow(
                () -> new BillingException("Top-up record not found after activation: " + orderId));

    auditService.record(
        ActivityAudit.builder()
            .eventType("TOPUP_PURCHASED")
            .entityType("BILLING")
            .entityId(topup.getId().toString())
            .actorId(userId)
            .newValue(
                "{\"quantity\":"
                    + topup.getQuantity()
                    + ",\"orderId\":\""
                    + orderId
                    + "\",\"amountInr\":"
                    + topup.getAmountPaidInr()
                    + "}")
            .build());

    // Send notification email
    managedAiUsageService.grantPurchasedCredits(userId, topup.getOrgId(), topup.getQuantity());
    var user = userRepo.findById(userId).orElse(null);
    if (user != null) {
      notificationService.sendTopupCredited(user.getEmail(), topup.getQuantity());
    }

    // Generate invoice
    try {
      // Resolve package from order notes if available
      TopupPackage pkg = null;
      if (topup.getGrantReason() != null && topup.getGrantReason().startsWith("pkg:")) {
        try {
          var pkgId = UUID.fromString(topup.getGrantReason().substring(4));
          pkg = packageRepo.findById(pkgId).orElse(null);
        } catch (Exception ignored) {
        }
      }
      invoiceService.generateTopupInvoice(topup, pkg, paymentId);
    } catch (Exception e) {
      log.warn("Invoice generation failed for top-up (non-blocking): {}", e.getMessage());
    }

    log.info(
        "Top-up credited: userId={}, quantity={}, orderId={}",
        userId,
        topup.getQuantity(),
        orderId);
    return topup;
  }

  public RunTopup creditStripeTopup(String topupId, String sessionId, String paymentId) {
    RunTopup topup =
        topupRepo
            .findById(UUID.fromString(topupId))
            .orElseThrow(() -> new BillingException("Top-up record not found: " + topupId));
    if (!"PENDING".equals(topup.getStatus())) {
      log.warn(
          "Stripe top-up already processed: topupId={}, status={}", topupId, topup.getStatus());
      return topup;
    }
    topup.setGatewayOrderId(sessionId);
    topup.setGatewayPaymentId(paymentId);
    topup.setRemaining(0);
    topup.setStatus("CREDITED");
    topupRepo.save(topup);
    managedAiUsageService.grantPurchasedCredits(
        topup.getUserId(), topup.getOrgId(), topup.getQuantity());
    auditService.record(
        ActivityAudit.builder()
            .eventType("TOPUP_PURCHASED")
            .entityType("BILLING")
            .entityId(topup.getId().toString())
            .actorId(topup.getUserId())
            .newValue(
                "{\"quantity\":"
                    + topup.getQuantity()
                    + ",\"sessionId\":\""
                    + sessionId
                    + "\",\"gateway\":\"STRIPE\"}")
            .build());
    return topup;
  }

  private static String normalizeGateway(String requested, String fallback) {
    if (requested == null || requested.isBlank()) return fallback;
    String normalized = requested.trim().toUpperCase();
    if (!"STRIPE".equals(normalized) && !"RAZORPAY".equals(normalized)) {
      throw new BillingException("Unsupported top-up gateway: " + requested);
    }
    return normalized;
  }

  private record TopupIntent(
      String name,
      int aiCredits,
      long amountInrPaise,
      long amountUsdCents,
      String gateway,
      String grantReason) {}

  /** Admin grants bonus runs to a user or org. */
  public RunTopup grantBonusRuns(
      String targetUserId,
      String targetOrgId,
      int quantity,
      Integer expiryDays,
      String reason,
      String grantedBy) {
    if (targetUserId == null && targetOrgId == null) {
      throw new BillingException("Must specify either a user or an org");
    }

    Instant expiresAt = null;
    if (expiryDays != null && expiryDays > 0) {
      expiresAt = Instant.now().plus(expiryDays, ChronoUnit.DAYS);
    } else {
      // Default 90 days for admin grants
      expiresAt = Instant.now().plus(90, ChronoUnit.DAYS);
    }

    RunTopup topup =
        RunTopup.builder()
            .userId(targetUserId)
            .orgId(targetOrgId)
            .quantity(quantity)
            .remaining(quantity)
            .source("ADMIN_GRANT")
            .grantedBy(grantedBy)
            .grantReason(reason)
            .expiresAt(expiresAt)
            .status("ACTIVE")
            .build();
    topupRepo.save(topup);

    String entityId = targetUserId != null ? targetUserId : targetOrgId;
    auditService.record(
        ActivityAudit.builder()
            .eventType("TOPUP_ADMIN_GRANTED")
            .entityType("BILLING")
            .entityId(entityId)
            .actorId(grantedBy)
            .orgId(targetOrgId)
            .newValue(
                "{\"quantity\":"
                    + quantity
                    + ",\"expiryDays\":"
                    + (expiryDays != null ? expiryDays : 90)
                    + ",\"reason\":\""
                    + (reason != null ? reason.replace("\"", "\\\"") : "")
                    + "\"}")
            .build());

    log.info(
        "Admin granted {} bonus runs: target={}, grantedBy={}, expiresAt={}",
        quantity,
        entityId,
        grantedBy,
        expiresAt);

    // Send notification to target user
    if (targetUserId != null) {
      var user = userRepo.findById(targetUserId).orElse(null);
      if (user != null) {
        notificationService.sendTopupCredited(user.getEmail(), quantity);
      }
    }

    return topup;
  }

  @Transactional(readOnly = true)
  public List<RunTopup> listTopups(String userId) {
    return topupRepo.findByUserIdOrderByCreatedAtDesc(userId);
  }

  /** Expire overdue admin-granted top-ups. Called by scheduler. */
  public int expireOverdueTopups() {
    int count = topupRepo.expireOverdue(Instant.now());
    if (count > 0) {
      log.info("Expired {} overdue top-up records", count);
    }
    return count;
  }
}
