package org.hanbat.ses.app.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 시뮬레이션 실행용 스레드 풀.
 *
 * <p>큐를 짧게 잡고 호출 스레드에서 실행하도록(CallerRuns) 두었다. 무한 큐를 쓰면
 * 부하가 몰릴 때 요청이 조용히 쌓이다가 메모리로 터진다. 즉시 느려지는 편이
 * 나중에 죽는 것보다 낫다.
 */
@Configuration
public class AsyncConfig {

    @Bean("simulationExecutor")
    public Executor simulationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors());
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("sim-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
