package com.navneet.executespec.composer;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R-339 — cross-product spec composer.  Substitutes user-supplied
 * slot values into a variant's {@code spec_template} (Mustache-style
 * {@code {{slot_name}}} placeholders) before submitting the run to
 * the V2 pipeline.
 *
 * <p>Pure static utility — no I/O, no Spring ceremony.
 *
 * <p>Substitution rules:
 * <ul>
 *   <li>{@code {{slot}}} → user-supplied value when present + non-blank.</li>
 *   <li>{@code {{slot}}} → slot's declared default when user value
 *       absent / blank AND default is set.</li>
 *   <li>{@code {{slot}}} → empty string when neither user value nor
 *       default is set (template should already have failed validation
 *       upstream if the slot was required).</li>
 *   <li>Any unknown placeholder is left as-is so it's visible in
 *       the rendered spec — better than silently dropping it.</li>
 * </ul>
 *
 * <p>Secrets in the rendered spec are NOT logged.
 */
public final class SpecComposer {

    /** Matches {@code {{slot_name}}} with optional surrounding whitespace. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");

    private SpecComposer() {}

    /**
     * Compose the final spec text.
     *
     * @param template the variant's {@code spec_template}.
     * @param userValues map of user-supplied slot values; null treated as empty.
     * @param defaults map of slot defaults from the slot definitions; null treated as empty.
     */
    public static String compose(String template, Map<String, String> userValues, Map<String, String> defaults) {
        if (template == null || template.isEmpty()) return "";
        Map<String, String> safeUser = userValues == null ? Map.of() : userValues;
        Map<String, String> safeDefaults = defaults == null ? Map.of() : defaults;

        StringBuilder out = new StringBuilder(template.length());
        Matcher m = PLACEHOLDER.matcher(template);
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            String slotName = m.group(1);
            String resolved = resolve(slotName, safeUser, safeDefaults);
            if (resolved != null) {
                out.append(resolved);
            } else {
                // Unknown placeholder — leave verbatim so it's visible.
                out.append(m.group(0));
            }
            last = m.end();
        }
        out.append(template, last, template.length());
        return out.toString();
    }

    /** Convenience overload that builds defaults from a list of {@link SlotValidator.SlotDef}. */
    public static String compose(String template, Map<String, String> userValues,
                                 java.util.List<SlotValidator.SlotDef> slots,
                                 Map<String, String> slotDefaults) {
        // The slotDefaults arg is the authoritative default map; the
        // slots list is here for future per-slot type-aware coercion
        // (e.g. BOOLEAN → "true" lowercase).  v1 just delegates.
        return compose(template, userValues, slotDefaults);
    }

    private static String resolve(String slotName, Map<String, String> userValues, Map<String, String> defaults) {
        String user = userValues.get(slotName);
        if (user != null && !user.isBlank()) return user;
        String dflt = defaults.get(slotName);
        if (dflt != null && !dflt.isBlank()) return dflt;
        // Slot is declared but empty + no default — return ""
        // (validation upstream rejected required+blank).  null
        // means "we don't know this slot at all" → leave verbatim.
        return userValues.containsKey(slotName) || defaults.containsKey(slotName) ? "" : null;
    }

    /** Build a defaults map from a list of {@link SlotValidator.SlotDef}.
     *  Skips slots whose default is null/blank. */
    public static Map<String, String> defaultsFrom(java.util.List<SlotDefWithDefault> slots) {
        Map<String, String> out = new HashMap<>();
        for (SlotDefWithDefault s : slots) {
            if (s.defaultValue() != null && !s.defaultValue().isBlank()) {
                out.put(s.name(), s.defaultValue());
            }
        }
        return out;
    }

    /** Like {@link SlotValidator.SlotDef} but carries the default value. */
    public record SlotDefWithDefault(String name, String defaultValue) {}
}
