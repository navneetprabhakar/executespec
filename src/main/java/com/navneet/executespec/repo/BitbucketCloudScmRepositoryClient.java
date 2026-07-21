package com.navneet.executespec.repo;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Bounded Bitbucket Cloud source reader. No write/review/webhook capability is advertised. */
@Service
public class BitbucketCloudScmRepositoryClient implements ScmRepositoryClient {
  static final int MAX_CATALOG_PAGES = 5;
  static final int MAX_CATALOG_ITEMS = 500;
  static final int MAX_TREE_PAGES = 100;
  static final int MAX_TREE_DIRECTORIES = 500;
  static final int MAX_TREE_FILES = 10_000;

  private static final Set<ScmCapability> CAPABILITIES =
      Set.of(
          ScmCapability.CATALOG,
          ScmCapability.ATTACH,
          ScmCapability.SYNC,
          ScmCapability.SOURCE_READINESS,
          ScmCapability.SOURCE_BROWSE);

  private final ObjectMapper mapper;
  private final HttpClient http;
  private final URI apiBase;

  @Autowired
  public BitbucketCloudScmRepositoryClient(ObjectMapper mapper) {
    this(
        mapper,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        URI.create("https://api.bitbucket.org/2.0"));
  }

  BitbucketCloudScmRepositoryClient(ObjectMapper mapper, HttpClient http, URI apiBase) {
    this.mapper = mapper;
    this.http = http;
    this.apiBase = apiBase;
  }

  @Override
  public ScmProvider provider() {
    return ScmProvider.BITBUCKET;
  }

  @Override
  public Set<ScmCapability> capabilities() {
    return CAPABILITIES;
  }

  @Override
  public List<ScmRemoteRepository> listRepositories(String ownerFilter, ScmCredential credential) {
    requireCredential(credential);
    String workspace = requireWorkspace(ownerFilter);
    List<ScmRemoteRepository> repos = new ArrayList<>();
    // Bitbucket removed the cross-workspace GET /repositories?role=member endpoint under
    // CHANGE-2770. ExecuteSpec already stores the selected workspace, so use the supported,
    // least-privilege workspace-scoped catalogue instead of requiring an additional workspace
    // discovery scope.
    URI next =
        resolve("/repositories/" + segment(workspace) + "?role=member&pagelen=100&sort=full_name");
    for (int page = 0; next != null && page < MAX_CATALOG_PAGES; page++) {
      JsonNode root = getJson(next, credential);
      JsonNode values = root.path("values");
      if (!values.isArray())
        throw new ScmProviderException("BITBUCKET_INVALID_RESPONSE", 200, false);
      for (JsonNode value : values) {
        ScmRemoteRepository repo = mapRepository(value);
        if (repo == null) continue;
        if (repo.fullName().regionMatches(true, 0, workspace + "/", 0, workspace.length() + 1)) {
          repos.add(repo);
        }
        if (repos.size() >= MAX_CATALOG_ITEMS) break;
      }
      if (repos.size() >= MAX_CATALOG_ITEMS) break;
      next = safeNext(root.path("next").asText(null));
    }
    repos.sort((left, right) -> left.fullName().compareToIgnoreCase(right.fullName()));
    return List.copyOf(repos);
  }

  @Override
  public ScmRemoteRepository inspect(String fullName, ScmCredential credential) {
    return inspect(fullName, null, credential);
  }

  @Override
  public ScmRemoteRepository inspect(
      String fullName, String immutableId, ScmCredential credential) {
    requireCredential(credential);
    String canonical = requireFullName(fullName);
    JsonNode root =
        getJson(
            resolve("/repositories/" + repositoryCoordinate(canonical, immutableId)), credential);
    ScmRemoteRepository repository = mapRepository(root);
    if (repository == null) {
      throw new ScmProviderException("BITBUCKET_INVALID_REPOSITORY_METADATA", 200, false);
    }
    return repository;
  }

  @Override
  public String branchHeadSha(
      ScmRemoteRepository repository, String branch, ScmCredential credential) {
    requireCredential(credential);
    String effectiveBranch =
        branch == null || branch.isBlank() ? repository.defaultBranch() : branch;
    JsonNode root =
        getJson(
            resolve(
                "/repositories/"
                    + repositoryCoordinate(repository)
                    + "/refs/branches/"
                    + segment(effectiveBranch)),
            credential);
    String hash = root.path("target").path("hash").asText(null);
    return hash == null || hash.isBlank() ? null : hash;
  }

  @Override
  public ScmSourceInventory inventory(
      ScmRemoteRepository repository, String branch, ScmCredential credential) {
    requireCredential(credential);
    String effectiveBranch =
        branch == null || branch.isBlank() ? repository.defaultBranch() : branch;
    ArrayDeque<String> directories = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    List<String> files = new ArrayList<>();
    directories.add("");
    int pages = 0;
    boolean truncated = false;
    try {
      while (!directories.isEmpty()) {
        if (visited.size() >= MAX_TREE_DIRECTORIES || pages >= MAX_TREE_PAGES) {
          truncated = true;
          break;
        }
        String directory = directories.removeFirst();
        if (!visited.add(directory)) continue;
        URI next = sourceUri(repository, effectiveBranch, directory);
        while (next != null) {
          if (pages++ >= MAX_TREE_PAGES) {
            truncated = true;
            next = null;
            break;
          }
          JsonNode root = getJson(next, credential);
          JsonNode values = root.path("values");
          if (!values.isArray()) {
            return ScmSourceInventory.failed("BITBUCKET_TREE_INVALID_RESPONSE", 200);
          }
          for (JsonNode value : values) {
            String path = value.path("path").asText(null);
            String type = value.path("type").asText("");
            if (path == null || path.isBlank()) continue;
            if (type.endsWith("directory")) {
              if (visited.size() + directories.size() < MAX_TREE_DIRECTORIES) {
                directories.addLast(path);
              } else {
                truncated = true;
              }
            } else if (type.endsWith("file")) {
              files.add(path);
              if (files.size() >= MAX_TREE_FILES) {
                truncated = true;
                return ScmSourceInventory.success(files, true);
              }
            }
          }
          next = safeNext(root.path("next").asText(null));
        }
      }
      files.sort(String::compareToIgnoreCase);
      return ScmSourceInventory.success(files, truncated);
    } catch (ScmProviderException ex) {
      return ScmSourceInventory.failed(ex.errorCode(), ex.upstreamStatus());
    }
  }

  @Override
  public String readFile(
      ScmRemoteRepository repository, String branch, String path, ScmCredential credential) {
    requireCredential(credential);
    if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")) {
      throw new ScmProviderException("INVALID_REPOSITORY_PATH", null, false);
    }
    String effectiveBranch =
        branch == null || branch.isBlank() ? repository.defaultBranch() : branch;
    URI uri =
        resolve(
            "/repositories/"
                + repositoryCoordinate(repository)
                + "/src/"
                + segment(effectiveBranch)
                + "/"
                + encodePath(path));
    return getText(uri, credential);
  }

  private ScmRemoteRepository mapRepository(JsonNode node) {
    String id = node.path("uuid").asText(null);
    String fullName = node.path("full_name").asText(null);
    if (id == null || id.isBlank() || fullName == null || fullName.isBlank()) return null;
    String cloneUrl = null;
    JsonNode clones = node.path("links").path("clone");
    if (clones.isArray()) {
      for (JsonNode clone : clones) {
        if ("https".equalsIgnoreCase(clone.path("name").asText())) {
          cloneUrl = clone.path("href").asText(null);
          break;
        }
      }
    }
    if (cloneUrl == null) cloneUrl = "https://bitbucket.org/" + fullName + ".git";
    Long sizeKb =
        node.path("size").isNumber() ? Math.max(1L, node.path("size").asLong() / 1024L) : null;
    return new ScmRemoteRepository(
        provider().name(),
        id,
        fullName,
        node.path("name").asText(fullName),
        cloneUrl,
        node.path("mainbranch").path("name").asText("main"),
        node.path("is_private").asBoolean(false),
        sizeKb,
        CAPABILITIES.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
  }

  private JsonNode getJson(URI uri, ScmCredential credential) {
    try {
      return mapper.readTree(getText(uri, credential));
    } catch (ScmProviderException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ScmProviderException("BITBUCKET_INVALID_JSON", 200, false);
    }
  }

  private String getText(URI uri, ScmCredential credential) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(20))
              .header("Authorization", basicAuth(credential))
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
      throw statusFailure(response.statusCode());
    } catch (ScmProviderException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ScmProviderException("BITBUCKET_REQUEST_INTERRUPTED", null, true);
    } catch (Exception ex) {
      throw new ScmProviderException("BITBUCKET_REQUEST_FAILED", null, true);
    }
  }

  private ScmProviderException statusFailure(int status) {
    return switch (status) {
      case 401 -> new ScmProviderException("BITBUCKET_AUTHENTICATION_FAILED", status, false);
      case 403 -> new ScmProviderException("BITBUCKET_ACCESS_FORBIDDEN", status, false);
      case 404 -> new ScmProviderException("BITBUCKET_REPOSITORY_NOT_FOUND", status, false);
      case 429 -> new ScmProviderException("BITBUCKET_RATE_LIMITED", status, true);
      default ->
          new ScmProviderException("BITBUCKET_HTTP_ERROR", status, status >= 500 && status <= 599);
    };
  }

  private URI sourceUri(ScmRemoteRepository repository, String branch, String directory) {
    String suffix = directory == null || directory.isBlank() ? "" : "/" + encodePath(directory);
    return resolve(
        "/repositories/"
            + repositoryCoordinate(repository)
            + "/src/"
            + segment(branch)
            + suffix
            + "?pagelen=100");
  }

  private URI safeNext(String raw) {
    if (raw == null || raw.isBlank()) return null;
    URI next = apiBase.resolve(URI.create(raw));
    if (!sameOrigin(apiBase, next)) {
      throw new ScmProviderException("BITBUCKET_UNSAFE_PAGINATION_URL", null, false);
    }
    return next;
  }

  private URI resolve(String path) {
    return apiBase.resolve(apiBase.getPath().replaceAll("/$", "") + path);
  }

  private static boolean sameOrigin(URI left, URI right) {
    return left.getScheme().equalsIgnoreCase(right.getScheme())
        && left.getHost().equalsIgnoreCase(right.getHost())
        && effectivePort(left) == effectivePort(right);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) return uri.getPort();
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static String basicAuth(ScmCredential credential) {
    String raw = credential.username() + ":" + credential.secret();
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static void requireCredential(ScmCredential credential) {
    if (credential == null
        || !credential.usable()
        || credential.username() == null
        || credential.username().isBlank()) {
      throw new ScmProviderException("MISSING_BITBUCKET_CREDENTIALS", null, false);
    }
  }

  private static String requireFullName(String fullName) {
    String value = fullName == null ? "" : fullName.trim();
    if (!value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
      throw new ScmProviderException("INVALID_REPOSITORY_IDENTITY", null, false);
    }
    return value;
  }

  private static String requireWorkspace(String workspace) {
    String value = workspace == null ? "" : workspace.trim();
    if (!value.matches("[A-Za-z0-9_.-]+")) {
      throw new ScmProviderException("BITBUCKET_WORKSPACE_REQUIRED", 400, false);
    }
    return value;
  }

  private static String encodeFullName(String fullName) {
    String[] parts = requireFullName(fullName).split("/", 2);
    return segment(parts[0]) + "/" + segment(parts[1]);
  }

  private static String repositoryCoordinate(ScmRemoteRepository repository) {
    return repositoryCoordinate(repository.fullName(), repository.immutableId());
  }

  private static String repositoryCoordinate(String fullName, String immutableId) {
    String[] parts = requireFullName(fullName).split("/", 2);
    String repositoryKey =
        immutableId == null || immutableId.isBlank() ? parts[1] : immutableId.trim();
    return segment(parts[0]) + "/" + segment(repositoryKey);
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String encodePath(String path) {
    return java.util.Arrays.stream(path.split("/"))
        .map(BitbucketCloudScmRepositoryClient::segment)
        .collect(java.util.stream.Collectors.joining("/"));
  }
}
