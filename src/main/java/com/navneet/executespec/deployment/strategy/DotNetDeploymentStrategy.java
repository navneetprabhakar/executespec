package com.navneet.executespec.deployment.strategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.navneet.executespec.deployment.DeploymentProperties;
import com.navneet.executespec.state.entity.Deployment;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Deployment strategy for .NET projects (ASP.NET Core, Minimal API, gRPC, etc.).
 *
 * <p>Detection signals: any {@code *.csproj} or {@code *.sln} file present at the workspace root.
 *
 * <p>Two-stage Dockerfile:
 *
 * <ul>
 *   <li>Stage 1 ({@code mcr.microsoft.com/dotnet/sdk:8.0}): {@code dotnet publish -c Release -o
 *       out}
 *   <li>Stage 2 ({@code mcr.microsoft.com/dotnet/aspnet:8.0}): run the published DLL
 * </ul>
 *
 * <p>The assembly name is read from the first {@code <AssemblyName>} or {@code <RootNamespace>} in
 * the {@code .csproj} file; falls back to the csproj file stem if not found.
 */
@Component
@Order(9)
@Slf4j
public class DotNetDeploymentStrategy implements DeploymentStrategy {

  @Override
  public Deployment.DeployStrategy type() {
    return Deployment.DeployStrategy.DOTNET;
  }

  @Override
  public boolean applies(Path workspacePath) {
    try (Stream<Path> files = Files.list(workspacePath)) {
      return files.anyMatch(
          p -> {
            String name = p.getFileName().toString();
            return name.endsWith(".csproj") || name.endsWith(".sln");
          });
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
    String assemblyName = detectAssemblyName(workspacePath);
    log.info("[DotNetStrategy] Detected assembly: {}", assemblyName);

    return """
           # Stage 1: Build & publish
           FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
           WORKDIR /app
           COPY . .
           RUN dotnet publish -c Release -o out

           # Stage 2: Runtime
           FROM mcr.microsoft.com/dotnet/aspnet:8.0
           WORKDIR /app
           COPY --from=build /app/out .
           EXPOSE 8080
           ENV PORT=8080
           ENV ASPNETCORE_URLS=http://+:8080
           ENTRYPOINT ["dotnet", "%s.dll"]
           """
        .formatted(assemblyName);
  }

  @Override
  public String detectionSummary(Path workspacePath) {
    return ".NET project detected (assembly: %s)".formatted(detectAssemblyName(workspacePath));
  }

  private String detectAssemblyName(Path workspacePath) {
    // Try to find a .csproj file
    try (Stream<Path> files = Files.list(workspacePath)) {
      Path csproj =
          files
              .filter(p -> p.getFileName().toString().endsWith(".csproj"))
              .findFirst()
              .orElse(null);

      if (csproj != null) {
        String content = Files.readString(csproj);
        // Look for explicit <AssemblyName> tag
        Matcher m = Pattern.compile("<AssemblyName>([^<]+)</AssemblyName>").matcher(content);
        if (m.find()) return m.group(1).strip();
        // Fall back to csproj file stem (e.g. MyApp.csproj → MyApp)
        String stem = csproj.getFileName().toString();
        return stem.substring(0, stem.lastIndexOf('.'));
      }
    } catch (IOException ignored) {
    }
    return "app";
  }
}
