package com.threeam.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

// 스레드를 모두 출발선에 세운 뒤 한꺼번에 풀어 DB에 동시에 부딪히게 한다.
// 실패도 결과로 모은다. 진 쪽이 어떤 예외로 지는지가 검증 대상인 경우가 있어서다.
public final class Race {

    private Race() {
    }

    public record Outcome<T>(int index, T value, RuntimeException error) {

        public boolean succeeded() {
            return error == null;
        }
    }

    public static <T> List<Outcome<T>> run(int threads, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome<>(index, task.apply(index), null);
                    } catch (RuntimeException e) {
                        return new Outcome<T>(index, null, e);
                    }
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("경합 스레드가 출발선에 다 서지 못함");
            }
            go.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get(120, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
