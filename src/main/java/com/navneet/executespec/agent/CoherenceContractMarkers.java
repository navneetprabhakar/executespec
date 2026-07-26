package com.navneet.executespec.agent;

import java.util.List;

/** Contract vocabulary used to retain high-value requirement evidence in coherence prompts. */
final class CoherenceContractMarkers {

  static final List<String> HIGH_SIGNAL =
      List.of(
          "base64",
          "bcrypt",
          "column",
          "dead_letter",
          "enum",
          "header format",
          "hex",
          "hmac",
          "sha-256",
          "signature",
          "status",
          "table");

  static final List<String> ALL =
      List.of(
          "api key",
          "authentication",
          "base64",
          "bcrypt",
          "column",
          "config",
          "constraint",
          "contract",
          "dead letter",
          "dead_letter",
          "dto",
          "encoding",
          "endpoint",
          "entity",
          "enum",
          "field",
          "format",
          "hash",
          "header",
          "hex",
          "hmac",
          "idempot",
          "identifier",
          "interface",
          "method",
          "outbox",
          "property",
          "repository",
          "request",
          "response",
          "replay",
          "schema",
          "secret",
          "sha-256",
          "status",
          "table",
          "tenant",
          "timestamp",
          "uuid");

  private CoherenceContractMarkers() {}
}
