package com.skillforge.server.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Custom implementation of {@link SessionAnnotationRepositoryCustom}. Spring
 * Data JPA wires this up by naming convention: class name = repository
 * interface name + {@code Impl} suffix.
 *
 * <p>Uses {@link EntityManager#createNativeQuery} directly so the {@code Long}
 * return type from {@code INSERT ... ON CONFLICT DO NOTHING RETURNING id} is
 * carried back to the caller (the {@code @Modifying @Query} path rejects
 * anything other than {@code void / int / Integer}).
 */
public class SessionAnnotationRepositoryImpl implements SessionAnnotationRepositoryCustom {

    private static final Logger log = LoggerFactory.getLogger(SessionAnnotationRepositoryImpl.class);

    private static final String UPSERT_SQL = """
            INSERT INTO t_session_annotation (
                session_id, annotation_type, annotation_value, source,
                confidence, reasoning, created_at
            ) VALUES (
                ?1, ?2, ?3, ?4, ?5, ?6, NOW()
            )
            ON CONFLICT ON CONSTRAINT uq_session_annotation
            DO NOTHING
            RETURNING id
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Long upsertSkipDuplicate(String sessionId,
                                    String annotationType,
                                    String annotationValue,
                                    String source,
                                    BigDecimal confidence,
                                    String reasoning) {
        Query q = entityManager.createNativeQuery(UPSERT_SQL);
        q.setParameter(1, sessionId);
        q.setParameter(2, annotationType);
        q.setParameter(3, annotationValue);
        q.setParameter(4, source);
        q.setParameter(5, confidence);
        q.setParameter(6, reasoning);
        try {
            Object result = q.getSingleResult();
            if (result == null) {
                return null;
            }
            return ((Number) result).longValue();
        } catch (NoResultException nre) {
            // ON CONFLICT DO NOTHING produced no row — already existed.
            return null;
        } catch (DataIntegrityViolationException dive) {
            // Defensive: shouldn't reach here because ON CONFLICT swallows the
            // unique violation, but cover the case where a different constraint
            // fires (e.g. NOT NULL).
            log.warn("[SessionAnnotationRepository.upsertSkipDuplicate] unexpected DIVE: {}",
                    dive.getMessage());
            return null;
        }
    }
}
