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
 * <h3>Local development (browser available):</h3>
 * <ol>
 *   <li>Place credentials.json in the path configured by app.gmail.credentials-path</li>
 *   <li>Start the app — a browser window opens at localhost:8888 for Google consent</li>
 *   <li>Grant access; tokens are written to app.gmail.tokens-dir/StoredCredential</li>
 *   <li>All subsequent starts load the stored tokens and refresh them automatically</li>
 * </ol>
 *
 * <h3>Server deployment (headless):</h3>
 * <ol>
 *   <li>Authorize locally first (steps above) to generate StoredCredential</li>
 *   <li>Upload credentials.json + StoredCredential to GCS deploy bucket under config/</li>
 *   <li>The GCE startup script downloads them to /opt/app/gmail/</li>
 *   <li>Set GMAIL_CREDENTIALS_PATH and GMAIL_TOKENS_DIR env vars</li>
 *   <li>The service loads stored tokens directly — no browser needed</li>
 * </ol>
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
     * Loads or obtains OAuth2 credentials.
     *
     * <p><b>If StoredCredential exists</b> (server or repeat local run): loads the
     * stored refresh token directly from the data store — no browser, no local server.
     * The Google client auto-refreshes the access token when it's within the expiry window.
     *
     * <p><b>If StoredCredential does NOT exist</b> (first local run): starts a
     * LocalServerReceiver on port 8888 and opens a browser for user consent.
     * This only works on a machine with a display — on a headless server with no
     * stored tokens, the service logs an error and stays unavailable.
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
                .setDataStoreFactory(new FileDataStoreFactory(tokenDirectory))
                .setAccessType("offline")
                .build();

        // Try loading existing stored credential first (works on server without browser)
        Credential storedCredential = flow.loadCredential("user");
        if (storedCredential != null && storedCredential.getRefreshToken() != null) {
            log.info("Gmail OAuth: loaded stored credential (token expiry: {}ms)",
                    storedCredential.getExpirationTimeMilliseconds());
            return storedCredential;
        }

        // No stored credential — need browser-based consent flow.
        // Only attempt this if we're NOT on a headless server.
        if (isHeadless()) {
            throw new IllegalStateException(
                    "No stored Gmail OAuth tokens found and running on headless server. " +
                    "First authorize locally, then upload StoredCredential to the server. " +
                    "Steps: 1) Run app locally with GMAIL_ENABLED=true → browser opens → grant consent. " +
                    "2) Upload ~/.executespec/gmail-tokens/StoredCredential to " +
                    "gs://<deploy-bucket>/config/gmail/StoredCredential. " +
                    "3) Upload credentials.json to gs://<deploy-bucket>/config/gmail/credentials.json. " +
                    "4) Redeploy — startup script downloads them automatically.");
        }

        log.info("Gmail OAuth: no stored tokens found — starting browser consent flow on port 8888");
        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                .setPort(8888)
                .build();

        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver)
                .authorize("user");

        log.info("Gmail OAuth credential obtained — access token expiry: {}ms",
                credential.getExpirationTimeMilliseconds());
        return credential;
    }

    /**
     * Detects if running on a headless server (no display available).
     * On GCE/CI, DISPLAY is unset and java.awt.GraphicsEnvironment.isHeadless() returns true.
     */
    private boolean isHeadless() {
        // Check Java AWT headless flag
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return true;
        }
        // Check if DISPLAY is set (Linux/macOS)
        String display = System.getenv("DISPLAY");
        if (display == null || display.isBlank()) {
            // macOS doesn't use DISPLAY but has a built-in display, check for GCE metadata
            String gceMetadata = System.getenv("GCE_METADATA_HOST");
            if (gceMetadata != null) return true;
            // Also check for common server indicators
            File optApp = new File("/opt/app/executespec.env");
            if (optApp.exists()) return true;
        }
        return false;
    }
}
