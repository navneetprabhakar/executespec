package com.navneet.executespec.state;

/**
 * Source of an apply-suggestion action. Used to prefix task IDs
 * so they are visually distinguishable in the task graph.
 *
 * <ul>
 *   <li>{@code COPILOT} — suggestion from the Chat Copilot → prefix: {@code _cop}</li>
 *   <li>{@code REVIEW}  — suggestion from the Code Review   → prefix: {@code _rev}</li>
 * </ul>
 */
public enum ApplySource {
    COPILOT("cop"),
    REVIEW("rev");

    private final String prefix;

    ApplySource(String prefix) {
        this.prefix = prefix;
    }

    /** Returns the short prefix used in task IDs, e.g. "cop" or "rev". */
    public String prefix() {
        return prefix;
    }
}
