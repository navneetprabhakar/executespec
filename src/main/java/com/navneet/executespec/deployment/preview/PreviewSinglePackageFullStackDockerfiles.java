package com.navneet.executespec.deployment.preview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

final class PreviewSinglePackageFullStackDockerfiles {

  private PreviewSinglePackageFullStackDockerfiles() {}

  /**
   * Single-package full-stack: both frontend (React/Vue/etc) and backend (Express/etc) in the same
   * package.json, typically run via "concurrently" or similar. Uses "npm run dev:full" if
   * available, falling back to "dev" or "start".
   */
  static String dockerfile(Path workspace) {
    JsonNode pkg = PreviewNodeSignals.parsePackageJson(workspace);
    boolean hasViteConfig =
        Files.exists(workspace.resolve("vite.config.js"))
            || Files.exists(workspace.resolve("vite.config.ts"));

    boolean usesVite = hasViteConfig;

    if (pkg != null && pkg.has("scripts")) {
      JsonNode scripts = pkg.get("scripts");
      // Prioritize scripts that likely run both FE and BE
      List<String> fullStackCandidates =
          List.of("dev:full", "start:full", "server:full", "all", "dev", "start");
      for (String candidate : fullStackCandidates) {
        if (scripts.has(candidate)) {
          String scriptVal = scripts.get(candidate).asText("");
          if (scriptVal.contains("vite")) usesVite = true;
          break;
        }
      }
    }

    // Always ensure concurrently is available; dev:all script uses it regardless of Vite.
    StringBuilder missing = new StringBuilder();
    if (!PreviewFileSignals.fileContainsPattern(
        workspace.resolve("package.json"), "\"concurrently\"")) missing.append(" concurrently");

    // Also install Vite-related deps if this is a Vite project
    if (usesVite) {
      if (!PreviewFileSignals.fileContainsPattern(workspace.resolve("package.json"), "\"vite\""))
        missing.append(" vite");
      if (!PreviewFileSignals.fileContainsPattern(
          workspace.resolve("package.json"), "\"@vitejs/plugin-react\""))
        missing.append(" @vitejs/plugin-react");
      if (!PreviewFileSignals.fileContainsPattern(workspace.resolve("package.json"), "\"react\""))
        missing.append(" react");
      if (!PreviewFileSignals.fileContainsPattern(
          workspace.resolve("package.json"), "\"react-dom\"")) missing.append(" react-dom");
      if (!PreviewFileSignals.fileContainsPattern(
          workspace.resolve("package.json"), "\"prop-types\"")) missing.append(" prop-types");
      if (!PreviewFileSignals.fileContainsPattern(
          workspace.resolve("package.json"), "\"lucide-react\"")) missing.append(" lucide-react");
      if (!PreviewFileSignals.fileContainsPattern(workspace.resolve("package.json"), "\"clsx\""))
        missing.append(" clsx");
      if (!PreviewFileSignals.fileContainsPattern(
          workspace.resolve("package.json"), "\"tailwind-merge\""))
        missing.append(" tailwind-merge");
    }

    String viteInstall =
        missing.length() > 0 ? " && npm install -D" + missing + " --legacy-peer-deps" : "";

    return """
           FROM node:20-alpine
           WORKDIR /app
           LABEL preview=true
           ENV HOST=0.0.0.0
           COPY package*.json ./
           RUN npm install --legacy-peer-deps"""
        + viteInstall
        + "\n"
        + "COPY . .\n"
        +
        // Patch package.json:
        // 1. Inject --host 0.0.0.0 into any vite script
        // 2. If it's a full-stack project but 'dev' only runs vite, create 'dev:all' that runs both
        "RUN node -e \"const fs=require('fs');const"
        + " p=JSON.parse(fs.readFileSync('package.json','utf8'));if(p.scripts){  for(const[k,v]of"
        + " Object.entries(p.scripts)){    if(typeof"
        + " v==='string'&&/\\\\bvite\\\\b/.test(v)&&!v.includes('--host')){     "
        + " p.scripts[k]=v.replace(/\\\\bvite\\\\b/g,'vite --host 0.0.0.0')    }  }  const"
        + " hasServer=p.scripts.server||p.scripts.backend||fs.existsSync('server/server.js')||fs.existsSync('server.js');"
        + "  const"
        + " runsBoth=Object.values(p.scripts).some(v=>v.includes('concurrently')||(v.includes('vite')&&v.includes('node')));"
        + "  if(hasServer && !runsBoth){    const serverCmd=p.scripts.server?'npm run"
        + " server':(p.scripts.backend?'npm run backend':(fs.existsSync('server/server.js')?'node"
        + " server/server.js':'node server.js'));    p.scripts['dev:all']='concurrently \\\"npm run"
        + " dev\\\" \\\"'+serverCmd+'\\\"'; "
        + " }}fs.writeFileSync('package.json',JSON.stringify(p,null,2));\"\n"
        + "EXPOSE 5173 3001\n"
        + "CMD [\"sh\", \"-c\", \"node -e \\\"var"
        + " s=JSON.parse(require('fs').readFileSync('package.json')).scripts;process.exit(s['dev:all']?0:1)\\\""
        + " && npm run dev:all || npm run dev\"]\n";
  }

  /**
   * Single-package full-stack: React + Express in same package.json for Cloud Run. Vite hardcoded to
   * 8080 (Cloud Run's exposed port). Backend gets PORT=3001 so Express reads 3001 and doesn't
   * compete with Vite for 8080.
   */
  static String cloudRun(Path workspace) {
    String viteInstall = crInstallMissingViteDeps(workspace);
    // Also ensure concurrently is available
    if (!PreviewFileSignals.fileContainsPattern(
        workspace.resolve("package.json"), "\"concurrently\"")) {
      viteInstall +=
          (viteInstall.isEmpty() ? " && npm install -D" : "") + " concurrently --legacy-peer-deps";
      if (viteInstall.startsWith(" concurrently")) {
        viteInstall = " && npm install -D concurrently --legacy-peer-deps";
      }
    }

    var sb = new StringBuilder();
    sb.append("FROM node:20-alpine\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV HOST=0.0.0.0\n");
    sb.append("ENV PORT=3001\n"); // Backend reads PORT=3001, Vite hardcoded to 8080
    sb.append("COPY package*.json ./\n");
    sb.append("RUN npm install --legacy-peer-deps").append(viteInstall).append("\n");
    sb.append("COPY . .\n");

    // Patch vite scripts to --host 0.0.0.0 --port 8080 and create dev:all
    sb.append(PreviewDockerfileSnippets.viteDevScriptPatch(8080));

    // Patch vite allowedHosts
    sb.append(PreviewDockerfileSnippets.viteAllowedHostsPatch());

    // Patch vite proxy to forward to backend on 3001
    sb.append(PreviewDockerfileSnippets.viteProxyPatch(3001));

    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(true));

    // CMD: try dev:all first (runs concurrently), fallback to dev
    sb.append(
        "CMD sh -c \"node -e \\\"var"
            + " s=JSON.parse(require('fs').readFileSync('package.json')).scripts;process.exit(s['dev:all']?0:1)\\\""
            + " && npm run dev:all || npm run dev\"\n");
    return sb.toString();
  }

  /**
   * Detect missing vite/react deps and return an `npm install -D ...` suffix string. Returns empty
   * string if all deps present.
   */
  private static String crInstallMissingViteDeps(Path workspace) {
    StringBuilder missing = new StringBuilder();
    Path pkgJson = workspace.resolve("package.json");
    if (!Files.exists(pkgJson)) return "";
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"vite\"")) missing.append(" vite");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"@vitejs/plugin-react\""))
      missing.append(" @vitejs/plugin-react");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"react\"")) missing.append(" react");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"react-dom\""))
      missing.append(" react-dom");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"prop-types\""))
      missing.append(" prop-types");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"lucide-react\""))
      missing.append(" lucide-react");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"clsx\"")) missing.append(" clsx");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"tailwind-merge\""))
      missing.append(" tailwind-merge");
    if (missing.isEmpty()) return "";
    return " && npm install -D" + missing + " --legacy-peer-deps";
  }
}
