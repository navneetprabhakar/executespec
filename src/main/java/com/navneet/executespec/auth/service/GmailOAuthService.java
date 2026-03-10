package com.navneet.executespec.auth.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Manages Gmail API access via OAuth 2.0 (Desktop / Installed App credentials).
 *
 * First-run flow:
 *   1. Place credentials.json in the path configured by app.gmail.credentials-path
 *   2. Start the app — a browser window opens at localhost:8888 for Google consent
 *   3. Grant access; tokens are written to app.gmail.tokens-dir/StoredCredential
 *   4. All subsequent starts load the stored tokens and refresh them automatically
 *
 * The Google client library refreshes the access token transparently before each
 * API call, so no manual refresh plumbing is needed in normal operation.
 */
@Service
@Slf4j
public class GmailOAuthService {

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    /** Only need SEND scope — never read user's inbox. */
    private static final List<String> SCOPES = List.of(GmailScopes.GMAIL_SEND);

    @Value("${app.gmail.enabled:false}")
    private boolean enabled;

    /** Absolute or relative path to the downloaded credentials.json file. */
    @Value("${app.gmail.credentials-path:credentials.json}")
    private String credentialsPath;

    /**
     * Directory where the OAuth tokens (StoredCredential) are persisted.
     * Defaults to ~/.executespec/gmail-tokens — never commit this directory.
     */
    @Value("${app.gmail.tokens-dir:${user.home}/.executespec/gmail-tokens}")
    private String tokensDir;

    /** The Gmail address that will appear as the From: sender. */
    @Value("${app.gmail.sender-email:}")
    private String senderEmail;

    @Value("${app.gmail.app-name:executespec}")
    private String appName;

    private Gmail gmailService;

    // ── lifecycle ────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Gmail OAuth service disabled (app.gmail.enabled=false) — emails will be skipped");
            return;
        }
        try {
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            Credential credential = authorize(httpTransport);
            gmailService = new Gmail.Builder(httpTransport, JSON_FACTORY, credential)
                    .setApplicationName(appName)
                    .build();
            log.info("Gmail OAuth service ready — sender: {}", senderEmail);
        } catch (Exception e) {
            log.error("Gmail OAuth init failed: {} — emails will not be sent until fixed.",
                    e.getMessage(), e);
        }
    }

    // ── public API ───────────────────────────────────────────────────────────

    public boolean isAvailable() {
        return gmailService != null;
    }

    public Gmail getService() {
        return gmailService;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    /**
     * Force-refreshes the stored access token.
     * Normally not needed — the Google HTTP client refreshes automatically
     * when the token is expired (checked before every API call).
     * Exposed here for an admin endpoint or a cron job if desired.
     *
     * @return true if the token was successfully refreshed
     */
    public boolean forceRefreshToken() {
        if (!isAvailable()) {
            log.warn("Gmail service not initialised — cannot force-refresh token");
            return false;
        }
        try {
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            Credential credential = authorize(httpTransport);
            boolean ok = credential.refreshToken();
            log.info("Gmail token force-refreshed: {}", ok);
            return ok;
        } catch (Exception e) {
            log.error("Gmail token force-refresh failed: {}", e.getMessage(), e);
            return false;
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Runs the OAuth2 installed-app authorisation flow.
     *
     * <ul>
     *   <li>First run: opens a browser tab → user grants consent → callback to
     *       localhost:8888 → tokens written to tokensDir/StoredCredential.</li>
     *   <li>Subsequent runs: loads StoredCredential from disk; the Google client
     *       library calls refreshToken() automatically when the access token is
     *       within the expiry window.</li>
     * </ul>
     */
    private Credential authorize(NetHttpTransport httpTransport) throws Exception {
        File credFile = new File(credentialsPath);
        if (!credFile.exists()) {
            throw new IllegalStateException(
                    "credentials.json not found at: " + credFile.getAbsolutePath() +
                    "\nDownload it from: Google Cloud Console → APIs & Services → Credentials " +
                    "→ your OAuth 2.0 Client → Download JSON");
        }

        GoogleClientSecrets clientSecrets;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(credFile), StandardCharsets.UTF_8)) {
            clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
        }

        File tokenDirectory = new File(tokensDir);
        if (!tokenDirectory.exists() && !tokenDirectory.mkdirs()) {
            throw new IllegalStateException("Cannot create token directory: " + tokensDir);
        }

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                // FileDataStoreFactory persists tokens so the browser only opens once
                .setDataStoreFactory(new FileDataStoreFactory(tokenDirectory))
                // "offline" access_type guarantees a refresh_token is returned
                .setAccessType("offline")
                .build();

        // Port 8888 receives the redirect after the user clicks "Allow" in their browser.
        // Change the port if 8888 is occupied on your machine.
        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                .setPort(8888)
                .build();

        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver)
                .authorize("user");

        log.info("Gmail OAuth credential loaded — access token expiry: {}ms",
                credential.getExpirationTimeMilliseconds());
        return credential;
    }
}
