package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.AppUserRepository.NameProjection;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class RunMapperCreatorProjectionTest {

  @Test
  void resolvesCreatorNameWithoutLoadingCredentialBearingUserEntity() {
    AppUserRepository users = mock(AppUserRepository.class);
    NameProjection name = mock(NameProjection.class);
    when(name.getFirstName()).thenReturn("Ada");
    when(name.getLastName()).thenReturn("Lovelace");
    when(users.findNameById("user-1")).thenReturn(Optional.of(name));

    RunMapper mapper =
        new RunMapper(
            null,
            null,
            users,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new ObjectMapper(),
            null,
            null);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setCreatedByUserId("user-1");

    assertThat(mapper.toSummary(run).createdByName()).isEqualTo("Ada Lovelace");
    verify(users).findNameById("user-1");
    verifyNoMoreInteractions(users);
  }
}
