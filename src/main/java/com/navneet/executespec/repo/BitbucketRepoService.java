package com.navneet.executespec.repo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages Bitbucket repositories for pipeline runs. Mirrors {@link GitHubRepoService} but targets
 * the Bitbucket REST API v2.
 *
 * <p>Auth uses HTTP Basic with a Bitbucket app password: {@code Authorization: Basic
 * Base64(username:appPassword)}.
 *
 * <p>All methods are non-blocking on failure: they return null/false and log warnings rather than
 * throwing, matching the GitHubRepoService contract.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BitbucketRepoService {

  private static final String BASE_URL = "https://api.bitbucket.org/2.0";

  private final ObjectMapper objectMapper;

  /**
   * Clones a Bitbucket repo into {@code workspacePath} using embedded credentials in the URL. Uses
   * ProcessBuilder for the shell {@code git clone} call.
   *
   * @param cloneUrl HTTPS clone URL (e.g. https://bitbucket.org/workspace/repo.git)
   * @param workspacePath local directory to clone into
   * @param username Bitbucket username
   * @param appPassword Bitbucket app password
   */
  public void cloneRepo(
      String cloneUrl, String workspacePath, String username, String appPassword) {
    if (username == null || username.isBlank() || appPassword == null || appPassword.isBlank()) {
      log.warn("[BitbucketRepoService] No Bitbucket credentials — cannot clone repo");
      return;
    }

    try {
      // Embed credentials: https://username:appPassword@bitbucket.org/workspace/repo.git
      String authUrl =
          cloneUrl.replace("https://", "https://" + username + ":" + appPassword + "@");

      ProcessBuilder pb = new ProcessBuilder("git", "clone", authUrl, workspacePath);
      pb.inheritIO();
      int exit = pb.start().waitFor();
      if (exit != 0) {
        log.error("[BitbucketRepoService] git clone failed with exit code {}", exit);
        return;
      }
      log.info("[BitbucketRepoService] Cloned repo to {}", workspacePath);
    } catch (Exception e) {
      log.error("[BitbucketRepoService] Error cloning repo: {}", e.getMessage());
    }
  }

  /**
   * Returns the default branch name for a Bitbucket repository.
   *
   * @param workspace Bitbucket workspace slug
   * @param repoSlug repository slug
   * @param username Bitbucket username
   * @param appPassword Bitbucket app password
   * @return default branch name (e.g. "main", "master"), or "main" on failure
   */
  public String getDefaultBranch(
      String workspace, String repoSlug, String username, String appPassword) {
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(BASE_URL + "/repositories/" + workspace + "/" + repoSlug))
              .header("Authorization", buildAuthHeader(username, appPassword))
              .header("Accept", "application/json")
              .GET()
              .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();

      if (status == 200) {
        JsonNode root = parseJson(response.body());
        if (root != null) {
          JsonNode mainBranch = root.path("mainbranch").path("name");
          if (!mainBranch.isMissingNode() && !mainBranch.asText().isBlank()) {
            return mainBranch.asText();
          }
        }
      }

      handleErrorStatus(status, workspace, repoSlug, "getDefaultBranch");
    } catch (Exception e) {
      log.warn(
          "[BitbucketRepoService] Error getting default branch for {}/{}: {}",
          workspace,
          repoSlug,
          e.getMessage());
    }
    return "main";
  }

  /**
   * Creates a pull request on a Bitbucket repository.
   *
   * @param workspace Bitbucket workspace slug
   * @param repoSlug repository slug
   * @param sourceBranch source branch name (e.g. "executespec/abc123")
   * @param title PR title
   * @param body PR description
   * @param username Bitbucket username
   * @param appPassword Bitbucket app password
   * @return PR HTML URL (e.g. "https://bitbucket.org/workspace/repo/pull-requests/1"), or null on
   *     failure
   */
  public String createPullRequest(
      String workspace,
      String repoSlug,
      String sourceBranch,
      String title,
      String body,
      String username,
      String appPassword) {
    if (username == null || username.isBlank() || appPassword == null || appPassword.isBlank()) {
      log.warn("[BitbucketRepoService] No Bitbucket credentials — cannot create PR");
      return null;
    }

    try {
      // Resolve destination branch
      String destinationBranch = getDefaultBranch(workspace, repoSlug, username, appPassword);

      // Build PR JSON payload
      String requestBody =
          objectMapper.writeValueAsString(
              objectMapper
                  .createObjectNode()
                  .put("title", title)
                  .put("description", body != null ? body : "")
                  .set(
                      "source",
                      objectMapper
                          .createObjectNode()
                          .set(
                              "branch",
                              objectMapper.createObjectNode().put("name", sourceBranch))));

      // Inject destination into the JSON (ObjectMapper node building)
      JsonNode tree = objectMapper.readTree(requestBody);
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
          .set(
              "destination",
              objectMapper
                  .createObjectNode()
                  .set("branch", objectMapper.createObjectNode().put("name", destinationBranch)));
      requestBody = objectMapper.writeValueAsString(tree);

      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      BASE_URL + "/repositories/" + workspace + "/" + repoSlug + "/pullrequests"))
              .header("Authorization", buildAuthHeader(username, appPassword))
              .header("Accept", "application/json")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();

      if (status == 201 || status == 200) {
        JsonNode root = parseJson(response.body());
        if (root != null) {
          JsonNode htmlHref = root.path("links").path("html").path("href");
          if (!htmlHref.isMissingNode()) {
            String prUrl = htmlHref.asText();
            log.info("[BitbucketRepoService] Created PR: {}", prUrl);
            return prUrl;
          }
        }
      }

      handleErrorStatus(status, workspace, repoSlug, "createPullRequest");
      log.warn(
          "[BitbucketRepoService] Failed to create PR for {}/{}: status {} — {}",
          workspace,
          repoSlug,
          status,
          response.body());
    } catch (Exception e) {
      log.warn(
          "[BitbucketRepoService] Error creating PR for {}/{}: {}",
          workspace,
          repoSlug,
          e.getMessage());
    }
    return null;
  }

  /**
   * Validates Bitbucket credentials by attempting to read the repository metadata.
   *
   * @param workspace Bitbucket workspace slug
   * @param repoSlug repository slug
   * @param username Bitbucket username
   * @param appPassword Bitbucket app password
   * @return true if credentials are valid and repo is accessible, false otherwise
   */
  public boolean validateCredentials(
      String workspace, String repoSlug, String username, String appPassword) {
    if (username == null || username.isBlank() || appPassword == null || appPassword.isBlank()) {
      log.warn("[BitbucketRepoService] Missing Bitbucket credentials for validation");
      return false;
    }

    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(BASE_URL + "/repositories/" + workspace + "/" + repoSlug))
              .header("Authorization", buildAuthHeader(username, appPassword))
              .header("Accept", "application/json")
              .GET()
              .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();

      if (status == 200) {
        log.info("[BitbucketRepoService] Credentials valid for {}/{}", workspace, repoSlug);
        return true;
      }

      handleErrorStatus(status, workspace, repoSlug, "validateCredentials");
    } catch (Exception e) {
      log.warn(
          "[BitbucketRepoService] Error validating credentials for {}/{}: {}",
          workspace,
          repoSlug,
          e.getMessage());
    }
    return false;
  }

  /**
   * Builds the HTTP Basic auth header value.
   *
   * @return "Basic " + Base64(username:appPassword)
   */
  private String buildAuthHeader(String username, String appPassword) {
    String credentials = username + ":" + appPassword;
    return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
  }

  /**
   * Parses a JSON string into a Jackson JsonNode.
   *
   * @return parsed JsonNode, or null on parse failure
   */
  private JsonNode parseJson(String body) {
    try {
      return objectMapper.readTree(body);
    } catch (Exception e) {
      log.warn("[BitbucketRepoService] Failed to parse JSON response: {}", e.getMessage());
      return null;
    }
  }

  /** Logs appropriate error messages based on HTTP status codes from the Bitbucket API. */
  private void handleErrorStatus(int status, String workspace, String repoSlug, String operation) {
    switch (status) {
      case 401 ->
          log.error(
              "[BitbucketRepoService] Invalid Bitbucket credentials for {}/{} ({})",
              workspace,
              repoSlug,
              operation);
      case 404 ->
          log.error(
              "[BitbucketRepoService] Repository not found: {}/{} ({})",
              workspace,
              repoSlug,
              operation);
      case 429 ->
          log.warn(
              "[BitbucketRepoService] Rate limited by Bitbucket API for {}/{} ({})",
              workspace,
              repoSlug,
              operation);
      default -> {
        /* handled by caller */
      }
    }
  }
}
