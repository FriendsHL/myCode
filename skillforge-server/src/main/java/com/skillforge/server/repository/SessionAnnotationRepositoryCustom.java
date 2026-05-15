package com.skillforge.server.repository;

import java.math.BigDecimal;

/**
 * Custom repository method declarations for {@link SessionAnnotationRepository}.
 *
 * <p>Why this exists: Spring Data JPA's {@code @Modifying} contract restricts
 * the return type to {@code void / int / Integer}. The V1 W2 fix uses the
 * Postgres-native {@code INSERT ... ON CONFLICT ... RETURNING id} idiom to keep
 * the per-row idempotency signal (newly-inserted vs already-existed), which
 * needs a {@code Long} return. The implementation in
 * {@code SessionAnnotationRepositoryImpl} uses {@code EntityManager.createNativeQuery}
 * directly, bypassing the {@code @Modifying} contract.
 */
public interface SessionAnnotationRepositoryCustom {

    /**
     * V1 W2 fix: native PG upsert that inserts a new row or skips on UNIQUE
     * conflict ({@code uq_session_annotation}). Returns the generated id of the
     * newly-inserted row, or {@code null} when the row already existed.
     *
     * <p>H2 (unit-test dialect) does not support {@code ON CONFLICT}; service
     * unit tests mock {@link SessionAnnotationRepository#findBySessionId} et
     * al., and the IT suite ({@code SessionAnnotationPersistenceIT}) covers
     * real-PG behaviour.
     */
    Long upsertSkipDuplicate(String sessionId,
                             String annotationType,
                             String annotationValue,
                             String source,
                             BigDecimal confidence,
                             String reasoning);
}
