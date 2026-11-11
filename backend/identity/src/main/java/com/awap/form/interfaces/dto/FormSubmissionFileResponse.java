package com.awap.form.interfaces.dto;

import java.util.UUID;

/**
 * Metadata for a file accepted in a form submission (issue #200). The scan status is included so
 * the client can prove the referenced file was promoted and never shows a pending or infected one.
 */
public record FormSubmissionFileResponse(
    UUID fileId, String name, long size, String contentType, String scanStatus) {}
