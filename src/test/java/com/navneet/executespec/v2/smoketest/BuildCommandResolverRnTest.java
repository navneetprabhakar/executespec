package com.navneet.executespec.v2.smoketest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the React Native + Expo detection branch of {@link BuildCommandResolver}.
 *
 * <p>app.json + package.json at workspace root is the high-signal marker. The
 * resolver must pick the RN smoke command ({@code expo export} + {@code tsc
 * --noEmit}) ahead of the generic {@code npm run build} fallback — otherwise RN
 * projects silently fall through to a no-op build.
 */
class BuildCommandResolverRnTest {

    private final BuildCommandResolver resolver = new BuildCommandResolver();

    @Test
    void rnExpoWorkspaceResolvesToExpoExport(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("app.json"),
                "{\"expo\":{\"name\":\"demo\",\"slug\":\"demo\"}}");
        Files.writeString(workspace.resolve("package.json"),
                "{\"dependencies\":{\"expo\":\"~52.0.0\",\"react\":\"18.3.1\"}}");

        BuildCommand cmd = resolver.resolve(workspace, null);

        assertThat(cmd.command()).isEqualTo(
                "npx expo export --platform android --output-dir dist && npx tsc --noEmit");
        assertThat(cmd.sourceDescription()).isEqualTo("inferred:expo-rn");
        assertThat(cmd.timeoutSeconds()).isEqualTo(300);
    }

    @Test
    void plainNodeProjectStillUsesNpmBuild(@TempDir Path workspace) throws IOException {
        // package.json alone (no app.json) must stay on the generic npm branch —
        // regression guard: the RN branch must not shadow existing Node behaviour.
        Files.writeString(workspace.resolve("package.json"),
                "{\"dependencies\":{\"express\":\"4.19.2\"}}");

        BuildCommand cmd = resolver.resolve(workspace, null);

        assertThat(cmd.command()).isEqualTo("npm run build --if-present");
        assertThat(cmd.sourceDescription()).isEqualTo("inferred:npm");
    }

    @Test
    void appJsonWithoutPackageJsonFallsThrough(@TempDir Path workspace) throws IOException {
        // app.json alone is not an Expo project — don't emit the RN command.
        // (Fallback is the no-op 'true' since no other markers match either.)
        Files.writeString(workspace.resolve("app.json"), "{\"expo\":{\"name\":\"demo\"}}");

        BuildCommand cmd = resolver.resolve(workspace, null);

        assertThat(cmd.command()).isEqualTo("true");
        assertThat(cmd.sourceDescription()).isEqualTo("fallback:no-op");
    }

    @Test
    void plannerDeclaredCommandOverridesInference(@TempDir Path workspace) throws IOException {
        // Even with RN markers, an explicit planner command wins — this is the
        // whole point of tier 1 in the resolution hierarchy.
        Files.writeString(workspace.resolve("app.json"), "{}");
        Files.writeString(workspace.resolve("package.json"), "{}");

        BuildCommand cmd = resolver.resolve(workspace, "npx expo prebuild --no-install");

        assertThat(cmd.command()).isEqualTo("npx expo prebuild --no-install");
        assertThat(cmd.sourceDescription()).isEqualTo("planner-declared");
    }
}
