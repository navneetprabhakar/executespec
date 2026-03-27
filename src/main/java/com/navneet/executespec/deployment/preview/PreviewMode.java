package com.navneet.executespec.deployment.preview;

/**
 * How a preview is rendered to the user.
 * Orthogonal to {@link PreviewProjectType} (language/framework detection).
 */
public enum PreviewMode {
    /** App serves HTTP directly (existing behaviour). */
    WEB,
    /** CLI app wrapped by ttyd — web-based terminal emulator on port 8080. */
    CLI_TERMINAL,
    /** MCP server wrapped by @modelcontextprotocol/inspector — web UI on port 8080. */
    MCP_INSPECTOR
}
