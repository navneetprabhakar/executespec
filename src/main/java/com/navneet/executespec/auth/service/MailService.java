package com.navneet.executespec.auth.service;

import com.google.api.services.gmail.model.Message;
import jakarta.annotation.PostConstruct;
import jakarta.mail.Session;
import jakarta.mail.Transport;
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
        Context ctx = new Context();
        ctx.setVariable("newUserEmail", newUserEmail);
        ctx.setVariable("baseUrl", baseUrl);
        sendHtml(adminEmail, "New user registered: " + newUserEmail,
                "email/new-user-alert", ctx);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void sendHtml(String to, String subject, String template, Context ctx) {
        if ("smtp".equals(transport)) {
            sendViaSmtp(to, subject, template, ctx);
        } else {
            sendViaGmailApi(to, subject, template, ctx);
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
}
