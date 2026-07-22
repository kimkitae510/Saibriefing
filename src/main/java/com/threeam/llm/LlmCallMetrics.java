package com.threeam.llm;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// 프로바이더 하나의 호출 지표. 세 클라이언트가 같은 이름, 같은 태그 키를 써야 한다 —
// Prometheus 레지스트리는 키가 다른 같은 이름의 지표를 거부한다.
final class LlmCallMetrics {

    // 판정은 추론 모델이라 몇 분까지 간다. 짧은 호출(결정, 판별)과 긴 판정이 다 갈리게 넓게 잡는다.
    private static final Duration[] BUCKETS = seconds(1, 2, 5, 10, 20, 30, 60, 90, 120, 180, 240, 300);

    // 타임아웃의 이 비율을 넘기고 겨우 성공한 호출은 조금만 더 느려져도 실패가 된다.
    // 실패율이 오르기 전에 상대가 느려지는 것을 먼저 알리려고 따로 센다.
    private static final double SLOW_RATIO = 0.8;

    private final String provider;
    // 스레드가 아니라 "기다리는 응답"이 이 워크로드의 실제 동시성이다. 풀 스레드는 응답 대기 중에 놀고 있다.
    private final AtomicInteger inflight = new AtomicInteger();

    LlmCallMetrics(String provider) {
        this.provider = provider;
        Metrics.gauge("llm.inflight", Tags.of("provider", provider), inflight);
    }

    // JDK HttpClient 기본 내부 풀은 지표가 없어 밖에서 안 보인다. 이름 있는 풀을 쥐여 주고 묶으면
    // executor_*(활성, 큐)로 드러난다.
    static void bindPool(ExecutorService pool, String name) {
        new ExecutorServiceMetrics(pool, name, Tags.empty()).bindTo(Metrics.globalRegistry);
    }

    // timeoutSeconds가 0이면 지연 집계를 건너뛴다(타임아웃이 전체 시간을 재지 않는 스트리밍).
    // 호출 조립이 동기로 터져도 동시 대기 수가 새지 않게 여기서 받는다.
    <T> CompletableFuture<T> measure(long timeoutSeconds, Supplier<CompletableFuture<T>> call) {
        long start = System.nanoTime();
        inflight.incrementAndGet();
        CompletableFuture<T> future;
        try {
            future = call.get();
        } catch (RuntimeException e) {
            inflight.decrementAndGet();
            record(provider, System.nanoTime() - start, timeoutSeconds, e);
            throw e;
        }
        return future.whenComplete((result, ex) -> {
            inflight.decrementAndGet();
            record(provider, System.nanoTime() - start, timeoutSeconds, ex);
        });
    }

    static void record(String provider, long elapsedNanos, long timeoutSeconds, Throwable ex) {
        String result = ex == null ? "success" : "error";
        Metrics.counter("llm.calls",
                "provider", provider,
                "result", result,
                "reason", LlmFailureReason.of(ex)).increment();
        // 재시도 대기까지 포함한 시간이다 — 유저가 기다린 시간과 같다.
        Timer.builder("llm.call.duration")
                .tags("provider", provider, "result", result)
                .serviceLevelObjectives(BUCKETS)
                .register(Metrics.globalRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        if (ex == null && timeoutSeconds > 0
                && elapsedNanos >= SLOW_RATIO * TimeUnit.SECONDS.toNanos(timeoutSeconds)) {
            Metrics.counter("llm.calls.slow", "provider", provider).increment();
        }
    }

    private static Duration[] seconds(int... values) {
        Duration[] out = new Duration[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Duration.ofSeconds(values[i]);
        }
        return out;
    }
}
