package com.skillforge.server.repository;

import com.skillforge.server.entity.ScheduledTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, Long> {

    /** List a user's scheduled tasks newest-first. */
    List<ScheduledTaskEntity> findByCreatorUserIdOrderByIdDesc(Long creatorUserId);

    /**
     * BE-2 startup recovery: register every enabled task on application boot
     * (see brief §3 INV-1). Disabled tasks are deliberately not loaded.
     */
    List<ScheduledTaskEntity> findByEnabledTrue();
}
