package com.navneet.executespec.validator.prompt;

import com.navneet.executespec.validator.model.ValidationError;
import com.navneet.executespec.validator.model.ValidationResult;

public final class RetryPromptBuilder {

    private RetryPromptBuilder() {}

    public static String build(ValidationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== VALIDATION FAILED — FIX REQUIRED ===\n\n");
        sb.append("The code you generated failed validation. Fix ONLY the errors listed below. Do NOT rewrite unaffected files.\n\n");

        sb.append("--- ERRORS ---\n");
        for (ValidationError error : result.getErrors()) {
            sb.append(String.format("File: %s | Line %d, Col %d | [%s] %s: %s\n",
                    error.getFile(), error.getLine(), error.getColumn(),
                    error.getGate(), error.getCode(), error.getMessage()));
            if (error.getSuggestion() != null && !error.getSuggestion().isEmpty()) {
                sb.append(String.format("  Suggestion: %s\n", error.getSuggestion()));
            }
        }

        if (result.getRawOutput() != null && !result.getRawOutput().isEmpty()) {
            sb.append("\n--- RAW TOOL OUTPUT ---\n");
            String raw = result.getRawOutput();
            if (raw.length() > 3000) { raw = raw.substring(0, 3000) + "\n... [truncated]"; }
            sb.append(raw);
        }

        sb.append("\n\n=== END VALIDATION ERRORS ===\n");
        sb.append("Fix the errors above. Return COMPLETE corrected files using writeFile / write_file tool calls.\n");
        return sb.toString();
    }

    public static String buildSummary(ValidationResult result) {
        if (result.getErrors().isEmpty()) return "Validation passed";
        return String.format("%d errors in %s (first: %s)",
                result.getErrors().size(), result.getErrors().get(0).getFile(),
                result.getErrors().get(0).getMessage());
    }
}
