package com.navneet.executespec.integration.scm;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Separate-process recovery probe for durable publication/provision crash windows. */
public final class PublicationRecoveryTakeoverProbe {

  private PublicationRecoveryTakeoverProbe() {}

  public static void main(String[] args) {
    if (args.length != 4) {
      throw new IllegalArgumentException("expected jdbc,user,password,cutoffEpochMillis");
    }
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(args[0]);
    dataSource.setUsername(args[1]);
    dataSource.setPassword(args[2]);
    PublicationPreparationStore store =
        new PublicationPreparationStore(new JdbcTemplate(dataSource));
    Instant cutoff = Instant.ofEpochMilli(Long.parseLong(args[3]));
    PublicationPreparationStore.ExternalRecovery attempts =
        store.recoverStalePublicationAttempts(cutoff);
    PublicationPreparationStore.ExternalRecovery provisions =
        store.recoverStaleRepositoryProvisions(cutoff);
    int preparations = store.recoverStalePreparations(cutoff);
    System.out.printf(
        "PUBLICATION_TAKEOVER_PASSED attemptsFailed=%d attemptsIndeterminate=%d "
            + "provisionsFailed=%d provisionsIndeterminate=%d preparations=%d%n",
        attempts.admittedFailed(),
        attempts.dispatchedIndeterminate(),
        provisions.admittedFailed(),
        provisions.dispatchedIndeterminate(),
        preparations);
  }
}
