package com.navneet.executespec.deployment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.state.entity.RunRepo;
import com.navneet.executespec.state.repository.RunRepoRepository;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DeploymentGitHubService {

  private final AgentProperties agentProps;
  private final ObjectMapper objectMapper;
  private final RunRepoRepository runRepoRepo;
  private final ArtifactPublicationGuard publicationGuard;
  // Bounded client: a hung GitHub API connect/response must not pin the deploy virtual thread
  // indefinitely, matching the timeout discipline every subprocess in this package already has
  // (RR-1637).
  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();

  public DeploymentGitHubService(
      AgentProperties agentProps,
      ObjectMapper objectMapper,
      RunRepoRepository runRepoRepo,
      ArtifactPublicationGuard publicationGuard) {
    this.agentProps = agentProps;
    this.objectMapper = objectMapper;
    this.runRepoRepo = runRepoRepo;
    this.publicationGuard = publicationGuard;
  }

  /** Mode NEW: Create new GitHub repo + push workspace. */
  public RunRepo createAndPush(String runId, String repoName, Path workspacePath, String pat)
      throws IOException {
    // Gate before creating the remote repository: a rejected artifact must have no external side
    // effect, even when shell/local-hands created the file.
    publicationGuard.assertGitWorkspaceSafe(runId);
    String repoUrl = createRepo(repoName, pat);
    commitAndPush(workspacePath, repoUrl, pat);

    RunRepo entity =
        RunRepo.create(
            runId,
            repoUrl,
            agentProps.getGithub().getDefaultOwner(),
            repoName,
            RunRepo.RepoMode.NEW);
    entity.markPushed();
    return runRepoRepo.save(entity);
  }

  /** Mode EXISTING: Push to existing GitHub repo. */
  public RunRepo pushToExisting(String runId, String repoUrl, Path workspacePath, String pat)
      throws IOException {
    publicationGuard.assertGitWorkspaceSafe(runId);
    commitAndPush(workspacePath, repoUrl, pat);

    String[] parts = parseOwnerAndName(repoUrl);
    RunRepo entity = RunRepo.create(runId, repoUrl, parts[0], parts[1], RunRepo.RepoMode.EXISTING);
    entity.markPushed();
    return runRepoRepo.save(entity);
  }

  /** Mode NONE: No GitHub — record a placeholder. */
  public RunRepo skipGitHub(String runId) {
    RunRepo entity = RunRepo.create(runId, "none", "none", "none", RunRepo.RepoMode.NONE);
    return runRepoRepo.save(entity);
  }

  private String createRepo(String name, String pat) throws IOException {
    if (pat == null || pat.isBlank()) {
      throw new IOException(
          "GitHub PAT required to create a repo — connect your PAT in Integrations.");
    }
    // Private by default: pushing a customer's generated/brownfield source to a world-readable repo
    // with no opt-in is a data-exposure defect (RR-1628). Visibility should become an explicit
    // caller parameter surfaced in the UI/API; until then the safe default is private.
    String body =
        objectMapper.writeValueAsString(
            java.util.Map.of("name", name, "private", true, "auto_init", false));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/user/repos"))
            .timeout(java.time.Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github+json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 422) {
        throw new IOException(
            "GitHub repo '"
                + name
                + "' already exists. Use EXISTING mode or choose a different name.");
      }
      if (response.statusCode() != 201) {
        String sanitized =
            response
                .body()
                .replaceAll(
                    "(?i)(authorization|bearer|token|ghp_)[:\\s]*[A-Za-z0-9_\\-]+",
                    "$1=***REDACTED***");
        throw new IOException("GitHub API error " + response.statusCode() + ": " + sanitized);
      }
      JsonNode json = objectMapper.readTree(response.body());
      String repoUrl = json.get("html_url").asText();
      log.info("Created GitHub repo: {}", repoUrl);
      return repoUrl;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("GitHub API interrupted", e);
    }
  }

  private void commitAndPush(Path workspacePath, String repoUrl, String pat) throws IOException {
    if (pat == null || pat.isBlank()) {
      throw new IOException("GitHub PAT required to push — connect your PAT in Integrations.");
    }
    try {
      Git git;
      if (workspacePath.resolve(".git").toFile().exists()) {
        git = Git.open(workspacePath.toFile());
        try {
          git.remoteRemove().setRemoteName("origin").call();
        } catch (Exception ignored) {
        }
      } else {
        git = Git.init().setDirectory(workspacePath.toFile()).call();
      }

      git.remoteAdd().setName("origin").setUri(new URIish(repoUrl + ".git")).call();

      git.add().addFilepattern(".").call();
      git.commit().setMessage("[Deploy] Push workspace for deployment").call();
      git.push()
          .setRemote("origin")
          .setCredentialsProvider(new UsernamePasswordCredentialsProvider(pat, ""))
          .call();
      git.close();

      log.info("Pushed workspace to {}", repoUrl);
    } catch (Exception e) {
      throw new IOException("Git push failed: " + e.getMessage(), e);
    }
  }

  private String[] parseOwnerAndName(String url) {
    String clean = url.replace(".git", "");
    String[] parts = clean.split("/");
    return new String[] {parts[parts.length - 2], parts[parts.length - 1]};
  }
}
