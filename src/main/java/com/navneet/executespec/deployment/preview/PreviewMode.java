package com.navneet.executespec.deployment.preview;

/**
 * How a preview is rendered to the user.
 * Orthogonal to {@link PreviewProjectType} (language/framework detection).
 */
public enum PreviewMode {
    /** Backend REST API exposing JSON endpoints directly. */
    REST,
    /** Full stack app requiring proxying for both API and Frontend. */
    FULL_STACK,
    /** CLI app wrapped by ttyd — web-based terminal emulator on port 8080. */
    CLI_TERMINAL,
    /** MCP server wrapped by @modelcontextprotocol/inspector — web UI on port 8080. */
    MCP_INSPECTOR,
    /** Expo-managed React Native app previewed via Expo Go on the user's phone —
     *  backend serves the Metro bundle + manifest; the frontend renders a QR code
     *  pointing at the manifest URL, not an iframe. */
    MOBILE_EXPO_GO
}
