package com.navneet.executespec.integration.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RR-640 — generated Markdown must be XML-escaped before it becomes Confluence storage XHTML. */
class ConfluenceClientEscapeTest {

  @Test
  void escapesXmlMetacharacters() {
    assertThat(ConfluenceClient.xmlEscape("a < b & c > d \"q\" 'x'"))
        .isEqualTo("a &lt; b &amp; c &gt; d &quot;q&quot; &#39;x&#39;");
  }

  @Test
  void neutralizesStorageMacroInjection() {
    // An injected <ac:structured-macro> would be active Confluence markup if not escaped.
    String malicious = "<ac:structured-macro ac:name=\"html\">evil</ac:structured-macro>";
    String escaped = ConfluenceClient.xmlEscape(malicious);
    assertThat(escaped).doesNotContain("<ac:");
    assertThat(escaped).contains("&lt;ac:structured-macro");
  }

  @Test
  void nullIsEmpty() {
    assertThat(ConfluenceClient.xmlEscape(null)).isEmpty();
  }
}
