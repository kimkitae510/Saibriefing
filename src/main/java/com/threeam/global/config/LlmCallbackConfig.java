package com.threeam.global.config;

import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// LLM 응답 뒤에 붙는 후속 작업(답변 저장, 원장 적재, 분석 저장)을 돌릴 전용 풀.
//
// 전에는 thenAccept, handle을 executor 없이 써서 이 작업들이 HttpClient 내부 스레드에서 실행됐다.
// 톰캣 스레드는 안 잡으니 요청 처리엔 문제가 없었지만, DB 쓰기와 커넥션 획득이 우리가 만들지도
// 모니터링하지도 않는 풀에서 일어난다는 게 문제였다 — DB가 느려지면 지연이 거기 쌓이고,
// 커넥션 풀이 마르면 공용 풀이 같이 묶여 다른 LLM 응답 처리까지 밀린다.
// 후속 작업을 우리 풀로 분리하면 그 전파가 끊기고, 큐 길이와 스레드 수가 지표로 드러난다.
@Slf4j
@Configuration
public class LlmCallbackConfig {

    // 후속 작업은 짧은 DB 쓰기라 커넥션 풀 크기를 넘겨봐야 대기만 길어진다.
    // 최대치를 Hikari 기본(10)에 맞춰 두고, 넘치는 건 큐에서 기다리게 한다.
    private static final int CORE_SIZE = 4;
    private static final int MAX_SIZE = 10;
    private static final int QUEUE_CAPACITY = 200;

    @Bean
    public Executor llmCallbackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_SIZE);
        executor.setMaxPoolSize(MAX_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("llm-cb-");
        // 큐까지 찼으면 호출 스레드(=HttpClient 스레드)에서 직접 실행한다. 후속 작업은 유실되면
        // 답변이 저장되지 않는 것이라 버릴 수 없다 — 밀리더라도 반드시 실행되는 쪽을 택한다.
        executor.setRejectedExecutionHandler((task, pool) -> {
            log.warn("LLM 후속 작업 큐 포화 — 호출 스레드에서 직접 실행함(활성 {}, 큐 {})",
                    pool.getActiveCount(), pool.getQueue().size());
            task.run();
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
