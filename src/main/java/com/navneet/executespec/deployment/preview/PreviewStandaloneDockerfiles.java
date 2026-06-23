package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

final class PreviewStandaloneDockerfiles {

  private static final long MAX_FILE_SIZE = 50 * 1024;

  private PreviewStandaloneDockerfiles() {}

  static String cloudRunGo() {
    var sb = new StringBuilder();
    sb.append("FROM golang:1.22-alpine\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV HOST=0.0.0.0\n");
    sb.append("ENV PORT=8080\n");
    sb.append("COPY go.mod go.sum* ./\n");
    sb.append("RUN go mod download 2>/dev/null || true\n");
    sb.append("COPY . .\n");
    sb.append("RUN go build -o server . 2>/dev/null || true\n");
    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(true));
    sb.append("CMD sh -c \"if [ -f server ]; then ./server; else go run .; fi\"\n");
    return sb.toString();
  }

  static String cloudRunRust() {
    var sb = new StringBuilder();
    sb.append("FROM rust:1.77-slim\n");
    sb.append(
        "RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf"
            + " /var/lib/apt/lists/*\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");
    sb.append("COPY Cargo.toml Cargo.lock* ./\n");
    sb.append(
        "RUN mkdir src && echo \"fn main() {}\" > src/main.rs && cargo build --release 2>/dev/null"
            + " || true && rm -rf src\n");
    sb.append("COPY . .\n");
    sb.append("RUN cargo build --release 2>/dev/null || true\n");
    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(false));
    sb.append(
        "CMD sh -c \"bin=$(find target/release -maxdepth 1 -type f -executable | head -1) && if ["
            + " -n \\\"$bin\\\" ]; then exec \\\"$bin\\\"; else cargo run --release; fi\"\n");
    return sb.toString();
  }

  static String cloudRunStaticHtml() {
    return """
FROM nginx:alpine
WORKDIR /usr/share/nginx/html
LABEL preview=true
ENV PORT=8080
COPY . .
RUN rm -f /etc/nginx/conf.d/default.conf \
    && echo 'server { listen 8080; root /usr/share/nginx/html; index index.html index.htm; location / { try_files $uri $uri/ /index.html =404; } }' \
       > /etc/nginx/conf.d/default.conf \
    && chown -R nginx:nginx /var/cache/nginx /var/log/nginx /etc/nginx/conf.d \
    && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid
EXPOSE 8080
USER nginx
CMD sh -c "sed -i 's/listen 8080/listen '${PORT:-8080}'/' /etc/nginx/conf.d/default.conf && nginx -g 'daemon off;'"
""";
  }

  static String cloudRunJava(Path workspace) {
    var sb = new StringBuilder();
    sb.append("FROM eclipse-temurin:21\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");
    sb.append("COPY . .\n");
    sb.append("RUN javac *.java 2>/dev/null || true\n");
    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(false));
    sb.append("CMD java ").append(findJavaMainClass(workspace)).append("\n");
    return sb.toString();
  }

  static String go() {
    return """
           FROM golang:1.22-alpine
           WORKDIR /app
           LABEL preview=true
           ENV HOST=0.0.0.0
           ENV PORT=8080
           COPY go.mod go.sum* ./
           RUN go mod download 2>/dev/null || true
           COPY . .
           RUN go build -o server . 2>/dev/null || true
           EXPOSE 8080
           CMD ["sh", "-c", "if [ -f server ]; then ./server; else go run .; fi"]
           """;
  }

  static String rust() {
    return """
FROM rust:1.77-slim
RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*
WORKDIR /app
LABEL preview=true
COPY Cargo.toml Cargo.lock* ./
RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release 2>/dev/null || true && rm -rf src
COPY . .
RUN cargo build --release 2>/dev/null || true
EXPOSE 8080
CMD ["sh", "-c", "bin=$(find target/release -maxdepth 1 -type f -executable | head -1) && if [ -n \\"$bin\\" ]; then exec \\"$bin\\"; else cargo run --release; fi"]
""";
  }

  static String reactNativeExpo() {
    return """
FROM node:20-alpine AS builder
WORKDIR /app
LABEL preview=true
COPY package.json package-lock.json* ./
# Install deps. Falls back to npm install if package-lock.json wasn't
# committed (common when the worker writes the scaffold from scratch
# and never invoked `npm install` in its sandbox).
RUN (npm ci --legacy-peer-deps 2>/dev/null) || npm install --legacy-peer-deps
# hermes-compiler guard. Expo SDK 55 / RN 0.80 under-declares this
# transitive dep — `expo export` fails with
# "Cannot find module 'hermes-compiler/package.json'" if it's missing.
# If the worker's package.json didn't include it, add it now so the
# bundle succeeds. No-op when already present.
RUN node -e "const p=require('./package.json');const deps=(p.dependencies||{});process.exit(deps['hermes-compiler']?0:1)" \\
    || npm install --no-save --legacy-peer-deps hermes-compiler@latest
COPY . .
RUN npx expo export --platform android --output-dir /app/dist \\
    && echo '<!doctype html><html><head><meta charset="utf-8"><title>Mobile Preview</title>' \\
       '<style>body{font-family:system-ui;margin:40px;max-width:640px}h1{color:#1f2937}' \\
       '.card{background:#f3f4f6;padding:16px;border-radius:8px;margin:16px 0}' \\
       'code{background:#e5e7eb;padding:2px 6px;border-radius:4px}</style></head>' \\
       '<body><h1>React Native preview bundled</h1>' \\
       '<p>Your mobile app compiled cleanly (Metro bundle + Hermes bytecode).</p>' \\
       '<div class="card"><b>Next steps:</b><ol>' \\
       '<li>Install <a href="https://expo.dev/client" target="_blank">Expo Go</a> on your phone.</li>' \\
       '<li>Full Expo Go QR pairing is a v2 follow-up. For now, download the bundle below or run the project locally with <code>npx expo start</code>.</li>' \\
       '</ol></div>' \\
       '<p><a href="/_expo/static/js/android/">Download the Hermes bundle</a> | ' \\
       '<a href="/metadata.json">View manifest (metadata.json)</a></p>' \\
       '</body></html>' > /app/dist/index.html
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
RUN rm -f /etc/nginx/conf.d/default.conf \\
    && echo 'server { listen 8080; root /usr/share/nginx/html; index index.html; location / { try_files $uri $uri/ =404; } }' \\
       > /etc/nginx/conf.d/default.conf \\
    && chown -R nginx:nginx /var/cache/nginx /var/log/nginx \\
    && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
""";
  }

  static String staticHtml() {
    return """
FROM nginx:alpine
WORKDIR /usr/share/nginx/html
LABEL preview=true
COPY . .
RUN rm -f /etc/nginx/conf.d/default.conf \
    && echo 'server { listen 8080; root /usr/share/nginx/html; index index.html index.htm; location / { try_files $uri $uri/ /index.html =404; } }' \
       > /etc/nginx/conf.d/default.conf \
    && chown -R nginx:nginx /var/cache/nginx /var/log/nginx \
    && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
""";
  }

  static String cliGo() {
    return """
FROM golang:1.22-alpine
WORKDIR /app
LABEL preview=true
RUN apk add --no-cache ttyd
COPY go.mod go.sum* ./
RUN go mod download 2>/dev/null || true
COPY . .
RUN go build -o app . 2>/dev/null || true
EXPOSE 8080
CMD ["sh", "-c", "if [ -f app ]; then ttyd --port 8080 --writable ./app; else ttyd --port 8080 --writable go run .; fi"]
""";
  }

  static String cliRust() {
    return """
FROM rust:1.77-slim
RUN apt-get update && apt-get install -y pkg-config libssl-dev wget && rm -rf /var/lib/apt/lists/* \\
    && wget https://github.com/tsl0922/ttyd/releases/download/1.7.7/ttyd.x86_64 -O /usr/local/bin/ttyd \\
    && chmod +x /usr/local/bin/ttyd
WORKDIR /app
LABEL preview=true
COPY Cargo.toml Cargo.lock* ./
RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release 2>/dev/null || true && rm -rf src
COPY . .
RUN cargo build --release 2>/dev/null || true
EXPOSE 8080
CMD ["sh", "-c", "bin=$(find target/release -maxdepth 1 -type f -executable | head -1) && if [ -n \\"$bin\\" ]; then exec ttyd --port 8080 --writable \\"$bin\\"; else ttyd --port 8080 --writable cargo run --release; fi"]
""";
  }

  private static String findJavaMainClass(Path workspace) {
    try (Stream<Path> stream = Files.walk(workspace, 3)) {
      Path mainFile =
          stream
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> fileContainsPattern(p, "public static void main"))
              .findFirst()
              .orElse(null);
      if (mainFile != null) {
        String filename = mainFile.getFileName().toString();
        return filename.substring(0, filename.length() - 5);
      }
    } catch (IOException e) {
      return "Main";
    }
    return "Main";
  }

  private static boolean fileContainsPattern(Path file, String... patterns) {
    try {
      if (Files.size(file) > MAX_FILE_SIZE) return false;
      String content = Files.readString(file);
      for (String pattern : patterns) {
        if (content.contains(pattern)) return true;
      }
    } catch (IOException e) {
      return false;
    }
    return false;
  }
}
