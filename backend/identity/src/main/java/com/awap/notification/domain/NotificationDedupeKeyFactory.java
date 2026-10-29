package com.awap.notification.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Builds the bounded, tenant-scoped SHA-256 de-duplication key.
 *
 * <p>The raw material is never stored: every key is a 64-character lowercase hex digest, so an
 * arbitrarily long business key or explicit dedupe key cannot bloat the unique index (the column is
 * {@code VARCHAR(128)}). The tenant is always part of the hashed material, which makes cross-tenant
 * collisions impossible even if two tenants use the same explicit key.
 *
 * <p>Composition (the {@code |} separators are escaped by hashing, not by escaping the values):
 *
 * <pre>
 * explicit: explicit|{tenantId}|{explicitKey}
 * auto:     auto|{tenantId}|{channel}|{recipient}|{templateKey}|{partition}
 * </pre>
 *
 * <p>The {@code partition} is the trimmed business key when present. Without a business key a
 * {@link DedupePolicy#TRANSACTIONAL} event falls back to a hash of its variables payload (legacy
 * content idempotency), while a {@link DedupePolicy#PERIODIC} event uses a fixed sentinel so every
 * repeat competes for the same time window regardless of copy changes.
 */
public class NotificationDedupeKeyFactory {

  /**
   * Builds the key for one notification request.
   *
   * @param tenantId owning tenant, always hashed
   * @param channel delivery channel
   * @param recipient channel address
   * @param templateKey template identifier
   * @param policy resolved policy for the event type
   * @param businessKey workflow instance / task / approval / form identity, may be {@code null}
   * @param variablesJson payload, used only as the transactional fallback for a missing business
   *     key
   * @param explicitKey caller supplied key, hashed but otherwise used verbatim
   * @return a 64-character lowercase hex SHA-256 digest
   */
  public String build(
      UUID tenantId,
      NotificationChannel channel,
      String recipient,
      String templateKey,
      DedupePolicy policy,
      String businessKey,
      String variablesJson,
      String explicitKey) {
    String source;
    if (explicitKey != null && !explicitKey.isBlank()) {
      source = "explicit|" + tenantId + "|" + explicitKey;
    } else {
      String partition = partition(policy, businessKey, variablesJson);
      source =
          "auto|"
              + tenantId
              + "|"
              + channel
              + "|"
              + safe(recipient)
              + "|"
              + safe(templateKey)
              + "|"
              + partition;
    }
    return sha256Hex(source);
  }

  private static String partition(DedupePolicy policy, String businessKey, String variablesJson) {
    if (businessKey != null && !businessKey.isBlank()) {
      return "business:" + businessKey.trim();
    }
    if (policy == DedupePolicy.TRANSACTIONAL) {
      return "variables:" + (variablesJson == null ? "" : variablesJson);
    }
    return "periodic";
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static String sha256Hex(String source) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
