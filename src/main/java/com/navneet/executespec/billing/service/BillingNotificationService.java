package com.navneet.executespec.billing.service;

import com.google.api.services.gmail.model.Message;
import com.navneet.executespec.auth.service.GmailOAuthService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Properties;

/**
 * Sends billing-related email notifications via the Gmail API.
 *
 * <p>All methods are @Async — callers are never blocked by email I/O.
 * If Gmail is unavailable, the notification is logged at WARN level
 * and silently skipped (non-fatal).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingNotificationService {

    private final GmailOAuthService gmailOAuthService;

    @Value("${app.dashboard-url:https://executespec.ai}")
    private String dashboardUrl;

    @Async
    public void sendSubscriptionActivated(String email, String planName) {
        send(email, "ExecuteSpec — Subscription Activated",
                """
                Your %s subscription is now active!

                View your plan and usage: %s/billing

                If you have questions, reply to this email.

                — ExecuteSpec
                """.formatted(planName, dashboardUrl));
    }

    @Async
    public void sendPaymentFailed(String email, String planName) {
        send(email, "ExecuteSpec — Payment Failed",
                """
                We were unable to process your payment for the %s plan.

                We'll retry automatically over the next few days. If the issue persists,
                please update your payment method: %s/billing

                — ExecuteSpec
                """.formatted(planName, dashboardUrl));
    }

    @Async
    public void sendDowngradedToFree(String email, String planName) {
        send(email, "ExecuteSpec — Subscription Downgraded",
                """
                After several payment attempts, your %s subscription has been downgraded
                to the Free plan.

                Your data and projects are preserved. You can upgrade anytime: %s/plans

                — ExecuteSpec
                """.formatted(planName, dashboardUrl));
    }

    @Async
    public void sendCancellationConfirmed(String email, String planName, String periodEnd) {
        send(email, "ExecuteSpec — Cancellation Confirmed",
                """
                Your %s subscription has been cancelled.

                You'll continue to have access until %s.
                After that, your account will revert to the Free plan.

                Changed your mind? Resubscribe anytime: %s/plans

                — ExecuteSpec
                """.formatted(planName, periodEnd, dashboardUrl));
    }

    @Async
    public void sendTopupCredited(String email, int quantity) {
        send(email, "ExecuteSpec — Bonus Runs Credited",
                """
                %d bonus runs have been added to your account!

                These runs are available immediately and can be used
                once your monthly quota is exhausted.

                View your balance: %s/billing

                — ExecuteSpec
                """.formatted(quantity, dashboardUrl));
    }

    @Async
    public void sendUpgradeRefund(String email, String oldPlanName,
                                   long unusedRuns, long totalRuns, long refundAmountPaise) {
        String refundFormatted = String.format("\u20B9%,.2f", refundAmountPaise / 100.0);
        send(email, "ExecuteSpec — Subscription Upgraded + Refund Issued",
                """
                Your %s subscription has been cancelled as part of your upgrade.

                Refund details:
                  - Unused runs: %d of %d
                  - Refund amount: %s (prorated)

                The refund will be credited to your original payment method
                within 5–7 business days.

                A credit note is attached to this email or will follow shortly.

                View your new plan: %s/billing

                — ExecuteSpec
                """.formatted(oldPlanName, unusedRuns, totalRuns, refundFormatted, dashboardUrl));
    }

    @Async
    public void sendUsageWarning(String email, String metric, long current, long max) {
        send(email, "ExecuteSpec — Usage Alert",
                """
                You've used %d of %d %s this month (%.0f%%).

                Upgrade for higher limits: %s/plans

                — ExecuteSpec
                """.formatted(current, max, metric.toLowerCase(),
                        (double) current / max * 100, dashboardUrl));
    }

    private void send(String to, String subject, String body) {
        if (!gmailOAuthService.isAvailable()) {
            log.warn("[BillingNotification] Gmail unavailable — logging instead: to={}, subject={}, body=\n{}",
                    to, subject, body);
            return;
        }

        try {
            Session session = Session.getDefaultInstance(new Properties());
            MimeMessage mimeMessage = new MimeMessage(session);
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");
            helper.setFrom(gmailOAuthService.getSenderEmail());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false); // plain text

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            mimeMessage.writeTo(buffer);
            String raw = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(buffer.toByteArray());

            Message gmailMsg = new Message();
            gmailMsg.setRaw(raw);
            gmailOAuthService.getService()
                    .users().messages().send("me", gmailMsg)
                    .execute();

            log.info("[BillingNotification] Email sent: to={}, subject={}", to, subject);
        } catch (Exception e) {
            log.error("[BillingNotification] Failed to send email: to={}, subject={}", to, subject, e);
            // Non-fatal — don't throw
        }
    }
}
