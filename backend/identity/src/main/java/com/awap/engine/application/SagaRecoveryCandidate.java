package com.awap.engine.application;

import java.util.UUID;

/** A claimed saga that the recovery scheduler may resume. */
public record SagaRecoveryCandidate(UUID tenantId, UUID sagaInstanceId, UUID leaseToken) {}
