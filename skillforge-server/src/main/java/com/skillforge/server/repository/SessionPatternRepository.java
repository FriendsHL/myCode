package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionPatternEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * PROD-LABEL-CLUSTER V1: JPA access for {@link SessionPatternEntity}.
 *
 * <p>{@link #findBySignature(String)} backs the cluster-upsert idempotency
 * path in {@code SessionPatternClusterService.recompute} (Phase 1.4).
 */
public interface SessionPatternRepository extends JpaRepository<SessionPatternEntity, Long> {

    Optional<SessionPatternEntity> findBySignature(String signature);
}
