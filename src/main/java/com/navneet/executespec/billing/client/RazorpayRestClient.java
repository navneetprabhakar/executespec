package com.navneet.executespec.billing.client;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class RazorpayRestClient {

  private final RestClient restClient;

  public RazorpayRestClient(@Qualifier("razorpayHttpClient") RestClient restClient) {
    this.restClient = restClient;
  }

  /** POST /v1/customers */
  public JsonNode createCustomer(String name, String email, String contact) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", name);
    body.put("email", email);
    body.put("fail_existing", 0); // return existing customer instead of 400 error
    if (contact != null && !contact.isBlank()) body.put("contact", contact);

    log.debug("Creating Razorpay customer: email={}", email);
    return restClient.post().uri("/customers").body(body).retrieve().body(JsonNode.class);
  }

  /** POST /v1/plans One-time setup — creates a plan in Razorpay. */
  public JsonNode createPlan(String planName, int amountPaise, String period, int interval) {
    return createPlan(planName, amountPaise, period, interval, null);
  }

  /**
   * POST /v1/plans with a caller-supplied idempotency key (RR-400).
   *
   * <p>When {@code idempotencyKey} is non-blank it is sent as the {@code X-Razorpay-Idempotency}
   * header. Razorpay dedupes a repeated create carrying the same key and returns the ORIGINAL plan
   * rather than minting a second chargeable plan. That is what makes plan seeding safe to retry
   * after a crash between the gateway call returning and the local id being committed: the retry
   * reuses the same deterministic key, so it reconciles instead of duplicating.
   */
  public JsonNode createPlan(
      String planName, int amountPaise, String period, int interval, String idempotencyKey) {
    Map<String, Object> body =
        Map.of(
            "period", period,
            "interval", interval,
            "item",
                Map.of(
                    "name", planName,
                    "amount", amountPaise,
                    "currency", "INR"));

    boolean idempotent = idempotencyKey != null && !idempotencyKey.isBlank();
    log.info(
        "Creating Razorpay plan: name={}, amount={}, period={}, idempotent={}",
        planName,
        amountPaise,
        period,
        idempotent);
    RestClient.RequestBodySpec request = restClient.post().uri("/plans");
    if (idempotent) {
      request = request.header("X-Razorpay-Idempotency", idempotencyKey);
    }
    return request.body(body).retrieve().body(JsonNode.class);
  }

  /** POST /v1/subscriptions */
  public JsonNode createSubscription(
      String razorpayPlanId,
      String customerId,
      int totalCount,
      int quantity,
      Map<String, String> notes) {
    Map<String, Object> body = new HashMap<>();
    body.put("plan_id", razorpayPlanId);
    body.put("customer_id", customerId);
    body.put("total_count", totalCount);
    body.put("quantity", Math.max(1, quantity));
    if (notes != null && !notes.isEmpty()) body.put("notes", notes);

    log.info(
        "Creating Razorpay subscription: planId={}, customerId={}", razorpayPlanId, customerId);
    return restClient.post().uri("/subscriptions").body(body).retrieve().body(JsonNode.class);
  }

  /** GET /v1/subscriptions/{id} */
  public JsonNode fetchSubscription(String subscriptionId) {
    return restClient
        .get()
        .uri("/subscriptions/{id}", subscriptionId)
        .retrieve()
        .body(JsonNode.class);
  }

  /** POST /v1/subscriptions/{id}/cancel */
  public JsonNode cancelSubscription(String subscriptionId, boolean cancelAtCycleEnd) {
    return restClient
        .post()
        .uri("/subscriptions/{id}/cancel", subscriptionId)
        .body(Map.of("cancel_at_cycle_end", cancelAtCycleEnd ? 1 : 0))
        .retrieve()
        .body(JsonNode.class);
  }

  /** GET /v1/customers — fetch customers by email */
  public JsonNode fetchCustomersByEmail(String email) {
    log.debug("Fetching Razorpay customers by email: {}", email);
    return restClient.get().uri("/customers?email={email}", email).retrieve().body(JsonNode.class);
  }

  /** POST /v1/payments/{paymentId}/refund — partial or full refund */
  public JsonNode createRefund(String paymentId, long amountPaise, Map<String, String> notes) {
    return createRefund(paymentId, amountPaise, notes, null);
  }

  /**
   * POST /v1/payments/{paymentId}/refund with a caller-supplied idempotency key (RR-049 drainer).
   *
   * <p>A non-blank {@code idempotencyKey} is sent as {@code X-Razorpay-Idempotency}, so a retried
   * refund carrying the same key returns the ORIGINAL refund instead of moving the customer's money
   * a second time. Combined with the pre-flight {@link #fetchPaymentRefunds} check this makes the
   * obligation drainer safe to re-run a failed refund.
   */
  public JsonNode createRefund(
      String paymentId, long amountPaise, Map<String, String> notes, String idempotencyKey) {
    Map<String, Object> body = new HashMap<>();
    body.put("amount", amountPaise);
    if (notes != null && !notes.isEmpty()) body.put("notes", notes);

    boolean idempotent = idempotencyKey != null && !idempotencyKey.isBlank();
    log.info(
        "Creating Razorpay refund: paymentId={}, amount={} paise, idempotent={}",
        paymentId,
        amountPaise,
        idempotent);
    RestClient.RequestBodySpec request = restClient.post().uri("/payments/{id}/refund", paymentId);
    if (idempotent) {
      request = request.header("X-Razorpay-Idempotency", idempotencyKey);
    }
    return request.body(body).retrieve().body(JsonNode.class);
  }

  /**
   * GET /v1/payments/{paymentId}/refunds — the refunds already issued against a payment.
   *
   * <p>Used by the obligation drainer to prove, before re-issuing, whether a prior refund attempt
   * actually landed at the gateway (the ambiguous case where the create call threw AFTER Razorpay
   * had accepted it). Returns the raw collection JSON ({@code items[]}).
   */
  public JsonNode fetchPaymentRefunds(String paymentId) {
    return restClient
        .get()
        .uri("/payments/{id}/refunds", paymentId)
        .retrieve()
        .body(JsonNode.class);
  }

  /** POST /v1/orders — one-time payment order (used for run top-up purchases) */
  public JsonNode createOrder(
      long amountPaise, String currency, String receiptId, Map<String, String> notes) {
    Map<String, Object> body = new HashMap<>();
    body.put("amount", amountPaise);
    body.put("currency", currency);
    body.put("receipt", receiptId);
    if (notes != null && !notes.isEmpty()) body.put("notes", notes);

    log.info(
        "Creating Razorpay order: amount={}, currency={}, receipt={}",
        amountPaise,
        currency,
        receiptId);
    return restClient.post().uri("/orders").body(body).retrieve().body(JsonNode.class);
  }
}
