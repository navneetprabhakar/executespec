package com.navneet.executespec.agentsession;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed store for validated, sanitized agent-chat upload content. The upload endpoint writes
 * the already-extracted + sanitized text here keyed by session + attachment id; the streaming turn
 * loads it server-side and injects it as untrusted-delimited context. Keeping the content
 * server-side (rather than round-tripping it through the client) means the injection delimiting and
 * the sanitize/scan gate cannot be bypassed by a crafted client request.
 *
 * <p>Key {@code agent:attach:<sessionId>:<attachmentId>}, JSON value, 2h TTL (an upload is normally
 * consumed by the very next turn; the TTL is generous headroom).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AgentAttachmentStore {

  public static final Duration TTL = Duration.ofHours(2);
  private static final String KEY_PREFIX = "agent:attach:";

  private final StringRedisTemplate redis;

  private static final ObjectMapper MAPPER =
      new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);

  /** A validated + sanitized upload, ready for server-side injection into a turn. */
  public record StoredAttachment(
      String attachmentId,
      String sessionId,
      String filename,
      String mimeType,
      long sizeBytes,
      int extractedChars,
      String sanitizedText,
      byte[] rawBytes,
      String createdAt) {

    public static StoredAttachment of(
        String attachmentId,
        String sessionId,
        String filename,
        String mimeType,
        long sizeBytes,
        String sanitizedText) {
      String text = sanitizedText == null ? "" : sanitizedText;
      return new StoredAttachment(
          attachmentId,
          sessionId,
          filename,
          mimeType,
          sizeBytes,
          text.length(),
          text,
          null,
          Instant.now().toString());
    }

    public static StoredAttachment image(
        String attachmentId,
        String sessionId,
        String filename,
        String mimeType,
        long sizeBytes,
        byte[] rawBytes) {
      return new StoredAttachment(
          attachmentId,
          sessionId,
          filename,
          mimeType,
          sizeBytes,
          0,
          "",
          rawBytes == null ? null : rawBytes.clone(),
          Instant.now().toString());
    }
  }

  private static String key(String sessionId, String attachmentId) {
    return KEY_PREFIX + sessionId + ":" + attachmentId;
  }

  /**
   * Persist a validated attachment. Throws if Redis is unreachable — the upload is useless then.
   */
  public void put(StoredAttachment a) {
    if (a == null || a.sessionId() == null || a.attachmentId() == null) return;
    try {
      redis
          .opsForValue()
          .set(key(a.sessionId(), a.attachmentId()), MAPPER.writeValueAsString(a), TTL);
    } catch (Exception e) {
      log.warn("AgentAttachmentStore put failed for {}: {}", a.attachmentId(), e.getMessage());
      throw new IllegalStateException("could not store attachment", e);
    }
  }

  /** Load a stored attachment, or empty when missing/expired. Scoped by session for isolation. */
  public Optional<StoredAttachment> get(String sessionId, String attachmentId) {
    if (sessionId == null || attachmentId == null) return Optional.empty();
    try {
      String json = redis.opsForValue().get(key(sessionId, attachmentId));
      if (json == null) return Optional.empty();
      return Optional.of(MAPPER.readValue(json, StoredAttachment.class));
    } catch (Exception e) {
      log.warn("AgentAttachmentStore get failed for {}: {}", attachmentId, e.getMessage());
      return Optional.empty();
    }
  }
}
