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
    Map<String, Object> body =
        Map.of(
            "period", period,
            "interval", interval,
            "item",
                Map.of(
                    "name", planName,
                    "amount", amountPaise,
                    "currency", "INR"));

    log.info(
        "Creating Razorpay plan: name={}, amount={}, period={}", planName, amountPaise, period);
    return restClient.post().uri("/plans").body(body).retrieve().body(JsonNode.class);
  }

  /** POST /v1/subscriptions */
  public JsonNode createSubscription(
      String razorpayPlanId, String customerId, int totalCount, Map<String, String> notes) {
    Map<String, Object> body = new HashMap<>();
    body.put("plan_id", razorpayPlanId);
    body.put("customer_id", customerId);
    body.put("total_count", totalCount);
    body.put("quantity", 1);
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
    Map<String, Object> body = new HashMap<>();
    body.put("amount", amountPaise);
    if (notes != null && !notes.isEmpty()) body.put("notes", notes);

    log.info("Creating Razorpay refund: paymentId={}, amount={} paise", paymentId, amountPaise);
    return restClient
        .post()
        .uri("/payments/{id}/refund", paymentId)
        .body(body)
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
