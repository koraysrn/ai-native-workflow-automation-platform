package com.awap.document.application;

import java.util.UUID;

/**
 * Contract of a {@code document.events} record (issue #208).
 *
 * <p>The publisher carries the tenant id so the consumer can restore the tenant context (and RLS)
 * before touching any row. {@code version} and {@code objectKey} make the event replayable: the
 * consumer can recreate or re-apply exactly the same version row instead of inventing a new one,
 * which is what prevented the previous listener from looping on its own {@code
 * DOCUMENT_VERSION_CREATED} events.
 *
 * @param tenantId tenant that owns the file
 * @param fileId uploaded file id
 * @param eventType DOCUMENT_REGISTERED, DOCUMENT_VERSION_CREATED, DOCUMENT_ARCHIVED or
 *     DOCUMENT_DELETED
 * @param version document version number
 * @param size byte size for a created version
 * @param format detected/declared format for a created version
 * @param objectKey deterministic object key inside {@code awap-documents}
 */
public record DocumentLifecycleEvent(
    UUID tenantId,
    UUID fileId,
    String eventType,
    int version,
    long size,
    String format,
    String objectKey) {}
