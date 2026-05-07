package com.skillforge.server.repository;

import com.skillforge.server.entity.ScheduledTaskRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRunEntity, Long> {

    /**
     * Run history for a single task, newest-first. Pageable lets the controller
     * plumb {@code limit} / {@code offset} from the query string.
     */
    List<ScheduledTaskRunEntity> findByTaskIdOrderByTriggeredAtDesc(Long taskId, Pageable pageable);
}
