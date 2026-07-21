package com.navneet.executespec.previewrender;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.security.GeneratedSecretScanner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RR-306: the policy must not be placed by regex.
 *
 * <p>The old implementation inserted the {@code <meta>} after the first regex match for {@code
 * <head...>}. Model output that mentions a head tag inside a comment, a script string or an
 * attribute produced a match before the real document head, leaving the policy inert exactly where
 * it is load-bearing — the {@code srcdoc} path, where the response header does not apply.
 */
class PreviewRenderCspEmbeddingTest {

  private final PreviewRenderGuard guard = new PreviewRenderGuard(new GeneratedSecretScanner());
  private final PreviewRenderController controller = new PreviewRenderController(null, guard, null);

  @Test
  @DisplayName("the policy is the first markup in the document")
  void policyLeadsTheDocument() {
    String out = controller.withEmbeddedCsp("<html><head><title>x</title></head><body>hi</body>");

    assertThat(out).startsWith("<!doctype html><meta http-equiv=\"Content-Security-Policy\"");
    assertThat(out).contains("default-src 'none'");
  }

  @Test
  @DisplayName("a decoy head tag inside a comment cannot displace the policy")
  void decoyInComment() {
    String hostile = "<!-- <head> --><html><head><script>1</script></head><body>x</body></html>";
    String out = controller.withEmbeddedCsp(hostile);

    int meta = out.indexOf("http-equiv=\"Content-Security-Policy\"");
    int decoy = out.indexOf("<!--");
    assertThat(meta).isGreaterThanOrEqualTo(0);
    assertThat(meta).isLessThan(decoy);
  }

  @Test
  @DisplayName("a decoy head tag inside a script string cannot displace the policy")
  void decoyInScriptString() {
    String hostile = "<script>var s = \"<head>\";</script><html><head></head><body>x</body></html>";
    String out = controller.withEmbeddedCsp(hostile);

    assertThat(out).startsWith("<!doctype html><meta http-equiv=\"Content-Security-Policy\"");
    assertThat(out.indexOf("http-equiv=\"Content-Security-Policy\""))
        .isLessThan(out.indexOf("<script>"));
  }

  @Test
  @DisplayName("a fragment with no head or html element still leads with the policy")
  void bareFragment() {
    String out = controller.withEmbeddedCsp("<div>only a fragment</div>");

    assertThat(out).startsWith("<!doctype html><meta http-equiv=\"Content-Security-Policy\"");
    assertThat(out).endsWith("<div>only a fragment</div>");
  }

  @Test
  @DisplayName("the model's own markup is never rewritten, only prefixed")
  void bodyIsNotMutated() {
    String body = "<html><head></head><body><p>unchanged &amp; intact</p></body></html>";

    assertThat(controller.withEmbeddedCsp(body)).endsWith(body);
  }
}
