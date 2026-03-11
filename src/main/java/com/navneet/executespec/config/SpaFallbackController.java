package com.navneet.executespec.config;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * Forwards unmatched non-API, non-asset routes to index.html so React Router
 * can handle deep links (e.g. navigating directly to /dashboard).
 *
 * <p>API paths that don't match any handler get a proper 404 instead of being
 * forwarded to the SPA (which would cause Thymeleaf template resolution errors).
 */
@Controller
public class SpaFallbackController {

    @GetMapping("{*path}")
    public String spa(@PathVariable String path) {
        if (path.startsWith("/api") || path.contains(".")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "forward:/index.html";
    }
}
