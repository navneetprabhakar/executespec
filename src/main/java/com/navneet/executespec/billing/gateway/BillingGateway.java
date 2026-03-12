package com.navneet.executespec.billing.gateway;

import com.navneet.executespec.billing.dto.SubscriptionInitResult;
import com.navneet.executespec.billing.dto.TopupOrderInitResult;
import com.navneet.executespec.billing.dto.VerifyPaymentRequest;
import com.navneet.executespec.billing.entity.BillingPlan;

import java.util.Map;

/**
 * Gateway-agnostic billing interface.
 * RazorpayGateway implements now. StripeGateway implements later.
 */
public interface BillingGateway {

    SubscriptionInitResult createSubscription(String userId, String userName, String userEmail,
                                               String orgId, BillingPlan plan);

    boolean verifyPayment(VerifyPaymentRequest request);

    void cancelAtPeriodEnd(String gatewaySubscriptionId);

    String fetchStatus(String gatewaySubscriptionId);

    String gatewayType();

    /** Create a one-time order for top-up purchase */
    default TopupOrderInitResult createOrder(long amountPaise, String currency,
                                              String receiptId, Map<String, String> notes) {
        throw new UnsupportedOperationException("One-time orders not supported by this gateway");
    }

    /** Verify one-time order payment signature */
    default boolean verifyOrderPayment(String orderId, String paymentId, String signature) {
        throw new UnsupportedOperationException("Order payment verification not supported by this gateway");
    }
}
