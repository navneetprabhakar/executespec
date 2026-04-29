package com.navneet.executespec.composer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R-339 — cross-product slot validator.  Used by Readymades (W4)
 * and Migrations (W4) to validate user-supplied slot values against
 * the slot definitions ({@code TEXT} / {@code SELECT} / {@code SECRET}
 * / {@code REPO_PICKER} / {@code FILE_PATH} / {@code NUMBER} /
 * {@code BOOLEAN}) declared by the recipe.
 *
 * <p>The slot enum lives in two packages — the readymade and migration
 * entities each have their own {@code Slot.Type} — but the validator
 * operates on the type's <em>name</em> (a {@code String}) so it works
 * for both products without a shared enum.
 *
 * <p>Returns a list of {@link Violation}s (empty when valid).  Callers
 * surface violations as 400 Bad Request bodies.
 */
@Slf4j
public final class SlotValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SlotValidator() {}

    /**
     * Validate a single slot value against its declaration.
     *
     * @param slot         the slot definition (see {@link SlotDef}).
     * @param value        the user-supplied raw value (string).
     * @return list of violations; empty when valid.
     */
    public static List<Violation> validate(SlotDef slot, String value) {
        List<Violation> out = new ArrayList<>();
        boolean blank = value == null || value.isBlank();

        if (slot.required() && blank) {
            out.add(new Violation(slot.name(), "REQUIRED", slot.displayName() + " is required"));
            return out;
        }
        if (blank) return out; // optional + blank = OK

        switch (slot.type().toUpperCase()) {
            case "TEXT" -> {
                if (value.length() > 4096) {
                    out.add(new Violation(slot.name(), "TOO_LONG",
                        slot.displayName() + " is too long (max 4096 chars)"));
                }
            }
            case "SECRET" -> {
                if (value.length() < 4) {
                    out.add(new Violation(slot.name(), "TOO_SHORT",
                        slot.displayName() + " looks empty / too short"));
                }
                if (value.length() > 4096) {
                    out.add(new Violation(slot.name(), "TOO_LONG",
                        slot.displayName() + " is too long (max 4096 chars)"));
                }
            }
            case "SELECT" -> {
                List<String> options = parseOptions(slot.optionsJson());
                if (!options.isEmpty() && !options.contains(value)) {
                    out.add(new Violation(slot.name(), "NOT_IN_OPTIONS",
                        slot.displayName() + " must be one of: " + String.join(", ", options)));
                }
            }
            case "NUMBER" -> {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    out.add(new Violation(slot.name(), "NOT_A_NUMBER",
                        slot.displayName() + " must be a number"));
                }
            }
            case "BOOLEAN" -> {
                String v = value.toLowerCase();
                if (!v.equals("true") && !v.equals("false")) {
                    out.add(new Violation(slot.name(), "NOT_A_BOOLEAN",
                        slot.displayName() + " must be 'true' or 'false'"));
                }
            }
            case "FILE_PATH" -> {
                if (value.startsWith("/") || value.contains("..")) {
                    out.add(new Violation(slot.name(), "INVALID_PATH",
                        slot.displayName() + " must be a workspace-relative path (no '/' prefix or '..')"));
                }
            }
            case "REPO_PICKER" -> {
                // Light shape check; full FK validity is checked by
                // the calling service against the user's project repos.
                if (value.length() > 36 && !value.contains("/")) {
                    // not a UUID nor "owner/repo"
                    out.add(new Violation(slot.name(), "INVALID_REPO",
                        slot.displayName() + " must be a project repo id or 'owner/repo' name"));
                }
            }
            default -> log.debug("[SlotValidator] unknown slot type {} — skipping", slot.type());
        }
        return out;
    }

    /**
     * Validate a full map of slot values against a list of slot
     * definitions.  Returns ALL violations (doesn't short-circuit
     * on the first failure) so the UI can render every issue at once.
     */
    public static List<Violation> validateAll(List<SlotDef> slots, Map<String, String> values) {
        List<Violation> out = new ArrayList<>();
        Map<String, String> safe = values == null ? Map.of() : values;
        for (SlotDef slot : slots) {
            String raw = safe.get(slot.name());
            out.addAll(validate(slot, raw));
        }
        // Reject unknown keys to catch typos / drift.
        for (String key : safe.keySet()) {
            boolean known = slots.stream().anyMatch(s -> s.name().equals(key));
            if (!known) {
                out.add(new Violation(key, "UNKNOWN_SLOT",
                    "Unknown slot '" + key + "' — not declared by this recipe/solution"));
            }
        }
        return out;
    }

    private static List<String> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) return List.of();
        try {
            JsonNode node = MAPPER.readTree(optionsJson);
            if (!node.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode el : node) {
                if (el.isTextual()) out.add(el.asText());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Stable shape for a slot definition that's product-agnostic.
     * The two products' Slot entities each provide an adapter that
     * builds a SlotDef on the way into validation (small adapter
     * lives in MigrationOrchestrator / ReadymadeOrchestrator).
     *
     * @param name        slot's stable id (used in {@code {{name}}}
     *                    placeholder + JSON key).
     * @param displayName form-field label.
     * @param type        TEXT / SELECT / SECRET / REPO_PICKER /
     *                    FILE_PATH / NUMBER / BOOLEAN.
     * @param required    true when the user MUST supply a value.
     * @param optionsJson JSON array string of allowed values when
     *                    {@code type == SELECT}; null otherwise.
     */
    public record SlotDef(String name, String displayName, String type,
                          boolean required, String optionsJson) {}

    /**
     * One violation reported by the validator.  {@code code} is a
     * stable machine-readable identifier (REQUIRED / TOO_LONG / ...);
     * {@code message} is the human-friendly form for the UI.
     */
    public record Violation(String slotName, String code, String message) {}
}
