package com.navneet.executespec.billing.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.billing.client.RazorpayRestClient;
import com.navneet.executespec.billing.config.RazorpayConfig;
import com.navneet.executespec.billing.dto.SubscriptionInitResult;
import com.navneet.executespec.billing.dto.TopupOrderInitResult;
import com.navneet.executespec.billing.dto.VerifyPaymentRequest;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.security.WebhookSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class RazorpayGateway implements BillingGateway {

    private final RazorpayRestClient client;
    private final RazorpayConfig config;
    private final WebhookSignatureVerifier verifier;
    private final BillingSubscriptionRepository subscriptionRepo;

    @Override
    public SubscriptionInitResult createSubscription(String userId, String userName, String userEmail,
                                                      String orgId, BillingPlan plan) {
        // 1. Get or create Razorpay customer
        String customerId = getOrCreateCustomer(userId, userName, userEmail);

        // 2. Compute max total_count
        int totalCount = "ANNUAL".equals(plan.getPeriod()) ? 50 : 600;

        // 3. Create Razorpay subscription
        JsonNode sub = client.createSubscription(
                plan.getRazorpayPlanId(),
                customerId,
                totalCount,
                Map.of(
                        "user_id", userId,
                        "plan_tier", plan.getTier(),
                        "key_mode", plan.getKeyMode()
                )
        );

        String subId = sub.get("id").asText();
        String shortUrl = sub.has("short_url") ? sub.get("short_url").asText() : null;

        log.info("Razorpay subscription created: subId={}, customerId={}, plan={}",
                subId, customerId, plan.getName());

        return new SubscriptionInitResult(subId, customerId, shortUrl, config.getKeyId());
    }

    @Override
    public boolean verifyPayment(VerifyPaymentRequest request) {
        return verifier.verifyPaymentSignature(
                request.razorpaySubscriptionId(),
                request.razorpayPaymentId(),
                request.razorpaySignature()
        );
    }

    @Override
    public void cancelAtPeriodEnd(String gatewaySubscriptionId) {
        client.cancelSubscription(gatewaySubscriptionId, true);
        log.info("Razorpay subscription cancelled at period end: {}", gatewaySubscriptionId);
    }

    @Override
    public String fetchStatus(String gatewaySubscriptionId) {
        JsonNode sub = client.fetchSubscription(gatewaySubscriptionId);
        return sub.get("status").asText();
    }

    @Override
    public String gatewayType() {
        return "RAZORPAY";
    }

    @Override
    public TopupOrderInitResult createOrder(long amountPaise, String currency,
                                             String receiptId, Map<String, String> notes) {
        JsonNode order = client.createOrder(amountPaise, currency, receiptId, notes);
        String orderId = order.get("id").asText();
        log.info("Razorpay order created: orderId={}, amount={}", orderId, amountPaise);

        String customerId = notes != null && notes.containsKey("customer_id")
                ? notes.get("customer_id") : null;

        return new TopupOrderInitResult(orderId, customerId, config.getKeyId(), amountPaise);
    }

    @Override
    public boolean verifyOrderPayment(String orderId, String paymentId, String signature) {
        return verifier.verifyOrderPaymentSignature(orderId, paymentId, signature);
    }

    private String getOrCreateCustomer(String userId, String name, String email) {
        return subscriptionRepo.findLatestCustomerIdByUserId(userId)
                .orElseGet(() -> {
                    JsonNode cust = client.createCustomer(name, email, "");
                    String custId = cust.get("id").asText();
                    log.info("Created Razorpay customer: userId={}, custId={}", userId, custId);
                    return custId;
                });
    }
}
