package com.navneet.executespec.standards.enforcement.filepattern;

import com.navneet.executespec.contracts.detection.DetectorUtils;
import com.navneet.executespec.standards.model.Standard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * E3.5 — applies a {@link Standard.FilePatternEnforcement} against a
 * repo's file path list. Pure function; no I/O.
 *
 * <p>Two rule kinds:
 * <ul>
 *   <li>{@code forbidden_patterns}: every file matching any forbidden
 *       glob is a violation.</li>
 *   <li>{@code required_patterns}: at least one file must match the
 *       glob; if zero files match → one MISSING violation per pattern.</li>
 * </ul>
 *
 * <p>Glob matching reuses {@link DetectorUtils#matchesGlob} from E2.2 so
 * the {@code **}/{@code *} semantics stay consistent across the
 * standards + contract-detection subsystems.
 */
@Component
@Slf4j
public class FilePatternEnforcer {

    public List<Violation> enforce(Standard.FilePatternEnforcement enforcement,
                                   List<String> repoFilePaths) {
        List<Violation> out = new ArrayList<>();
        if (enforcement == null || repoFilePaths == null) return out;

        // Forbidden — emit one violation per matching file
        if (enforcement.forbiddenPatterns() != null) {
            for (String pattern : enforcement.forbiddenPatterns()) {
                if (pattern == null || pattern.isBlank()) continue;
                for (String file : repoFilePaths) {
                    if (DetectorUtils.matchesGlob(file, pattern)) {
                        out.add(new Violation(
                                Violation.Kind.FORBIDDEN_FILE_PRESENT,
                                pattern,
                                file,
                                "File '" + file + "' matches forbidden pattern '" + pattern + "'"));
                    }
                }
            }
        }
        // Required — emit one violation per pattern with no matching file
        if (enforcement.requiredPatterns() != null) {
            for (String pattern : enforcement.requiredPatterns()) {
                if (pattern == null || pattern.isBlank()) continue;
                boolean found = false;
                for (String file : repoFilePaths) {
                    if (DetectorUtils.matchesGlob(file, pattern)) { found = true; break; }
                }
                if (!found) {
                    out.add(new Violation(
                            Violation.Kind.REQUIRED_FILE_MISSING,
                            pattern,
                            null,
                            "No file matches required pattern '" + pattern + "'"));
                }
            }
        }
        return out;
    }

    /**
     * @param kind         FORBIDDEN_FILE_PRESENT or REQUIRED_FILE_MISSING
     * @param pattern      the glob that caused the violation
     * @param file         file that matched (FORBIDDEN_FILE_PRESENT) or null (REQUIRED_FILE_MISSING)
     * @param message      human-readable message
     */
    public record Violation(Kind kind, String pattern, String file, String message) {
        public enum Kind { FORBIDDEN_FILE_PRESENT, REQUIRED_FILE_MISSING }
    }
}
