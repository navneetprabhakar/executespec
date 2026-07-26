package com.navneet.executespec.previewrender;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.executioncontext.LlmDispatchPolicyException;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns one discovered UI surface into a self-contained, offline HTML approximation.
 *
 * <p>This agent deliberately has no tools. It reads source it is handed and emits a single document
 * — it cannot touch the workspace, run a build, or reach the network. Its output is decorative: it
 * never contributes to {@code verifiedScore} or to any validation evidence, because an LLM's
 * rendering of a screen is not proof that the screen works.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewRenderAgent {

  private static final String PROMPT = "preview-render.md";
  private static final int MAX_OUTPUT_TOKENS = 8192;

  private final LlmRouter llmRouter;
  private final ModelResolver modelResolver;
  private final PromptAssembler promptAssembler;
  private final PreviewRenderGuard guard;
  private final LlmExecutionPolicyFactory executionPolicyFactory;

  /**
   * The result of rendering one surface.
   *
   * @param surfaceId the surface this corresponds to
   * @param html admitted bundle, or null when rendering or admission failed
   * @param failureReason stable category when {@code html} is null; never contains model text
   */
  public record RenderedSurface(String surfaceId, String html, String failureReason) {
    public boolean ok() {
      return html != null;
    }

    static RenderedSurface failed(String surfaceId, String reason) {
      return new RenderedSurface(surfaceId, null, reason);
    }
  }

  /** Renders a single surface. Never throws — a failed render degrades to a reason code. */
  public RenderedSurface render(
      PreviewSurface surface, LlmProvider provider, String runId, String renderAttemptId) {
    try {
      ResolvedModel resolved =
          modelResolver.resolve(
              "PreviewRenderAgent",
              provider == null ? LlmProvider.ANTHROPIC : provider,
              AgentTier.WORKHORSE);

      String systemPrompt =
          promptAssembler.assemble(
              PROMPT,
              Map.of(
                  "TARGET", humanTarget(surface.target()),
                  "TARGET_GUIDANCE", guidanceFor(surface.target()),
                  "VIEWPORT_WIDTH", String.valueOf(surface.target().defaultWidth()),
                  "VIEWPORT_HEIGHT", String.valueOf(surface.target().defaultHeight())));

      List<Message> messages =
          List.of(new SystemMessage(systemPrompt), new UserMessage(buildEvidence(surface)));

      ChatResponse response =
          llmRouter.callText(
              resolved.providerName(),
              resolved.getModel(),
              messages,
              runId,
              "PreviewRenderAgent",
              "render:" + surface.id(),
              MAX_OUTPUT_TOKENS,
              com.navneet.executespec.agent.LlmRequest.PromptCacheStrategy.DEFAULT,
              executionPolicyFactory.forRunPreviewSurface(runId, renderAttemptId));

      String raw = response.getResult().getOutput().getText();
      String html = stripFences(raw);

      PreviewRenderGuard.Verdict verdict = guard.admit(html);
      if (!verdict.admitted()) {
        log.warn(
            "preview-render rejected surface={} run={} reason={}",
            surface.id(),
            runId,
            verdict.reason());
        return RenderedSurface.failed(surface.id(), verdict.reason());
      }

      return new RenderedSurface(surface.id(), html, null);

    } catch (RuntimeException e) {
      String reason = failureReason(e);
      log.warn(
          "preview-render failed surface={} run={} reason={} type={}",
          surface.id(),
          runId,
          reason,
          e.getClass().getSimpleName());
      return RenderedSurface.failed(surface.id(), reason);
    }
  }

  /**
   * Maps admission/policy failures to stable, non-secret categories the UI can explain honestly.
   */
  static String failureReason(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
      if (current instanceof LlmDispatchPolicyException policy) {
        return switch (policy.reason()) {
          case BUDGET_EXHAUSTED -> "BUDGET_EXHAUSTED";
          case CREDENTIAL_UNAVAILABLE, CREDENTIAL_SOURCE_CHANGED ->
              "PROVIDER_CREDENTIAL_UNAVAILABLE";
          default -> "PROVIDER_ROUTE_UNAVAILABLE";
        };
      }
      if (current instanceof ResponseStatusException status
          && status.getStatusCode().value() == HttpStatus.PAYMENT_REQUIRED.value()) {
        return "AI_CREDITS_REQUIRED";
      }
    }
    return "RENDER_ERROR";
  }

  // ---------------------------------------------------------------- prompt construction

  /** Frames the screen source and its supporting files as untrusted repository evidence. */
  private String buildEvidence(PreviewSurface surface) {
    StringBuilder sb = new StringBuilder();
    sb.append("Render the screen defined by `")
        .append(surface.path())
        .append("`.\n\n")
        .append(PromptTrustBoundary.wrap("SCREEN_SOURCE", surface.source()));

    if (!surface.supporting().isEmpty()) {
      sb.append(
          "\n\nThe following local files are referenced by that screen or define its design"
              + " tokens. Use them for layout and color; do not render them as separate"
              + " screens.\n\n");
      for (PreviewSurface.SupportingSource s : surface.supporting()) {
        sb.append(PromptTrustBoundary.wrap("SUPPORTING_FILE_" + safeLabel(s.path()), s.content()))
            .append("\n\n");
      }
    }
    return sb.toString();
  }

  private String safeLabel(String path) {
    String cleaned = path.replaceAll("[^A-Za-z0-9]+", "_");
    return cleaned.length() > 40 ? cleaned.substring(cleaned.length() - 40) : cleaned;
  }

  /**
   * Removes a markdown fence if the model wrapped its output despite the contract. Anything outside
   * the outermost HTML-looking region is discarded.
   */
  private String stripFences(String raw) {
    if (raw == null) {
      return null;
    }
    String text = raw.trim();
    if (text.startsWith("```")) {
      int firstNewline = text.indexOf('\n');
      if (firstNewline > 0) {
        text = text.substring(firstNewline + 1);
      }
      int closing = text.lastIndexOf("```");
      if (closing >= 0) {
        text = text.substring(0, closing);
      }
      text = text.trim();
    }
    int start = text.indexOf('<');
    int end = text.lastIndexOf('>');
    if (start > 0 && end > start) {
      text = text.substring(start, end + 1);
    }
    return text.trim();
  }

  private String humanTarget(PreviewRenderTarget target) {
    return switch (target) {
      case WEB -> "web (React, Vue, Svelte, or plain HTML)";
      case REACT_NATIVE -> "React Native / Expo";
      case ANDROID_NATIVE -> "native Android (Kotlin Compose or XML layout)";
      case IOS_NATIVE -> "native iOS (SwiftUI or UIKit)";
    };
  }

  /** Per-target rendering instructions appended to the shared system prompt. */
  private String guidanceFor(PreviewRenderTarget target) {
    return switch (target) {
      case WEB ->
          """
          ## Web specifics

          Resolve the component to its final rendered markup yourself. If the source is JSX,
          evaluate the JSX mentally and emit the resulting HTML — do not ship React and do not
          leave JSX in the output. Expand mapped lists into their repeated elements with varied
          invented data.

          If the project uses Tailwind, do not emit Tailwind class names, because there is no
          Tailwind runtime in the output. Translate each utility class into the equivalent literal
          CSS in your inline `<style>` block. The supporting files may include a Tailwind config
          with the project's custom colors and spacing — honor those exact values.

          If a router is present, render the single screen you were given, not the shell around it,
          unless the file itself is the layout.
          """;
      case REACT_NATIVE ->
          """
          ## React Native specifics

          React Native's primitives map cleanly onto flexbox, so translate them directly:
          `View` becomes `div` with `display: flex`, `Text` becomes `span` or `p`, `Image` becomes
          an inline SVG or gradient placeholder, `ScrollView` becomes a scrollable `div`,
          `FlatList` becomes the expanded list of its rendered rows, `Pressable`/`TouchableOpacity`
          become a styled `button` or `div`.

          Two defaults differ from the web and matter for correctness: React Native's default
          `flexDirection` is `column`, not `row`, and it has no inheritance of text styles across
          `View` boundaries. Apply those semantics rather than the browser's.

          `StyleSheet.create` objects convert to CSS with numeric values becoming `px`. Honor the
          exact numbers in the source.

          Draw only the screen content. The surrounding UI supplies the phone frame.
          """;
      case ANDROID_NATIVE ->
          """
          ## Android specifics

          You are reading a layout you cannot execute, so infer structure carefully and follow the
          source rather than inventing a generic Android screen.

          For Compose: `Column` becomes flex column, `Row` becomes flex row, `Box` becomes a
          relatively-positioned container, `Modifier.padding/size/weight/fillMaxWidth` become the
          corresponding CSS, `Spacer` becomes a sized empty div, `LazyColumn` becomes the expanded
          list. `Text` style parameters map to font size and weight.

          For XML layouts: `LinearLayout` becomes flex with the declared orientation,
          `ConstraintLayout` becomes a best-effort flex or grid approximation, `RecyclerView`
          becomes an expanded list, `dp` values map 1:1 to `px`. Resolve `@string`, `@color`, and
          `@dimen` references from the supporting files when present; otherwise substitute
          plausible values.

          Apply Material 3 visual conventions — elevation via subtle shadow, 4dp-grid spacing,
          rounded corners on cards and buttons — where the source implies a Material component.
          """;
      case IOS_NATIVE ->
          """
          ## iOS specifics

          You are reading a layout you cannot execute, so infer structure carefully and follow the
          source rather than inventing a generic iOS screen.

          For SwiftUI: `VStack` becomes flex column, `HStack` becomes flex row, `ZStack` becomes a
          relatively-positioned container, `Spacer` becomes `flex: 1`, `List` and `ForEach` become
          the expanded rows, `NavigationStack`/`NavigationView` becomes a large-title header.
          `.padding()` with no argument is 16px. Honor explicit `.frame`, `.font`, and
          `.foregroundColor` modifiers.

          For UIKit: read the view hierarchy from `viewDidLoad` and the constraint definitions, and
          approximate the resulting layout.

          Apply iOS visual conventions — SF-like system font stack, 17px body text, grouped list
          sections with rounded corners and inset margins, system blue `#007AFF` for interactive
          elements — where the source implies a standard control.
          """;
    };
  }
}
