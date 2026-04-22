package com.navneet.executespec.auth.service;

import com.google.api.services.gmail.model.Message;
import jakarta.annotation.PostConstruct;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Properties;

/**
 * Sends transactional emails via either SMTP or Gmail API.
 *
 * <p>Transport is selected by {@code app.mail.transport}:
 * <ul>
 *   <li>{@code smtp} — uses Jakarta Mail with SMTP (recommended for server,
 *       works with Gmail App Passwords — no OAuth, no token expiry)</li>
 *   <li>{@code gmail-api} — uses Gmail REST API with OAuth2 (Desktop flow,
 *       good for local dev but tokens expire in Testing mode)</li>
 * </ul>
 *
 * All methods are @Async — callers are never blocked by email I/O.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailService {

    private final GmailOAuthService gmailOAuthService;
    private final TemplateEngine templateEngine;

    @Value("${app.mail.base-url:http://localhost:5173}")
    private String baseUrl;

    @Value("${app.mail.transport:smtp}")
    private String transport;

    @Value("${app.mail.from:admin@executespec.ai}")
    private String fromEmail;

    @Value("${app.mail.admin-address:admin@executespec.ai}")
    private String adminAddress;

    public String getAdminAddress() {
        return adminAddress;
    }

    // SMTP settings
    @Value("${app.mail.smtp.host:smtp.gmail.com}")
    private String smtpHost;

    @Value("${app.mail.smtp.port:587}")
    private int smtpPort;

    @Value("${app.mail.smtp.username:}")
    private String smtpUsername;

    @Value("${app.mail.smtp.password:}")
    private String smtpPassword;

    private Session smtpSession;

    @PostConstruct
    void initSmtp() {
        if (!"smtp".equals(transport)) return;
        if (smtpUsername.isBlank() || smtpPassword.isBlank()) {
            log.warn("SMTP transport selected but SMTP_USERNAME/SMTP_PASSWORD not set — emails will be skipped");
            return;
        }
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        props.put("mail.smtp.ssl.trust", smtpHost);
        smtpSession = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(smtpUsername, smtpPassword);
            }
        });
        log.info("SMTP mail transport ready — host: {}:{}, from: {}", smtpHost, smtpPort, fromEmail);
    }

    // ── public send methods ──────────────────────────────────────────────────

    @Async
    public void sendVerificationEmail(String toEmail, String firstName, String token) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("verifyUrl", baseUrl + "/verify-email?token=" + token);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, "Verify your executespec account", "email/verify-email", ctx);
    }

    @Async
    public void sendPasswordResetEmail(String toEmail, String firstName, String token) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("resetUrl", baseUrl + "/reset-password?token=" + token);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, "Reset your executespec password", "email/reset-password", ctx);
    }

    @Async
    public void sendTemporaryPasswordEmail(String toEmail, String firstName, String tempPassword) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("tempPassword", tempPassword);
        ctx.setVariable("loginUrl", baseUrl + "/login");
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, "Your executespec temporary password", "email/temp-password", ctx);
    }

    @Async
    public void sendOrgInviteEmail(String toEmail, String orgName, String token) {
        Context ctx = new Context();
        ctx.setVariable("orgName", orgName);
        ctx.setVariable("acceptUrl", baseUrl + "/accept-invite?token=" + token);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, "You've been invited to " + orgName + " on executespec",
                "email/org-invite", ctx);
    }

    @Async
    public void sendEscalationToAdmin(String adminEmail, String invitedEmail,
                                       String orgName, String invitationId) {
        Context ctx = new Context();
        ctx.setVariable("invitedEmail", invitedEmail);
        ctx.setVariable("orgName", orgName);
        ctx.setVariable("approveUrl", baseUrl + "/admin/invitations?id=" + invitationId + "&action=approve");
        ctx.setVariable("rejectUrl",  baseUrl + "/admin/invitations?id=" + invitationId + "&action=reject");
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(adminEmail,
                "Cross-domain invite approval required: " + invitedEmail + " → " + orgName,
                "email/escalation-admin", ctx);
    }

    @Async
    public void sendInviteRejectedEmail(String orgAdminEmail, String invitedEmail) {
        Context ctx = new Context();
        ctx.setVariable("invitedEmail", invitedEmail);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(orgAdminEmail,
                "Invite for " + invitedEmail + " was rejected by executespec admin",
                "email/invite-rejected", ctx);
    }

    @Async
    public void sendNewUserAlertToAdmin(String adminEmail, String newUserEmail) {
        sendNewUserAlertToAdmin(adminEmail, newUserEmail, null, null, "Email");
    }

    @Async
    public void sendNewUserAlertToAdmin(String adminEmail, String newUserEmail,
                                         String firstName, String lastName,
                                         String signupMethod) {
        Context ctx = new Context();
        ctx.setVariable("newUserEmail", newUserEmail);
        ctx.setVariable("email", newUserEmail);
        ctx.setVariable("firstName", firstName != null ? firstName : "");
        ctx.setVariable("lastName", lastName != null ? lastName : "");
        ctx.setVariable("signupMethod", signupMethod != null ? signupMethod : "Email");
        ctx.setVariable("baseUrl", baseUrl);
        ctx.setVariable("adminUrl", baseUrl + "/admin");
        sendHtml(adminEmail, "New user registered: " + newUserEmail,
                "email/new-user-alert", ctx);
    }

    /**
     * v2.6.0 — generic admin reminder email. Body is rendered verbatim inside
     * a Graphite+Signal-styled wrapper. Used by the remote-trigger scheduler
     * via {@code POST /api/internal/reminder/send}.
     */
    @Async
    public void sendAdminReminderEmail(String to, String subject, String body) {
        Context ctx = new Context();
        ctx.setVariable("subject", subject);
        ctx.setVariable("body", body);
        ctx.setVariable("baseUrl", baseUrl);
        ctx.setVariable("adminUrl", baseUrl + "/admin");
        sendHtml(to, subject, "email/admin-reminder", ctx);
    }

    /**
     * v2.6.0 — weekly admin summary. Rendered from {@code email/weekly-admin-summary.html}.
     * Expects a {@code summary} map with keys: periodStart, periodEnd, newSignups,
     * newSignupList (List&lt;Map&lt;String,String&gt;&gt; with name/email/signupMethod/createdAt),
     * totalRuns, completedRuns, failedRuns, partialRuns, completionPct,
     * activeUsers, openApprovalGates, baseUrl, adminUrl.
     */
    @Async
    public void sendWeeklyAdminSummary(String adminEmail, java.util.Map<String, Object> summary) {
        Context ctx = new Context();
        summary.forEach(ctx::setVariable);
        ctx.setVariable("baseUrl", baseUrl);
        ctx.setVariable("adminUrl", baseUrl + "/admin");
        String subject = String.format("Weekly summary · %s signups · %s runs · %s",
                summary.getOrDefault("newSignups", 0),
                summary.getOrDefault("totalRuns", 0),
                summary.getOrDefault("periodLabel", ""));
        sendHtml(adminEmail, subject, "email/weekly-admin-summary", ctx);
    }

    @Async
    public void sendContactFeedbackEmail(String adminEmail, String userEmail,
                                          String userName, String subject, String feedbackBody) {
        Context ctx = new Context();
        ctx.setVariable("userEmail", userEmail);
        ctx.setVariable("userName", userName);
        ctx.setVariable("feedbackSubject", subject);
        ctx.setVariable("feedbackBody", feedbackBody);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(adminEmail, "Contact feedback: " + subject,
                "email/contact-feedback", ctx);
    }

    @Async
    public void sendWelcomeEmail(String toEmail, String firstName, String tier) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("tier", tier);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, "Welcome to executespec!", "email/welcome", ctx);
    }

    @Async
    public void sendTierUpgradeEmail(String toEmail, String firstName, String planName, String tier) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("planName", planName);
        ctx.setVariable("tier", tier);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, "Your executespec plan has been upgraded", "email/tier-upgraded", ctx);
    }

    @Async
    public void sendSuspensionEmail(String toEmail, String firstName) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, "Your executespec account has been suspended", "email/account-suspended", ctx);
    }

    @Async
    public void sendBonusRunsEmail(String toEmail, String firstName, int quantity) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("quantity", quantity);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, quantity + " bonus runs added to your executespec account", "email/bonus-runs", ctx);
    }

    @Async
    public void sendInvoiceEmail(String toEmail, String firstName, String invoiceNumber,
                                  long subtotalPaise, long gstPaise, long totalAmountPaise,
                                  byte[] pdfBytes, String pdfFilename) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("invoiceNumber", invoiceNumber);
        ctx.setVariable("subtotalFormatted", String.format("\u20B9%,.2f", subtotalPaise / 100.0));
        ctx.setVariable("gstFormatted", String.format("\u20B9%,.2f", gstPaise / 100.0));
        ctx.setVariable("totalFormatted", String.format("\u20B9%,.2f", totalAmountPaise / 100.0));
        ctx.setVariable("hasGst", gstPaise > 0);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtmlWithAttachment(toEmail, "Invoice " + invoiceNumber + " \u2014 executespec",
                "email/invoice-issued", ctx, pdfBytes, pdfFilename, "application/pdf");
    }

    @Async
    public void sendDunningReminder(String toEmail, String firstName, String planName, String urgency) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("planName", planName);
        ctx.setVariable("urgency", urgency);
        ctx.setVariable("baseUrl", baseUrl);
        String subject = switch (urgency) {
            case "FINAL" -> "Final notice: Update your payment — executespec";
            case "URGENT" -> "Action required: Payment issue — executespec";
            default -> "Payment reminder — executespec";
        };
        sendHtml(toEmail, subject, "email/dunning-reminder", ctx);
    }

    @Async
    public void sendRenewalReminder(String toEmail, String firstName, String planName, String renewalDate) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("planName", planName);
        ctx.setVariable("renewalDate", renewalDate);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, "Your subscription renews soon — executespec", "email/renewal-reminder", ctx);
    }

    // ── Usage report emails ─────────────────────────────────────────────────

    @Async
    public void sendUserUsageReport(String toEmail, String firstName, String periodLabel,
                                     String periodRange,
                                     com.navneet.executespec.report.UsageReportService.UserUsageData data) {
        Context ctx = new Context();
        ctx.setVariable("firstName", firstName);
        ctx.setVariable("period", periodLabel);
        ctx.setVariable("periodRange", periodRange);
        populateUsageContext(ctx, data.totalRuns(), data.completedRuns(), data.failedRuns(),
                data.totalInputTokens(), data.totalOutputTokens(), data.estimatedCostUsd(),
                data.prevTotalRuns(), data.prevTotalTokens(), data.prevCostUsd(), data.projects());
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, "Your " + periodLabel.toLowerCase() + " usage report — executespec",
                "email/usage-report-user", ctx);
    }

    @Async
    public void sendOrgUsageReport(String toEmail, String orgName, String periodLabel,
                                    String periodRange,
                                    com.navneet.executespec.report.UsageReportService.OrgUsageData data) {
        Context ctx = new Context();
        ctx.setVariable("orgName", orgName);
        ctx.setVariable("period", periodLabel);
        ctx.setVariable("periodRange", periodRange);
        ctx.setVariable("memberCount", data.memberCount());
        populateUsageContext(ctx, data.totalRuns(), data.completedRuns(), data.failedRuns(),
                data.totalInputTokens(), data.totalOutputTokens(), data.estimatedCostUsd(),
                data.prevTotalRuns(), data.prevTotalTokens(), data.prevCostUsd(), data.projects());

        // Top users with percentage bars
        int maxRuns = data.topUsers().isEmpty() ? 1
                : data.topUsers().stream().mapToInt(com.navneet.executespec.report.UsageReportService.TopUser::runCount).max().orElse(1);
        var topUsersWithPct = data.topUsers().stream()
                .map(u -> java.util.Map.of(
                        "name", u.name(), "email", u.email(),
                        "runCount", u.runCount(),
                        "tokens", formatTokens(u.tokens()),
                        "pct", Math.max(5, (int) (u.runCount() * 100.0 / maxRuns))))
                .toList();
        ctx.setVariable("topUsers", topUsersWithPct);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(toEmail, orgName + " — " + periodLabel.toLowerCase() + " usage report — executespec",
                "email/usage-report-org", ctx);
    }

    private void populateUsageContext(Context ctx, int totalRuns, int completed, int failed,
                                       long inTok, long outTok, double cost,
                                       int prevRuns, long prevTokens, double prevCost,
                                       java.util.List<com.navneet.executespec.report.UsageReportService.ProjectBreakdown> projects) {
        ctx.setVariable("totalRuns", totalRuns);
        ctx.setVariable("completedRuns", completed);
        ctx.setVariable("failedRuns", failed);
        long totalTokens = inTok + outTok;
        ctx.setVariable("totalTokens", formatTokens(totalTokens));
        ctx.setVariable("estimatedCost", String.format("$%.2f", cost));

        // Percentages for bar chart
        int completedPct = totalRuns > 0 ? (int) (completed * 100.0 / totalRuns) : 0;
        int failedPct = totalRuns > 0 ? (int) (failed * 100.0 / totalRuns) : 0;
        ctx.setVariable("completedPct", completedPct);
        ctx.setVariable("failedPct", failedPct);

        // Delta comparisons
        ctx.setVariable("runsDelta", formatDelta(totalRuns, prevRuns));
        ctx.setVariable("runsDeltaPositive", totalRuns >= prevRuns);
        ctx.setVariable("tokensDelta", formatDelta(totalTokens, prevTokens));
        ctx.setVariable("tokensDeltaPositive", totalTokens >= prevTokens);
        ctx.setVariable("costDelta", formatDelta(cost, prevCost));
        ctx.setVariable("costDeltaPositive", cost >= prevCost);

        // Projects with percentage bars
        int maxProjectRuns = projects.isEmpty() ? 1
                : projects.stream().mapToInt(com.navneet.executespec.report.UsageReportService.ProjectBreakdown::runs).max().orElse(1);
        var projectsWithPct = projects.stream()
                .map(p -> java.util.Map.of(
                        "name", p.projectName(),
                        "runs", p.runs(),
                        "tokens", formatTokens(p.tokens()),
                        "pct", Math.max(5, (int) (p.runs() * 100.0 / maxProjectRuns))))
                .toList();
        ctx.setVariable("projects", projectsWithPct);
        ctx.setVariable("hasProjects", !projects.isEmpty());
    }

    private String formatTokens(long tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    private String formatDelta(double current, double previous) {
        if (previous == 0) return current > 0 ? "+100%" : "—";
        double pct = ((current - previous) / previous) * 100;
        return (pct >= 0 ? "+" : "") + String.format("%.0f%%", pct);
    }

    private String formatDelta(long current, long previous) {
        return formatDelta((double) current, (double) previous);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void sendHtml(String to, String subject, String template, Context ctx) {
        if ("smtp".equals(transport)) {
            sendViaSmtp(to, subject, template, ctx);
        } else {
            sendViaGmailApi(to, subject, template, ctx);
        }
    }

    private void sendHtmlWithAttachment(String to, String subject, String template, Context ctx,
                                          byte[] attachmentBytes, String attachmentName, String contentType) {
        if ("smtp".equals(transport)) {
            sendViaSmtpWithAttachment(to, subject, template, ctx, attachmentBytes, attachmentName, contentType);
        } else {
            sendViaGmailApiWithAttachment(to, subject, template, ctx, attachmentBytes, attachmentName, contentType);
        }
    }

    /** Send via SMTP (Jakarta Mail Transport) — works with Gmail App Passwords. */
    private void sendViaSmtp(String to, String subject, String template, Context ctx) {
        if (smtpSession == null) {
            log.warn("SMTP not configured — skipping email to {} (subject: {})", to, subject);
            return;
        }
        try {
            String html = templateEngine.process(template, ctx);

            MimeMessage message = new MimeMessage(smtpSession);
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            Transport.send(message);
            log.info("Email sent to {} via SMTP — subject: {}", to, subject);
        } catch (Exception e) {
            log.warn("Failed to send email to {} via SMTP (subject: {}): {}", to, subject, e.getMessage());
        }
    }

    private void sendViaSmtpWithAttachment(String to, String subject, String template, Context ctx,
                                              byte[] attachmentBytes, String attachmentName, String contentType) {
        if (smtpSession == null) {
            log.warn("SMTP not configured — skipping email to {} (subject: {})", to, subject);
            return;
        }
        try {
            String html = templateEngine.process(template, ctx);

            MimeMessage message = new MimeMessage(smtpSession);
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            helper.addAttachment(attachmentName, new ByteArrayResource(attachmentBytes), contentType);

            Transport.send(message);
            log.info("Email with attachment sent to {} via SMTP — subject: {}", to, subject);
        } catch (Exception e) {
            log.warn("Failed to send email with attachment to {} via SMTP (subject: {}): {}", to, subject, e.getMessage());
        }
    }

    /** Send via Gmail REST API (OAuth2) — original transport. */
    private void sendViaGmailApi(String to, String subject, String template, Context ctx) {
        if (!gmailOAuthService.isAvailable()) {
            log.warn("Gmail API unavailable — skipping email to {} (subject: {})", to, subject);
            return;
        }
        try {
            String html = templateEngine.process(template, ctx);

            Session session = Session.getDefaultInstance(new Properties());
            MimeMessage mimeMessage = new MimeMessage(session);
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(gmailOAuthService.getSenderEmail());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

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

            log.info("Email sent to {} via Gmail API — subject: {}", to, subject);
        } catch (Exception e) {
            log.warn("Failed to send email to {} via Gmail API (subject: {}): {}", to, subject, e.getMessage());
        }
    }

    private void sendViaGmailApiWithAttachment(String to, String subject, String template, Context ctx,
                                                 byte[] attachmentBytes, String attachmentName, String contentType) {
        if (!gmailOAuthService.isAvailable()) {
            log.warn("Gmail API unavailable — skipping email to {} (subject: {})", to, subject);
            return;
        }
        try {
            String html = templateEngine.process(template, ctx);

            Session session = Session.getDefaultInstance(new Properties());
            MimeMessage mimeMessage = new MimeMessage(session);
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(gmailOAuthService.getSenderEmail());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            helper.addAttachment(attachmentName, new ByteArrayResource(attachmentBytes), contentType);

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

            log.info("Email with attachment sent to {} via Gmail API — subject: {}", to, subject);
        } catch (Exception e) {
            log.warn("Failed to send email with attachment to {} via Gmail API (subject: {}): {}", to, subject, e.getMessage());
        }
    }
}
