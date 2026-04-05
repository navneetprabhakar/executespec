package com.navneet.executespec.harness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Validates that a workspace contains files from a supported language.
 *
 * <p>Supported languages: Java, TypeScript, React (TSX/JSX), Python, Go, Rust, HTML, CSS.
 * If the workspace only contains files from unsupported languages (Swift, Kotlin, etc.),
 * the validation fails with a descriptive error message.
 *
 * <p>Only applies to EXISTING repo mode — new (empty) workspaces skip validation.
 */
@Component
@Slf4j
public class LanguageValidator {

    /** Marker files that indicate a supported language is present in the workspace. */
    private static final List<String> SUPPORTED_MARKERS = List.of(
        // Java
        "pom.xml", "build.gradle", "build.gradle.kts",
        // TypeScript / React
        "tsconfig.json", "package.json",
        // Python
        "requirements.txt", "pyproject.toml", "setup.py", "setup.cfg", "Pipfile"
    );

    /** File extensions belonging to supported languages. */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        ".java", ".ts", ".tsx", ".js", ".jsx",
        ".py",
        ".html", ".htm", ".css", ".scss", ".sass", ".less"
    );

    /** File extensions that indicate an unsupported language. */
    private static final Set<String> UNSUPPORTED_EXTENSIONS = Set.of(
        ".swift", ".kt", ".kts",
        ".scala", ".clj", ".ex", ".exs",
        ".dart", ".lua", ".pl", ".pm",
        ".r", ".R", ".jl", ".zig",
        ".cs", ".fs", ".vb",
        ".m", ".mm",       // Objective-C
        ".cpp", ".cc", ".cxx", ".c", ".h", ".hpp", // C/C++
        ".go", ".rs"       // Go, Rust
    );

    public static final String SUPPORTED_LANGUAGES_LIST =
        "Java, TypeScript, React (TSX/JSX), Python, HTML/CSS";

    /**
     * Validates that the workspace contains at least one supported language.
     *
     * @param workspace the root path of the workspace
     * @return null if valid, or an error message string if unsupported
     */
    public String detectAndValidateLanguage(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return null; // nothing to validate
        }

        // Check for marker files first (fast path)
        for (String marker : SUPPORTED_MARKERS) {
            if (Files.exists(workspace.resolve(marker))) {
                log.debug("Found supported language marker: {}", marker);
                return null; // supported
            }
        }

        // Scan for file extensions (limit depth to avoid deeply nested node_modules etc.)
        boolean hasSupportedFile = false;
        boolean hasUnsupportedFile = false;
        String firstUnsupportedExt = null;

        try (Stream<Path> walk = Files.walk(workspace, 6)) {
            List<Path> files = walk
                .filter(Files::isRegularFile)
                .filter(p -> !p.toString().contains("/.git/"))
                .filter(p -> !p.toString().contains("/node_modules/"))
                .filter(p -> !p.toString().contains("/target/"))
                .filter(p -> !p.toString().contains("/build/"))
                .filter(p -> !p.toString().contains("/__pycache__/"))
                .toList();

            for (Path file : files) {
                String name = file.getFileName().toString();
                String ext = getExtension(name);
                if (ext == null) continue;

                if (SUPPORTED_EXTENSIONS.contains(ext)) {
                    hasSupportedFile = true;
                    break; // one supported file is enough
                }
                if (UNSUPPORTED_EXTENSIONS.contains(ext)) {
                    hasUnsupportedFile = true;
                    if (firstUnsupportedExt == null) {
                        firstUnsupportedExt = ext;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan workspace for language detection: {}", e.getMessage());
            return null; // don't block on scan failure
        }

        // Also check for .html files which may not have a marker file
        if (!hasSupportedFile) {
            try (Stream<Path> walk = Files.walk(workspace, 3)) {
                hasSupportedFile = walk
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".html") || name.endsWith(".htm");
                    });
            } catch (IOException ignored) {
                // already handled above
            }
        }

        if (hasSupportedFile) {
            return null; // valid
        }

        if (hasUnsupportedFile) {
            String langName = extensionToLanguageName(firstUnsupportedExt);
            return "Unsupported language detected: " + langName
                + " (" + firstUnsupportedExt + " files). "
                + "ExecuteSpec currently supports: " + SUPPORTED_LANGUAGES_LIST + ". "
                + "Please use a repository with one of these languages.";
        }

        // No recognised source files at all — let it through (could be docs-only, config, etc.)
        return null;
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot).toLowerCase();
    }

    private static String extensionToLanguageName(String ext) {
        return switch (ext) {
            case ".swift" -> "Swift";
            case ".kt", ".kts" -> "Kotlin";
            case ".scala" -> "Scala";
            case ".clj" -> "Clojure";
            case ".ex", ".exs" -> "Elixir";
            case ".dart" -> "Dart";
            case ".lua" -> "Lua";
            case ".pl", ".pm" -> "Perl";
            case ".r", ".R" -> "R";
            case ".jl" -> "Julia";
            case ".zig" -> "Zig";
            case ".cs" -> "C#";
            case ".fs" -> "F#";
            case ".vb" -> "Visual Basic";
            case ".m", ".mm" -> "Objective-C";
            case ".cpp", ".cc", ".cxx" -> "C++";
            case ".c", ".h" -> "C";
            case ".hpp" -> "C++";
            case ".go" -> "Go";
            case ".rs" -> "Rust";
            default -> ext;
        };
    }
}
