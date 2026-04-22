package com.navneet.executespec.auth.controller;

import com.navneet.executespec.auth.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * v2.6.0 — internal reminder endpoint for the claude.ai remote-trigger
 * scheduler. The trigger curls here with the pre-rendered reminder body
 * and we forward it as email to {@code app.mail.admin-address}.
 *
 * <p>Auth model: shared-secret {@code X-Admin-Reminder-Token} header
 * (constant-time compared against {@code app.mail.admin-reminder-token},
 * sourced from the {@code ADMIN_REMINDER_TOKEN} env var). Deliberately
 * NOT under {@code /api/admin/**} — that path is JWT-gated. This
 * endpoint uses the same auth pattern as {@code /api/webhooks/*}:
 * public on the URL, secret-gated in the controller.
 *
 * <p>Not intended for user traffic. Rate-limit upstream if exposed
 * beyond a single cron trigger.
 */
@RestController
@RequestMapping("/api/internal/reminder")
@RequiredArgsConstructor
@Slf4j
public class AdminReminderController {

    private final MailService mailService;

    @Value("${app.mail.admin-reminder-token:}")
    private String expectedToken;

    public record ReminderRequest(String subject, String body) {}

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(
            @RequestHeader(value = "X-Admin-Reminder-Token", required = false) String token,
            @RequestBody ReminderRequest req) {

        if (expectedToken == null || expectedToken.isBlank()) {
            log.warn("/api/internal/reminder/send hit but ADMIN_REMINDER_TOKEN is not configured — rejecting");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Reminder endpoint not configured");
        }
        if (token == null || !constantTimeEquals(token, expectedToken)) {
            log.warn("/api/internal/reminder/send rejected — invalid or missing token");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid reminder token");
        }
        if (req == null || req.subject() == null || req.subject().isBlank()
                || req.body() == null || req.body().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "subject and body are required");
        }

        String to = mailService.getAdminAddress();
        mailService.sendAdminReminderEmail(to, req.subject(), req.body());
        log.info("Admin reminder dispatched: to={} subjectLen={} bodyLen={}",
                to, req.subject().length(), req.body().length());

        return ResponseEntity.ok(Map.of(
                "sent", true,
                "to", to,
                "subjectLength", req.subject().length(),
                "bodyLength", req.body().length()));
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
