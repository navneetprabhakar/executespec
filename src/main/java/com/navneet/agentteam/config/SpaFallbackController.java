package com.navneet.agentteam.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards unmatched non-API, non-asset routes to index.html so React Router
 * can handle deep links (e.g. navigating directly to /dashboard).
 */
@Controller
public class SpaFallbackController {

    @GetMapping("{*path}")
    public String spa(@org.springframework.web.bind.annotation.PathVariable String path) {
        if (path.startsWith("/api") || path.contains(".")) {
            return null;
        }
        return "forward:/index.html";
    }
}
