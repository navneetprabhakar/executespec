package com.navneet.executespec.auth.service;

import com.google.api.services.gmail.model.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Properties;

/**
 * Sends transactional emails via the Gmail API (OAuth2).
 *
 * All methods are @Async — callers are never blocked by email I/O.
 * If the Gmail service is unavailable the call is silently skipped
 * (the service logs a WARN and continues).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailService {

    private final GmailOAuthService gmailOAuthService;
    private final TemplateEngine templateEngine;

    @Value("${app.mail.base-url:http://localhost:5173}")
    private String baseUrl;

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
        Context ctx = new Context();
        ctx.setVariable("newUserEmail", newUserEmail);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(adminEmail, "New user registered: " + newUserEmail,
                "email/new-user-alert", ctx);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Renders a Thymeleaf template to HTML, wraps it in a MIME message,
     * base64url-encodes it, and delivers it via the Gmail API.
     *
     * The Google client library transparently refreshes the OAuth access token
     * before the API call if it has expired, using the stored refresh token.
     */
    private void sendHtml(String to, String subject, String template, Context ctx) {
        if (!gmailOAuthService.isAvailable()) {
            log.warn("Gmail service unavailable — skipping email to {} (subject: {})", to, subject);
            return;
        }
        try {
            // 1. Render HTML body via Thymeleaf
            String html = templateEngine.process(template, ctx);

            // 2. Build a MIME message (no SMTP session needed — just using jakarta.mail
            //    as a convenient MIME builder; we never actually connect to an SMTP server)
            Session session = Session.getDefaultInstance(new Properties());
            MimeMessage mimeMessage = new MimeMessage(session);
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(gmailOAuthService.getSenderEmail());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            // 3. Serialize the MIME message to bytes and base64url-encode it
            //    (Gmail API requires the raw RFC 2822 message encoded this way)
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            mimeMessage.writeTo(buffer);
            String raw = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(buffer.toByteArray());

            // 4. Send via Gmail API — access token is auto-refreshed if needed
            Message gmailMsg = new Message();
            gmailMsg.setRaw(raw);
            gmailOAuthService.getService()
                    .users().messages().send("me", gmailMsg)
                    .execute();

            log.info("Email sent to {} via Gmail API — subject: {}", to, subject);
        } catch (Exception e) {
            log.warn("Failed to send email to {} (subject: {}): {}", to, subject, e.getMessage());
        }
    }
}
