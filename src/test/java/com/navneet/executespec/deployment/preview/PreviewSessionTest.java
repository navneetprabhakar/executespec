package com.navneet.executespec.deployment.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PreviewSession")
class PreviewSessionTest {

    @Test
    @DisplayName("isExpired returns true when expiresAt is in the past")
    void expiredSession() {
        var session = new PreviewSession(
                "run-1", "es-preview-run1", PreviewProjectType.NODE_FRONTEND, null,
                PreviewStatus.RUNNING, 10000, "http://localhost:10000", null,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60)
        );
        assertThat(session.isExpired()).isTrue();
    }

    @Test
    @DisplayName("isExpired returns false when expiresAt is in the future")
    void activeSession() {
        var session = new PreviewSession(
                "run-1", "es-preview-run1", PreviewProjectType.NODE_FRONTEND, null,
                PreviewStatus.RUNNING, 10000, "http://localhost:10000", null,
                Instant.now(), Instant.now().plusSeconds(3600)
        );
        assertThat(session.isExpired()).isFalse();
    }

    @Test
    @DisplayName("isExpired returns false when expiresAt is null (no expiry)")
    void noExpirySession() {
        var session = new PreviewSession(
                "run-1", "es-preview-run1", PreviewProjectType.NODE_FRONTEND, null,
                PreviewStatus.RUNNING, 10000, "http://localhost:10000", null,
                Instant.now(), null
        );
        assertThat(session.isExpired()).isFalse();
    }

    @Test
    @DisplayName("Record fields are accessible")
    void recordFieldAccess() {
        var session = new PreviewSession(
                "run-1", "es-preview-run1", PreviewProjectType.SPRING_BOOT, DatabaseType.POSTGRESQL,
                PreviewStatus.BUILDING, 10042, "http://localhost:10042", "some error",
                Instant.now(), Instant.now().plusSeconds(900)
        );

        assertThat(session.runId()).isEqualTo("run-1");
        assertThat(session.composeProjectName()).isEqualTo("es-preview-run1");
        assertThat(session.projectType()).isEqualTo(PreviewProjectType.SPRING_BOOT);
        assertThat(session.databaseType()).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(session.status()).isEqualTo(PreviewStatus.BUILDING);
        assertThat(session.hostPort()).isEqualTo(10042);
        assertThat(session.url()).isEqualTo("http://localhost:10042");
        assertThat(session.errorMessage()).isEqualTo("some error");
    }

    @Test
    @DisplayName("Session with database type reports it correctly")
    void sessionWithDatabase() {
        var session = new PreviewSession(
                "run-db", "es-preview-rundb", PreviewProjectType.NODE_BACKEND, DatabaseType.MYSQL,
                PreviewStatus.RUNNING, 10050, "http://localhost:10050", null,
                Instant.now(), Instant.now().plusSeconds(3600)
        );

        assertThat(session.databaseType()).isEqualTo(DatabaseType.MYSQL);
        assertThat(session.isExpired()).isFalse();
    }
}
