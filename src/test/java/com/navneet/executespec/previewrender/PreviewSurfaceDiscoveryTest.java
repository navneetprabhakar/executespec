package com.navneet.executespec.previewrender;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Discovery is deterministic on purpose — it decides the render's token cost before any provider
 * call happens, and it must never hand the agent a file that is not a screen.
 */
class PreviewSurfaceDiscoveryTest {

  @TempDir Path workspace;

  private final PreviewSurfaceDiscovery discovery = new PreviewSurfaceDiscovery();

  @Test
  void findsWebScreenAndClassifiesItAsWeb() throws IOException {
    write(
        "src/pages/Dashboard.tsx",
        "export default function Dashboard(){ return (<div className='p-4'><h1>Dash</h1></div>);"
            + " }");

    List<PreviewSurface> found = discovery.discover(workspace, Set.of(), 6, 200_000);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).target()).isEqualTo(PreviewRenderTarget.WEB);
    assertThat(found.get(0).displayName()).isEqualTo("Dashboard");
  }

  @Test
  void classifiesReactNativeByImportEvenOutsideScreenDirectory() throws IOException {
    write(
        "src/Home.tsx",
        "import {View, Text} from 'react-native';\n"
            + "export default function Home(){ return (<View><Text>Hi</Text></View>); }");

    List<PreviewSurface> found = discovery.discover(workspace, Set.of(), 6, 200_000);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).target()).isEqualTo(PreviewRenderTarget.REACT_NATIVE);
    assertThat(found.get(0).target().isMobile()).isTrue();
  }

  @Test
  void classifiesSwiftUiAndComposeAsNativeApproximations() throws IOException {
    write(
        "ios/ProfileView.swift",
        "import SwiftUI\n"
            + "struct ProfileView: View { var body: some View { VStack { Text(\"Hi\") } } }");
    write(
        "android/app/src/main/java/com/x/ProfileScreen.kt",
        "@Composable\nfun ProfileScreen(){ Column { Text(\"Hi\") } }");

    List<PreviewSurface> found = discovery.discover(workspace, Set.of(), 6, 200_000);

    assertThat(found).hasSize(2);
    assertThat(found)
        .extracting(PreviewSurface::target)
        .containsExactlyInAnyOrder(
            PreviewRenderTarget.IOS_NATIVE, PreviewRenderTarget.ANDROID_NATIVE);
    assertThat(found)
        .allSatisfy(
            s ->
                assertThat(s.target().fidelity())
                    .isEqualTo(PreviewRenderTarget.Fidelity.APPROXIMATE));
  }

  @Test
  void ignoresTestsStoriesAndNonUiSource() throws IOException {
    write("src/pages/Home.test.tsx", "export default function T(){ return (<div/>); }");
    write("src/pages/Home.stories.tsx", "export default function S(){ return (<div/>); }");
    write("src/utils/format.ts", "export const fmt = (n: number) => String(n);");
    write("src/hooks/useThing.ts", "export function useThing(){ return 1; }");
    write("android/app/src/main/java/com/x/Repo.kt", "class Repo { fun load() {} }");

    assertThat(discovery.discover(workspace, Set.of(), 6, 200_000)).isEmpty();
  }

  @Test
  void skipsVendorDirectories() throws IOException {
    write(
        "node_modules/pkg/screens/Fake.tsx",
        "export default function Fake(){ return (<div>no</div>); }");
    write("dist/screens/Built.tsx", "export default function B(){ return (<div>no</div>); }");
    write("src/screens/Real.tsx", "export default function Real(){ return (<div>yes</div>); }");

    List<PreviewSurface> found = discovery.discover(workspace, Set.of(), 6, 200_000);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).path()).isEqualTo("src/screens/Real.tsx");
  }

  @Test
  void ranksRunChangedFilesAboveUntouchedRepoScreens() throws IOException {
    write("src/screens/Existing.tsx", "export default function E(){ return (<div>old</div>); }");
    write("src/components/NewThing.tsx", "export default function N(){ return (<div>new</div>); }");

    List<PreviewSurface> found =
        discovery.discover(workspace, Set.of("src/components/NewThing.tsx"), 6, 200_000);

    // The component scores lower structurally, but the run actually produced it, so the operator
    // should see it first.
    assertThat(found.get(0).path()).isEqualTo("src/components/NewThing.tsx");
  }

  @Test
  void pullsInRelativeImportsAndDesignTokens() throws IOException {
    write("tailwind.config.js", "module.exports={theme:{colors:{brand:'#5b21b6'}}}");
    write("src/components/Card.tsx", "export default function Card(){ return (<div>card</div>); }");
    write(
        "src/pages/Home.tsx",
        "import Card from '../components/Card';\n"
            + "export default function Home(){ return (<div><Card/></div>); }");

    List<PreviewSurface> found = discovery.discover(workspace, Set.of(), 1, 200_000);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).supporting())
        .extracting(PreviewSurface.SupportingSource::path)
        .contains("src/components/Card.tsx", "tailwind.config.js");
  }

  @Test
  void respectsMaxSurfacesCap() throws IOException {
    for (int i = 0; i < 12; i++) {
      write(
          "src/screens/S" + i + ".tsx",
          "export default function S(){ return (<div>" + i + "</div>); }");
    }
    assertThat(discovery.discover(workspace, Set.of(), 4, 200_000)).hasSize(4);
  }

  @Test
  void respectsCharacterBudgetRatherThanOverspendingTokens() throws IOException {
    String big = "export default function S(){ return (<div>" + "x".repeat(5_000) + "</div>); }";
    for (int i = 0; i < 6; i++) {
      write("src/screens/S" + i + ".tsx", big);
    }

    List<PreviewSurface> found = discovery.discover(workspace, Set.of(), 6, 12_000);

    assertThat(found).isNotEmpty().hasSizeLessThan(6);
    assertThat(found.stream().mapToInt(PreviewSurface::characterCost).sum())
        .isLessThanOrEqualTo(12_000);
  }

  @Test
  void returnsEmptyForMissingWorkspaceRatherThanThrowing() {
    assertThat(discovery.discover(workspace.resolve("nope"), Set.of(), 6, 200_000)).isEmpty();
    assertThat(discovery.discover(null, Set.of(), 6, 200_000)).isEmpty();
  }

  @Test
  void derivesDisplayNameFromDirectoryForRouteMarkerFilenames() throws IOException {
    write("app/settings/page.tsx", "export default function P(){ return (<div>s</div>); }");

    List<PreviewSurface> found = discovery.discover(workspace, Set.of(), 6, 200_000);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).displayName()).isEqualTo("Settings");
  }

  /**
   * D5 regression. The walk cap must be spent on candidate screens, not on directories that are
   * skipped anyway. An ordinary JS repo carries far more files in {@code node_modules} than the cap
   * allows, so a cap applied before the skip filter finds zero surfaces in a repo full of screens.
   */
  @Test
  void ignoredDirectoriesDoNotConsumeTheWalkBudget() throws IOException {
    Path noise = workspace.resolve("node_modules/pkg");
    Files.createDirectories(noise);
    for (int i = 0; i < 25_000; i++) {
      Files.writeString(noise.resolve("f" + i + ".js"), "module.exports={};");
    }
    write(
        "src/pages/Dashboard.tsx",
        "export default function D(){ return (<div><h1>D</h1></div>); }");

    List<PreviewSurface> found = discovery.discover(workspace, Set.of(), 6, 200_000);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).path()).isEqualTo("src/pages/Dashboard.tsx");
  }

  /**
   * D5 regression. Discovery advertises itself as deterministic because the token cost has to be
   * knowable before any provider call. {@code Files.walk} yields entries in filesystem order, so
   * the selected set must be ordered by something stable rather than by iteration order.
   */
  @Test
  void selectionIsStableAcrossRepeatedWalksOfTheSameWorkspace() throws IOException {
    for (int i = 0; i < 40; i++) {
      write(
          "src/pages/Screen" + i + ".tsx",
          "export default function S(){ return (<div><h1>S</h1></div>); }");
    }

    List<String> first = paths(discovery.discover(workspace, Set.of(), 6, 200_000));
    List<String> second = paths(discovery.discover(workspace, Set.of(), 6, 200_000));

    assertThat(first).isEqualTo(second).hasSize(6);
    // A total order on equal scores means the tie-break is the path, not the directory listing.
    assertThat(first).isSorted();
  }

  private List<String> paths(List<PreviewSurface> surfaces) {
    return surfaces.stream().map(PreviewSurface::path).toList();
  }

  private void write(String relative, String content) throws IOException {
    Path target = workspace.resolve(relative);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content);
  }
}
