package com.navneet.executespec.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BitbucketCloudScmRepositoryClientTest {
  private HttpServer server;
  private URI base;
  private final List<String> requestedUris = new ArrayList<>();
  private final List<String> authorizationHeaders = new ArrayList<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/2.0");
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void catalogInspectHeadInventoryAndReadUseBoundedCredentialSafeTransport() {
    BitbucketCloudScmRepositoryClient client = client();
    ScmCredential credential = new ScmCredential("forge", "app-secret-value");

    var repositories = client.listRepositories("acme", credential);
    assertThat(repositories)
        .extracting(ScmRemoteRepository::immutableId)
        .containsExactly("{id-1}", "{id-2}");

    ScmRemoteRepository repository = client.inspect("acme/api", credential);
    assertThat(repository.fullName()).isEqualTo("acme/api");
    assertThat(client.inspect("acme/old-slug", "{id-1}", credential).fullName())
        .isEqualTo("acme/api");
    assertThat(repository.capabilities())
        .contains("SYNC", "SOURCE_READINESS", "SOURCE_BROWSE")
        .doesNotContain(
            "ARCHITECTURE",
            "CODE_REVIEW",
            "PR_REVIEW",
            "WEBHOOK",
            "BOOTSTRAP",
            "CLOUD_RUN",
            "LOCAL_RUN");
    assertThat(client.branchHeadSha(repository, "main", credential)).isEqualTo("abc123");
    assertThat(client.inventory(repository, "main", credential).files())
        .containsExactly("README.md", "src/App.java");
    assertThat(client.readFile(repository, "main", "README.md", credential)).isEqualTo("hello\n");

    assertThat(requestedUris)
        .allSatisfy(uri -> assertThat(uri).doesNotContain("forge", "app-secret-value", "@"));
    assertThat(authorizationHeaders)
        .allMatch(
            header ->
                header.equals(
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString(
                                "forge:app-secret-value".getBytes(StandardCharsets.UTF_8))));
    assertThat(credential.toString()).doesNotContain("app-secret-value");
  }

  @Test
  void rejectsCrossOriginPaginationBeforeCredentialCouldBeForwarded() {
    BitbucketCloudScmRepositoryClient client = client();
    assertThatThrownBy(
            () -> client.listRepositories("acme", new ScmCredential("forge", "unsafe-secret")))
        .isInstanceOf(ScmProviderException.class)
        .hasMessageContaining("BITBUCKET_UNSAFE_PAGINATION_URL");
    assertThat(requestedUris).hasSize(1);
  }

  @Test
  void requiresWorkspaceInsteadOfCallingRemovedCrossWorkspaceCatalogue() {
    BitbucketCloudScmRepositoryClient client = client();

    assertThatThrownBy(
            () -> client.listRepositories(null, new ScmCredential("forge", "safe-secret")))
        .isInstanceOf(ScmProviderException.class)
        .hasMessageContaining("BITBUCKET_WORKSPACE_REQUIRED");
    assertThat(requestedUris).isEmpty();
  }

  @Test
  void exposesRateLimitAsTypedRetryableFailure() {
    BitbucketCloudScmRepositoryClient client = client();
    assertThatThrownBy(() -> client.inspect("rate/limited", new ScmCredential("forge", "secret")))
        .isInstanceOfSatisfying(
            ScmProviderException.class,
            error -> {
              assertThat(error.errorCode()).isEqualTo("BITBUCKET_RATE_LIMITED");
              assertThat(error.upstreamStatus()).isEqualTo(429);
              assertThat(error.retryable()).isTrue();
            });
  }

  private BitbucketCloudScmRepositoryClient client() {
    return new BitbucketCloudScmRepositoryClient(
        new ObjectMapper(), HttpClient.newHttpClient(), base);
  }

  private void handle(HttpExchange exchange) throws IOException {
    requestedUris.add(exchange.getRequestURI().toString());
    authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getQuery();
    if (path.equals("/2.0/repositories/acme") && query != null && query.contains("role=member")) {
      if (query.contains("page=2")) {
        json(exchange, 200, "{\"values\":[" + repositoryJson("{id-2}", "acme/web") + "]}");
      } else if (query.contains("sort=full_name") && query.contains("role=member")) {
        String next =
            exchange
                    .getRequestHeaders()
                    .getFirst("Authorization")
                    .equals(
                        "Basic "
                            + Base64.getEncoder()
                                .encodeToString(
                                    "forge:unsafe-secret".getBytes(StandardCharsets.UTF_8)))
                ? "https://attacker.example/steal"
                : base + "/repositories/acme?role=member&pagelen=100&page=2";
        json(
            exchange,
            200,
            "{\"values\":["
                + repositoryJson("{id-1}", "acme/api")
                + "],\"next\":\""
                + next
                + "\"}");
      }
      return;
    }
    if (path.equals("/2.0/repositories/acme/api")) {
      json(exchange, 200, repositoryJson("{id-1}", "acme/api"));
      return;
    }
    if (path.equals("/2.0/repositories/acme/{id-1}")) {
      json(exchange, 200, repositoryJson("{id-1}", "acme/api"));
      return;
    }
    if (path.equals("/2.0/repositories/rate/limited")) {
      json(exchange, 429, "{\"error\":{\"message\":\"slow down\"}}");
      return;
    }
    if (path.equals("/2.0/repositories/acme/{id-1}/refs/branches/main")) {
      json(exchange, 200, "{\"target\":{\"hash\":\"abc123\"}}");
      return;
    }
    if (path.equals("/2.0/repositories/acme/{id-1}/src/main")
        || path.equals("/2.0/repositories/acme/{id-1}/src/main/")) {
      json(
          exchange,
          200,
          "{\"values\":[{\"path\":\"README.md\",\"type\":\"commit_file\"},{\"path\":\"src\",\"type\":\"commit_directory\"}]}");
      return;
    }
    if (path.equals("/2.0/repositories/acme/{id-1}/src/main/src")) {
      json(exchange, 200, "{\"values\":[{\"path\":\"src/App.java\",\"type\":\"commit_file\"}]}");
      return;
    }
    if (path.equals("/2.0/repositories/acme/{id-1}/src/main/README.md")) {
      text(exchange, 200, "hello\n");
      return;
    }
    json(exchange, 404, "{}");
  }

  private String repositoryJson(String id, String fullName) {
    String name = fullName.substring(fullName.indexOf('/') + 1);
    return "{\"uuid\":\""
        + id
        + "\",\"full_name\":\""
        + fullName
        + "\",\"name\":\""
        + name
        + "\",\"mainbranch\":{\"name\":\"main\"},\"is_private\":true,\"size\":2048,\"links\":{\"clone\":[{\"name\":\"https\",\"href\":\"https://bitbucket.org/"
        + fullName
        + ".git\"}]}}";
  }

  private static void json(HttpExchange exchange, int status, String body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    text(exchange, status, body);
  }

  private static void text(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
