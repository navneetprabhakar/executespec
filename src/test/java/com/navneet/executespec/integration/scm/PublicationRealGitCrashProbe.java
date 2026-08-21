package com.navneet.executespec.integration.scm;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.UUID;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.tool.GitTool;
import com.navneet.executespec.tool.GitToolResult;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Replacement-process probe that dies after a real Git push and before durable settlement. */
public final class PublicationRealGitCrashProbe {

  private PublicationRealGitCrashProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 12) {
      throw new IllegalArgumentException("expected 12 arguments");
    }
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(args[0]);
    dataSource.setUsername(args[1]);
    dataSource.setPassword(args[2]);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    PublicationPreparationStore store = new PublicationPreparationStore(jdbc);
    UUID preparationId = UUID.fromString(args[4]);
    UUID receiptId = UUID.fromString(args[5]);
    UUID ownerToken = UUID.fromString(args[11]);
    PublicationPreparationStore.PreparedRepository repository =
        store.preparedRepositories(preparationId).stream()
            .filter(candidate -> candidate.repositoryReceiptId().equals(receiptId))
            .findFirst()
            .orElseThrow();
    PublicationPreparationService.RequiredPreparation required =
        new PublicationPreparationService.RequiredPreparation(
            store.findPrepared(args[3], args[10], args[9]).orElseThrow(),
            store.preparedRepositories(preparationId));
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn(args[3]);
    when(lease.ownerToken()).thenReturn(ownerToken);
    when(lease.authority()).thenReturn(RunWorkspaceAuthorityService.Authority.PUBLICATION);
    PublicationAdmissionService.Admission admission =
        new PublicationAdmissionService.Admission(
            args[10], "PUBLICATION_ELIGIBLE", lease, required);
    GitTool git = new GitTool(mock(StringRedisTemplate.class));
    setWorkspaceRoot(git, args[6]);
    PublicationDispatchService dispatcher = new PublicationDispatchService(store);
    dispatcher.push(
        admission,
        repository,
        args[8],
        null,
        () -> {
          GitToolResult result =
              git.pushPreparedCommit(
                  args[3],
                  args[7],
                  args[8],
                  System.getenv("ES_R17_GITHUB_PAT"),
                  repository.publicationBranch(),
                  repository.commitId(),
                  null);
          if (!result.isOk()) {
            System.err.println(result.value());
            Runtime.getRuntime().halt(92);
          }
          Runtime.getRuntime().halt(91);
          return result;
        });
  }

  private static void setWorkspaceRoot(GitTool git, String root) throws Exception {
    Field field = GitTool.class.getDeclaredField("workspaceRoot");
    field.setAccessible(true);
    field.set(git, root);
  }
}
