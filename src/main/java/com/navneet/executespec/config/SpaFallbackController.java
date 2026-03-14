package com.navneet.executespec.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves index.html for all non-API, non-asset routes so React Router can
 * handle deep links (e.g. navigating directly to /dashboard, /runs/123).
 *
 * <p>Uses [^\\.]*  regex to only match paths WITHOUT a file extension —
 * this lets requests for static assets (.js, .css, .ico, etc.) fall through
 * to Spring Boot's default static resource handler.
 *
 * <p>Directly returns the ClassPathResource instead of forwarding to avoid
 * re-dispatch issues with catch-all controller mappings.
 */
@Controller
public class SpaFallbackController {

    private static final Resource INDEX_HTML = new ClassPathResource("static/index.html");

    /** Root path / */
    @GetMapping("/")
    public ResponseEntity<Resource> root() {
        return serveIndex();
    }

    /** Single-level paths: /login, /dashboard, /settings, etc. */
    @GetMapping("/{path:[^\\.]*}")
    public ResponseEntity<Resource> spaLevel1() {
        return serveIndex();
    }

    /** Multi-level paths: /runs/123, /org/settings, /projects/abc/runs, etc. */
    @GetMapping("/{a:[^\\.]*}/{path:[^\\.]*}")
    public ResponseEntity<Resource> spaLevel2() {
        return serveIndex();
    }

    /** Three-level paths: /org/projects/123, etc. */
    @GetMapping("/{a:[^\\.]*}/{b:[^\\.]*}/{path:[^\\.]*}")
    public ResponseEntity<Resource> spaLevel3() {
        return serveIndex();
    }

    private ResponseEntity<Resource> serveIndex() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(INDEX_HTML);
    }
}
