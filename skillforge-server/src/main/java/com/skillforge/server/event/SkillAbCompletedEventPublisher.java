package com.skillforge.server.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tiny indirection bean that wraps {@link ApplicationEventPublisher#publishEvent}
 * inside a Spring-managed {@code @Transactional} method.
 *
 * <p>Why this exists: {@link SkillAbCompletedEvent} listeners are registered with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}. AFTER_COMMIT only
 * fires if the publisher is inside an active transaction whose commit can be
 * observed. {@link com.skillforge.server.improve.SkillAbEvalService#runAbTestAsync}
 * runs on a thread-pool ({@code abEvalCoordinatorExecutor}) outside any TX
 * boundary, and self-invocation of a {@code @Transactional} method on the same
 * service does NOT go through Spring's AOP proxy. Calling this dedicated bean
 * does — its {@code @Transactional} opens a real TX, the publish gets queued
 * against that TX synchronizer, the TX commits, the AFTER_COMMIT listener
 * fires.
 *
 * <p>The TX itself is essentially empty (no DB writes here); we just need the
 * commit point so the synchronizer fires.
 */
@Component
public class SkillAbCompletedEventPublisher {

    private final ApplicationEventPublisher delegate;

    public SkillAbCompletedEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Transactional
    public void publish(SkillAbCompletedEvent event) {
        delegate.publishEvent(event);
    }
}
