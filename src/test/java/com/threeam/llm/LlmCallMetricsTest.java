package com.threeam.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmCallMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        Metrics.globalRegistry.add(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.globalRegistry.remove(registry);
    }

    @Test
    void slow_onlyWhenSuccessUsedMostOfTheTimeout() {
        LlmCallMetrics.record("record-test", TimeUnit.SECONDS.toNanos(9), 10, null);
        LlmCallMetrics.record("record-test", TimeUnit.SECONDS.toNanos(5), 10, null);
        // 실패는 이미 실패 알림이 따로 운다.
        LlmCallMetrics.record("record-test", TimeUnit.SECONDS.toNanos(10), 10, new HttpTimeoutException("t"));
        // 0이면 지연 집계를 끈다(스트리밍).
        LlmCallMetrics.record("record-test", TimeUnit.SECONDS.toNanos(60), 0, null);

        assertThat(registry.get("llm.calls.slow").tag("provider", "record-test").counter().count()).isEqualTo(1);
        assertThat(registry.get("llm.call.duration").tags("provider", "record-test", "result", "success").timer().count())
                .isEqualTo(3);
        assertThat(registry.get("llm.calls").tags("provider", "record-test", "reason", "timeout").counter().count())
                .isEqualTo(1);
    }

    @Test
    void measure_tracksInflightAndRecordsOnCompletion() {
        // 다른 테스트가 같은 프로바이더 이름으로 게이지를 먼저 등록하면 그 객체를 따라가므로 이름을 따로 쓴다.
        LlmCallMetrics metrics = new LlmCallMetrics("measure-test");
        CompletableFuture<String> pending = new CompletableFuture<>();

        CompletableFuture<String> measured = metrics.measure(30, () -> pending);
        assertThat(registry.get("llm.inflight").tag("provider", "measure-test").gauge().value()).isEqualTo(1);

        pending.complete("ok");
        measured.join();
        assertThat(registry.get("llm.inflight").tag("provider", "measure-test").gauge().value()).isZero();
        assertThat(registry.get("llm.calls").tags("provider", "measure-test", "result", "success").counter().count())
                .isEqualTo(1);
    }
}
