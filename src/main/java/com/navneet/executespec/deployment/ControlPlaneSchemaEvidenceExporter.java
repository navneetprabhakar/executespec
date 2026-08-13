package com.navneet.executespec.deployment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.navneet.executespec.workspacegeneration.WorkspaceGenerationSchemaAttestor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Exports exact applied-migration and PostgreSQL definition evidence for release qualification. */
@Service
@RequiredArgsConstructor
public class ControlPlaneSchemaEvidenceExporter {

  private final JdbcTemplate jdbc;

  public Evidence export(WorkspaceGenerationSchemaAttestor.Attestation schema) {
    List<AppliedMigration> migrations =
        jdbc.query(
            "SELECT installed_rank,version,description,type,script,checksum,installed_on,success "
                + "FROM flyway_schema_history ORDER BY installed_rank",
            (rs, row) ->
                new AppliedMigration(
                    rs.getInt("installed_rank"),
                    rs.getString("version"),
                    rs.getString("description"),
                    rs.getString("type"),
                    rs.getString("script"),
                    (Integer) rs.getObject("checksum"),
                    rs.getTimestamp("installed_on").toInstant(),
                    rs.getBoolean("success")));

    List<Definition> definitions = new ArrayList<>(schema.artifacts().size());
    schema.artifacts().stream()
        .sorted(
            Comparator.comparing(WorkspaceGenerationSchemaAttestor.Artifact::kind)
                .thenComparing(WorkspaceGenerationSchemaAttestor.Artifact::name))
        .forEach(
            expected -> {
              Definition definition = definition(expected);
              definitions.add(definition);
            });

    boolean complete =
        schema.databaseReachable()
            && schema.ready()
            && !migrations.isEmpty()
            && migrations.stream().anyMatch(AppliedMigration::success)
            && definitions.size() == schema.artifacts().size()
            && definitions.stream()
                .allMatch(
                    definition ->
                        definition.present()
                            && definition.active()
                            && definition.digestMatchesAttestor());
    return new Evidence(List.copyOf(migrations), List.copyOf(definitions), complete);
  }

  private Definition definition(WorkspaceGenerationSchemaAttestor.Artifact expected) {
    DefinitionState state =
        "INDEX".equals(expected.kind())
            ? jdbc
                .query(
                    "SELECT pg_get_indexdef(i.indexrelid), (i.indisvalid AND i.indisready) "
                        + "FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid "
                        + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                        + "WHERE n.nspname=current_schema() AND c.relname=?",
                    (rs, row) -> new DefinitionState(rs.getString(1), rs.getBoolean(2)),
                    expected.name())
                .stream()
                .findFirst()
                .orElse(null)
            : jdbc
                .query(
                    "SELECT pg_get_triggerdef(t.oid,true), (t.tgenabled IN ('O','A')) "
                        + "FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid "
                        + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                        + "WHERE n.nspname=current_schema() AND t.tgname=? AND NOT t.tgisinternal",
                    (rs, row) -> new DefinitionState(rs.getString(1), rs.getBoolean(2)),
                    expected.name())
                .stream()
                .findFirst()
                .orElse(null);
    if (state == null) {
      return new Definition(expected.kind(), expected.name(), false, false, null, null, false);
    }
    String digest = ControlPlaneReleaseAttestationService.sha256(state.definition());
    return new Definition(
        expected.kind(),
        expected.name(),
        true,
        state.active(),
        state.definition(),
        digest,
        digest.equals(expected.definitionSha256()));
  }

  private record DefinitionState(String definition, boolean active) {}

  public record AppliedMigration(
      int installedRank,
      String version,
      String description,
      String type,
      String script,
      Integer checksum,
      Instant installedOn,
      boolean success) {}

  public record Definition(
      String kind,
      String name,
      boolean present,
      boolean active,
      String definition,
      String definitionSha256,
      boolean digestMatchesAttestor) {}

  public record Evidence(
      List<AppliedMigration> appliedMigrations, List<Definition> definitions, boolean complete) {}
}
