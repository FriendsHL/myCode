package com.skillforge.server.repository;

import com.skillforge.server.entity.PatternSessionMemberEntity;
import com.skillforge.server.entity.PatternSessionMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * PROD-LABEL-CLUSTER V1: JPA access for {@link PatternSessionMemberEntity}.
 *
 * <p>Composite PK type {@link PatternSessionMemberId}. {@code findByPatternId}
 * backs the {@code /api/insights/patterns/{id}/members} endpoint (Phase 1.4).
 */
public interface PatternSessionMemberRepository
        extends JpaRepository<PatternSessionMemberEntity, PatternSessionMemberId> {

    List<PatternSessionMemberEntity> findByPatternId(Long patternId);
}
