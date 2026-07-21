package com.navneet.executespec.folder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Git object-writing mechanics for {@link ExecutespecFolderWriter}.
 *
 * <p>Owns exactly one responsibility: turning a batch of path→content pairs into a single
 * content-addressed Git commit through GitHub's Git Data API, and moving refs at it. It knows
 * nothing about {@code .executespec/}, PR mode, project namespacing or drift — the folder writer
 * owns that policy and calls this for the plumbing.
 *
 * <p>The invariant this class exists to protect (RR-536): blobs, trees and commits are invisible to
 * a repository until a ref points at them, so every step before {@link #createBranchAt} or {@link
 * #fastForwardRef} is free of visible side effects. Abandoning a batch part-way leaves the
 * repository byte-identical and the dangling objects are garbage collected. The ref update is the
 * single atomic act.
 */
@Slf4j
class GitObjectWriter {

  private final HttpClient http;
  private final ObjectMapper json;

  GitObjectWriter(HttpClient http, ObjectMapper json) {
    this.http = http;
    this.json = json;
  }

  /**
   * Build one commit containing every file in {@code files}, parented on {@code baseSha}.
   *
   * <p>No ref moves here, so a failure at any point publishes nothing at all.
   *
   * @return the new commit SHA, or empty when the batch could not be built
   */
  Optional<String> buildCommit(
      String repoFullName, String baseSha, Map<String, String> files, String message, String pat) {
    Optional<String> baseTree = getCommitTreeSha(repoFullName, baseSha, pat);
    if (baseTree.isEmpty()) {
      log.warn("[GitObjectWriter] could not read base commit {} on {}", baseSha, repoFullName);
      return Optional.empty();
    }
    Map<String, String> blobsByPath = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : files.entrySet()) {
      Optional<String> blob = createBlob(repoFullName, e.getValue(), pat);
      if (blob.isEmpty()) {
        // No ref has moved, so abandoning here leaves the repository untouched.
        log.warn(
            "[GitObjectWriter] blob creation failed for {} on {}; nothing published",
            e.getKey(),
            repoFullName);
        return Optional.empty();
      }
      blobsByPath.put(e.getKey(), blob.get());
    }
    Optional<String> tree = createTree(repoFullName, baseTree.get(), blobsByPath, pat);
    if (tree.isEmpty()) {
      log.warn("[GitObjectWriter] tree creation failed on {}; nothing published", repoFullName);
      return Optional.empty();
    }
    return createCommit(repoFullName, "ExecuteSpec: " + message, tree.get(), baseSha, pat);
  }

  Optional<String> getRefSha(String repoFullName, String branch, String pat) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(refUrl(repoFullName, branch)))
              .header("Authorization", "Bearer " + pat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) return Optional.empty();
      JsonNode tree = json.readTree(res.body());
      JsonNode obj = tree.path("object").path("sha");
      return obj.isMissingNode() ? Optional.empty() : Optional.of(obj.asText());
    } catch (Exception ex) {
      log.debug("getRefSha failed for {}@{}: {}", repoFullName, branch, ex.getMessage());
      return Optional.empty();
    }
  }

  /** Tree SHA of an existing commit; the base every new tree is layered on. */
  Optional<String> getCommitTreeSha(String repoFullName, String commitSha, String pat) {
    JsonNode body =
        sendJson(
            "GET",
            "https://api.github.com/repos/" + repoFullName + "/git/commits/" + commitSha,
            null,
            pat,
            200);
    if (body == null) return Optional.empty();
    JsonNode sha = body.path("tree").path("sha");
    return sha.isMissingNode() ? Optional.empty() : Optional.of(sha.asText());
  }

  /**
   * Create a dangling blob. Invisible to the repository until a tree and commit reference it, so a
   * batch that aborts after some blobs exist has published nothing.
   */
  Optional<String> createBlob(String repoFullName, String content, String pat) {
    ObjectNode req = json.createObjectNode();
    req.put(
        "content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
    req.put("encoding", "base64");
    JsonNode body =
        sendJson(
            "POST", "https://api.github.com/repos/" + repoFullName + "/git/blobs", req, pat, 201);
    return sha(body);
  }

  /** One tree carrying every path in the batch, layered over the base commit's tree. */
  Optional<String> createTree(
      String repoFullName, String baseTreeSha, Map<String, String> blobsByPath, String pat) {
    ObjectNode req = json.createObjectNode();
    req.put("base_tree", baseTreeSha);
    var entries = req.putArray("tree");
    for (Map.Entry<String, String> e : blobsByPath.entrySet()) {
      ObjectNode entry = entries.addObject();
      entry.put("path", e.getKey());
      entry.put("mode", "100644");
      entry.put("type", "blob");
      entry.put("sha", e.getValue());
    }
    JsonNode body =
        sendJson(
            "POST", "https://api.github.com/repos/" + repoFullName + "/git/trees", req, pat, 201);
    return sha(body);
  }

  /** One commit for the whole batch, parented on the SHA the batch was planned against. */
  Optional<String> createCommit(
      String repoFullName, String message, String treeSha, String parentSha, String pat) {
    ObjectNode req = json.createObjectNode();
    req.put("message", message);
    req.put("tree", treeSha);
    req.putArray("parents").add(parentSha);
    JsonNode body =
        sendJson(
            "POST", "https://api.github.com/repos/" + repoFullName + "/git/commits", req, pat, 201);
    return sha(body);
  }

  /**
   * Create a new branch ref pointing straight at the batch commit.
   *
   * <p>Only 201 counts. The former implementation also treated a 422 "already exists" as success,
   * which would have opened a PR from a ref this call did not place — the branch could point
   * anywhere.
   */
  boolean createBranchAt(String repoFullName, String newBranch, String commitSha, String pat) {
    ObjectNode req = json.createObjectNode();
    req.put("ref", "refs/heads/" + newBranch);
    req.put("sha", commitSha);
    return sendJson(
            "POST", "https://api.github.com/repos/" + repoFullName + "/git/refs", req, pat, 201)
        != null;
  }

  /**
   * Move an existing branch to {@code commitSha} without forcing. GitHub rejects the update when
   * the commit is not a descendant of the current head, which is exactly the compare-and-swap this
   * needs: a concurrent writer wins and we publish nothing rather than overwriting them.
   */
  boolean fastForwardRef(String repoFullName, String branch, String commitSha, String pat) {
    ObjectNode req = json.createObjectNode();
    req.put("sha", commitSha);
    req.put("force", false);
    return sendJson("PATCH", refUrl(repoFullName, branch), req, pat, 200) != null;
  }

  /** Compensation for an orphaned branch whose PR never opened. */
  boolean deleteRef(String repoFullName, String branch, String pat) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(refUrl(repoFullName, branch)))
              .header("Authorization", "Bearer " + pat)
              .header("Accept", "application/vnd.github+json")
              .DELETE()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      return res.statusCode() == 204 || res.statusCode() == 404;
    } catch (Exception ex) {
      log.debug("deleteRef failed for {}@{}: {}", repoFullName, branch, ex.getMessage());
      return false;
    }
  }

  private static String refUrl(String repoFullName, String branch) {
    return "https://api.github.com/repos/"
        + repoFullName
        + "/git/refs/heads/"
        + URLEncoder.encode(branch, StandardCharsets.UTF_8);
  }

  private static Optional<String> sha(JsonNode body) {
    if (body == null) return Optional.empty();
    JsonNode sha = body.path("sha");
    return sha.isMissingNode() ? Optional.empty() : Optional.of(sha.asText());
  }

  /** Single JSON transport for the Git Data calls. Returns null on any non-expected status. */
  private JsonNode sendJson(
      String method, String url, ObjectNode requestBody, String pat, int expectedStatus) {
    try {
      HttpRequest.BodyPublisher publisher =
          requestBody == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(requestBody.toString());
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .header("Authorization", "Bearer " + pat)
              .header("Accept", "application/vnd.github+json");
      if (requestBody != null) builder.header("Content-Type", "application/json");
      HttpResponse<String> res =
          http.send(
              builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != expectedStatus) {
        log.debug("{} {} returned {}", method, url, res.statusCode());
        return null;
      }
      return json.readTree(res.body());
    } catch (Exception ex) {
      log.debug("{} {} failed: {}", method, url, ex.getMessage());
      return null;
    }
  }
}
