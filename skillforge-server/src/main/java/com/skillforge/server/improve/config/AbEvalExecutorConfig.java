package com.skillforge.server.improve.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AbEvalExecutorConfig {

    @Bean(name = "abEvalCoordinatorExecutor", destroyMethod = "shutdown")
    public ExecutorService abEvalCoordinatorExecutor() {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("ab-eval-coord-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Sized off {@code skillforge.flywheel.ab-eval.scenario-concurrency} (default 3,
     * mirrors {@code AbEvalPipeline}'s Semaphore): each concurrent scenario spawns an
     * inner engine.run on this same pool, so it must hold ~2× the scenario
     * concurrency + a small buffer, else cascading RejectedExecutionException.
     */
    @Bean(name = "abEvalLoopExecutor", destroyMethod = "shutdown")
    public ExecutorService abEvalLoopExecutor(
            @org.springframework.beans.factory.annotation.Value(
                    "${skillforge.flywheel.ab-eval.scenario-concurrency:3}") int scenarioConcurrency) {
        int concurrency = Math.max(1, scenarioConcurrency);
        int poolMax = concurrency * 2 + 2;   // outer + inner engine.run per scenario, + buffer
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("ab-eval-loop-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                poolMax, poolMax, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(20, poolMax * 3)),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean(name = "skillEvolutionExecutor", destroyMethod = "shutdown")
    public ExecutorService skillEvolutionExecutor() {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("skill-evo-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
