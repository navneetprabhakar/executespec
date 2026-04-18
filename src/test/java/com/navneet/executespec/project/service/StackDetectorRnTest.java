package com.navneet.executespec.project.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link StackDetector#detectNodeFramework(List)} for the React Native +
 * Expo branch.
 *
 * <p>{@code app.json} at the repo root is the specific marker for managed Expo
 * projects. It must win over the generic "react-native" string-contains match
 * so Expo repos classify distinctly from bare React Native.
 */
class StackDetectorRnTest {

    @Test
    void appJsonAtRootClassifiesAsExpoRn() {
        List<String> files = List.of("package.json", "app.json", "App.tsx", "src/screens/Home.tsx");
        assertThat(StackDetector.detectNodeFramework(files)).isEqualTo("Expo (React Native)");
    }

    @Test
    void bareReactNativeWithoutAppJsonStillClassifiesAsReactNative() {
        // Legacy bare-RN projects without an app.json — keep the existing label so
        // detection doesn't regress on non-Expo RN repos.
        List<String> files = List.of("package.json", "metro.config.js", "src/react-native-components/Foo.tsx");
        assertThat(StackDetector.detectNodeFramework(files)).isEqualTo("React Native");
    }

    @Test
    void appJsonInSubdirDoesNotTriggerExpo() {
        // app.json is common in non-Expo contexts (e.g. some Node CLI tools put
        // configs under deep paths). Only the root file is a signal.
        List<String> files = List.of("package.json", "config/app.json", "server.js");
        assertThat(StackDetector.detectNodeFramework(files)).isEqualTo("Node");
    }

    @Test
    void nextJsWinsOverExpoWhenBothMarkersExist() {
        // If some oddball repo has both next.config.js and app.json, Next wins —
        // its config file is the more-specific marker of framework identity.
        List<String> files = List.of("package.json", "next.config.js", "app.json");
        assertThat(StackDetector.detectNodeFramework(files)).isEqualTo("Next.js");
    }
}
