package com.navneet.executespec.deployment.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PreviewProjectType")
class PreviewProjectTypeTest {

    @Test
    @DisplayName("NODE_FRONTEND exposes port 5173 (Vite default)")
    void nodeFrontendPort() {
        assertThat(PreviewProjectType.NODE_FRONTEND.getContainerPort()).isEqualTo(5173);
    }

    @Test
    @DisplayName("NODE_BACKEND exposes port 3000")
    void nodeBackendPort() {
        assertThat(PreviewProjectType.NODE_BACKEND.getContainerPort()).isEqualTo(3000);
    }

    @Test
    @DisplayName("SPRING_BOOT exposes port 8080")
    void springBootPort() {
        assertThat(PreviewProjectType.SPRING_BOOT.getContainerPort()).isEqualTo(8080);
    }

    @Test
    @DisplayName("PYTHON exposes port 8000")
    void pythonPort() {
        assertThat(PreviewProjectType.PYTHON.getContainerPort()).isEqualTo(8000);
    }

    @Test
    @DisplayName("FULL_STACK exposes port 5173 (same as frontend — primary preview)")
    void fullStackPort() {
        assertThat(PreviewProjectType.FULL_STACK.getContainerPort()).isEqualTo(5173);
    }

    @Test
    @DisplayName("Core Node/Python types have distinct container ports")
    void distinctCoreProjectPorts() {
        // Only the original core types (NODE_FRONTEND, NODE_BACKEND, PYTHON) must have distinct ports.
        // Server-side types (SPRING_BOOT, JAVA, GO, RUST, PHP, STATIC_HTML) legitimately share 8080.
        var corePorts = java.util.List.of(
                PreviewProjectType.NODE_FRONTEND.getContainerPort(),
                PreviewProjectType.NODE_BACKEND.getContainerPort(),
                PreviewProjectType.PYTHON.getContainerPort()
        );
        assertThat(corePorts).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("All enum values have a positive container port")
    void allTypesHaveValidPort() {
        for (PreviewProjectType type : PreviewProjectType.values()) {
            assertThat(type.getContainerPort()).isGreaterThan(0);
        }
    }
}
