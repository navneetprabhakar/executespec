package com.navneet.executespec.adr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdrInjectionServiceTest {

  @Mock AdrRepository adrRepo;

  @InjectMocks AdrInjectionService service;

  String projectId = UUID.randomUUID().toString();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "fullBodyThreshold", 15);
  }

  @Test
  void emptyMode_whenNoAcceptedAdrs() {
    when(adrRepo.findAcceptedForProject(projectId)).thenReturn(List.of());
    AdrInjectionService.Result r = service.compose(projectId);
    assertThat(r.mode()).isEqualTo(AdrInjectionService.Mode.EMPTY);
    assertThat(r.block()).isEmpty();
    assertThat(r.totalAdrs()).isZero();
  }

  @Test
  void fullBodyMode_atOrUnderThreshold() {
    when(adrRepo.findAcceptedForProject(projectId))
        .thenReturn(List.of(adr(42, "Use Postgres", "## Decision\nWe pick Postgres because…")));
    AdrInjectionService.Result r = service.compose(projectId);
    assertThat(r.mode()).isEqualTo(AdrInjectionService.Mode.FULL_BODY);
    assertThat(r.block()).contains("=== PROJECT_ADRS ===");
    assertThat(r.block()).contains("ADR 0042");
    assertThat(r.block()).contains("We pick Postgres");
  }

  @Test
  void indexOnlyMode_aboveThreshold() {
    // 20 ADRs > threshold 15 → INDEX_ONLY
    List<Adr> many =
        IntStream.range(1, 21)
            .mapToObj(i -> adr(i, "ADR " + i, "First-line summary of ADR " + i))
            .toList();
    when(adrRepo.findAcceptedForProject(projectId)).thenReturn(many);
    AdrInjectionService.Result r = service.compose(projectId);
    assertThat(r.mode()).isEqualTo(AdrInjectionService.Mode.INDEX_ONLY);
    assertThat(r.block()).contains("=== PROJECT_ADRS_INDEX ===");
    assertThat(r.block()).contains("[0001]");
    assertThat(r.block()).contains("[0020]");
    assertThat(r.block()).contains("Use the getAdr(id) tool");
    assertThat(r.totalAdrs()).isEqualTo(20);
  }

  @Test
  void firstNonHeadingLine_skipsHeadingsAndBlanks() {
    String body = "# Heading\n\n## Subhead\nactual content here\nmore content";
    assertThat(AdrInjectionService.firstNonHeadingLine(body)).isEqualTo("actual content here");
  }

  @Test
  void firstNonHeadingLine_returnsNullWhenAllHeadings() {
    assertThat(AdrInjectionService.firstNonHeadingLine("# x\n## y")).isNull();
  }

  @Test
  void truncate_handlesEdgeCases() {
    assertThat(AdrInjectionService.truncate(null, 10)).isNull();
    assertThat(AdrInjectionService.truncate("short", 100)).isEqualTo("short");
    assertThat(AdrInjectionService.truncate("0123456789", 5)).hasSize(5).endsWith("…");
  }

  private Adr adr(int idInRepo, String title, String body) {
    return Adr.builder()
        .id(UUID.randomUUID())
        .projectId(projectId)
        .repoId(UUID.randomUUID())
        .adrIdInRepo(idInRepo)
        .title(title)
        .status(Adr.Status.ACCEPTED.name())
        .decisionDate(LocalDate.of(2026, 4, 25))
        .body(body)
        .build();
  }
}
