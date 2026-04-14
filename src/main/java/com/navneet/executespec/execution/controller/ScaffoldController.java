package com.navneet.executespec.execution.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the scaffold taxonomy so the Launch UI can populate the "tech hint"
 * dropdown when a user adds a new-repo spec. The keys must match scaffold
 * template filenames under {@code resources/prompts/scaffolds/}.
 */
@RestController
public class ScaffoldController {

    public record Scaffold(String key, String label, String language) {}

    private static final List<Scaffold> SCAFFOLDS = List.of(
            new Scaffold("java-spring-boot",  "Java · Spring Boot",   "java"),
            new Scaffold("node-backend",      "Node · Express",       "node"),
            new Scaffold("node-frontend",     "Node · React + Vite",  "node"),
            new Scaffold("python",            "Python · FastAPI",     "python"),
            new Scaffold("go",                "Go · Echo",            "go"),
            new Scaffold("rust",              "Rust · Actix",         "rust"),
            new Scaffold("fullstack",         "Full-stack · Node+React", "node"),
            new Scaffold("static-html",       "Static HTML site",     "html"));

    @GetMapping("/api/scaffolds")
    public ResponseEntity<List<Scaffold>> list() {
        return ResponseEntity.ok(SCAFFOLDS);
    }
}
