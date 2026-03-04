package com.navneet.agentteam.deployment.strategy;

import com.navneet.agentteam.deployment.DeploymentProperties;
import com.navneet.agentteam.state.entity.Deployment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deployment strategy for Node.js projects.
 *
 * <p>Detection signal: {@code package.json} present, no {@code pom.xml} or {@code build.gradle}.
 *
 * <p>Two build modes determined at Dockerfile-generation time by inspecting {@code package.json}:
 * <ol>
 *   <li><b>Frontend SPA</b> (Vite, CRA, Angular, Vue CLI, Svelte) — multi-stage build → nginx:alpine,
 *       exposing port 8080. The build output directory is detected (dist / build / .output).</li>
 *   <li><b>Server app</b> (Express, Fastify, NestJS, Koa, Hapi, Next.js, Nuxt, SvelteKit) —
 *       node:20-slim, {@code npm run build --if-present} then {@code npm start},
 *       PORT env var set to 8080.</li>
 * </ol>
 */
@Component
@Order(3)
@Slf4j
public class NodeDeploymentStrategy implements DeploymentStrategy {

    @Override
    public Deployment.DeployStrategy type() {
        return Deployment.DeployStrategy.NODE;
    }

    @Override
    public boolean applies(Path workspacePath) {
        if (Files.exists(workspacePath.resolve("pom.xml")))           return false;
        if (Files.exists(workspacePath.resolve("build.gradle")))       return false;
        if (Files.exists(workspacePath.resolve("build.gradle.kts")))   return false;
        return Files.exists(workspacePath.resolve("package.json"));
    }

    @Override
    public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
        String pkgJson = readPackageJson(workspacePath);
        String framework = detectFramework(pkgJson, workspacePath);
        log.info("[NodeStrategy] Detected framework: {}", framework);

        if (isFrontendOnly(pkgJson, workspacePath)) {
            String outDir = detectBuildOutput(pkgJson, workspacePath);
            log.info("[NodeStrategy] Frontend SPA mode, build output dir: {}", outDir);
            return """
                    # Stage 1: Build
                    FROM node:20-slim AS build
                    WORKDIR /app
                    COPY package*.json ./
                    RUN npm ci
                    COPY . .
                    RUN npm run build

                    # Stage 2: Serve with nginx
                    FROM nginx:alpine
                    COPY --from=build /app/%s /usr/share/nginx/html
                    RUN printf 'server {\\n    listen 8080;\\n    server_name _;\\n    root /usr/share/nginx/html;\\n    index index.html;\\n    location / { try_files $uri $uri/ /index.html; }\\n}\\n' \\
                        > /etc/nginx/conf.d/default.conf
                    EXPOSE 8080
                    CMD ["nginx", "-g", "daemon off;"]
                    """.formatted(outDir);
        }

        // Server / SSR app (Express, Fastify, NestJS, Next.js, Nuxt, SvelteKit, etc.)
        return """
                FROM node:20-slim
                WORKDIR /app
                COPY package*.json ./
                RUN npm ci
                COPY . .
                RUN npm run build --if-present
                EXPOSE 8080
                ENV PORT=8080
                ENV NODE_ENV=production
                CMD ["npm", "start"]
                """;
    }

    @Override
    public String detectionSummary(Path workspacePath) {
        String pkgJson = readPackageJson(workspacePath);
        String framework = detectFramework(pkgJson, workspacePath);
        boolean frontend = isFrontendOnly(pkgJson, workspacePath);
        return "Node.js project detected (%s, %s)".formatted(
            framework,
            frontend ? "SPA/static build → nginx" : "server app → npm start"
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isFrontendOnly(String pkgJson, Path workspacePath) {
        // Explicit server-side frameworks → always server mode
        if (pkgJson.contains("\"express\"")        ||
            pkgJson.contains("\"fastify\"")         ||
            pkgJson.contains("\"@nestjs/core\"")    ||
            pkgJson.contains("\"koa\"")             ||
            pkgJson.contains("\"@hapi/hapi\"")      ||
            pkgJson.contains("\"restify\"")         ||
            pkgJson.contains("\"polka\"")           ||
            pkgJson.contains("\"micro\"")) {
            return false;
        }
        // SSR frameworks that need npm start → server mode
        if (pkgJson.contains("\"next\"")    ||
            pkgJson.contains("\"nuxt\"")    ||
            pkgJson.contains("\"@sveltejs/kit\"")) {
            return false;
        }
        // Pure frontend tooling → SPA build
        if (pkgJson.contains("\"vite\"")            ||
            pkgJson.contains("\"react-scripts\"")   ||
            pkgJson.contains("\"@angular/core\"")   ||
            pkgJson.contains("\"@vue/cli-service\"") ||
            pkgJson.contains("\"svelte\"")          ||
            pkgJson.contains("\"parcel\"")          ||
            pkgJson.contains("\"webpack\"")) {
            return true;
        }
        // Has start script that looks like a real server → server mode
        if (pkgJson.contains("\"start\"") &&
            (pkgJson.contains("node ") || pkgJson.contains("ts-node") || pkgJson.contains("nodemon"))) {
            return false;
        }
        // Has a build script but no obvious server → assume frontend SPA
        if (pkgJson.contains("\"build\"") && !pkgJson.contains("\"start\"")) {
            return true;
        }
        return false;
    }

    /** Best-effort detection of the build output directory. */
    private String detectBuildOutput(String pkgJson, Path workspacePath) {
        if (pkgJson.contains("react-scripts"))          return "build";   // CRA
        if (pkgJson.contains("\"@angular/core\""))      return "dist";    // Angular
        if (pkgJson.contains("\"@vue/cli-service\""))   return "dist";    // Vue CLI
        if (Files.exists(workspacePath.resolve("dist"))) return "dist";   // already built
        if (Files.exists(workspacePath.resolve("build"))) return "build";
        return "dist"; // vite default
    }

    private String detectFramework(String pkgJson, Path workspacePath) {
        if (pkgJson.contains("\"next\""))               return "Next.js";
        if (pkgJson.contains("\"nuxt\""))               return "Nuxt";
        if (pkgJson.contains("\"@nestjs/core\""))       return "NestJS";
        if (pkgJson.contains("\"@sveltejs/kit\""))      return "SvelteKit";
        if (pkgJson.contains("\"svelte\""))             return "Svelte";
        if (pkgJson.contains("\"express\""))            return "Express";
        if (pkgJson.contains("\"fastify\""))            return "Fastify";
        if (pkgJson.contains("\"koa\""))                return "Koa";
        if (pkgJson.contains("\"react-scripts\""))      return "Create React App";
        if (pkgJson.contains("\"vite\""))               return "Vite";
        if (pkgJson.contains("\"@angular/core\""))      return "Angular";
        if (pkgJson.contains("\"vue\""))                return "Vue";
        return "Node.js";
    }

    private String readPackageJson(Path workspacePath) {
        try {
            return Files.readString(workspacePath.resolve("package.json"));
        } catch (IOException e) {
            return "";
        }
    }
}
