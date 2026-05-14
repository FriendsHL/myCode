package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionAnnotationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * PROD-LABEL-CLUSTER V1: JPA access for {@link SessionAnnotationEntity}.
 *
 * <p>Phase 1.1 deliberately ships only the lookup queries Phase 1.2+ services
 * are known to need (per-session listing). The rest will be added as services
 * land, to avoid speculative query surface.
 */
public interface SessionAnnotationRepository extends JpaRepository<SessionAnnotationEntity, Long> {

    List<SessionAnnotationEntity> findBySessionId(String sessionId);
}
