package com.threeam.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.support.MysqlIntegrationTest;
import com.threeam.support.Race;
import com.threeam.support.Race.Outcome;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EntitlementConsumeIntegrationTest extends MysqlIntegrationTest {

    private static final long USER_ID = 11L;

    @Autowired
    private EntitlementRepository entitlementRepository;

    @Autowired
    private DbUsageLimiter limiter;

    @BeforeEach
    void clean() {
        jdbc().update("DELETE FROM entitlements");
    }

    @Test
    @DisplayName("한 번 남은 이용권을 동시에 차감하면 정확히 하나만 1을 받고 사용 횟수는 총량에서 멈춘다")
    void concurrentConsumeOfLastUseHasOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            clean();
            Long id = entitlementWithUsesLeft(3, 1);

            List<Outcome<Integer>> outcomes = Race.run(THREADS,
                    i -> updateInTx(() -> entitlementRepository.consumeOne(id)));

            assertThat(outcomes).as("%d번째 판", round).allMatch(Outcome::succeeded);
            assertThat(outcomes.stream().filter(o -> o.value() == 1).count())
                    .as("%d번째 판의 승자 수", round).isEqualTo(1);
            assertThat(outcomes.stream().filter(o -> o.value() == 0).count()).isEqualTo(THREADS - 1);
            assertThat(entitlementRepository.findById(id).orElseThrow().getUsedCount()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("차감 요청이 잔여보다 많이 몰려도 이용권은 총량을 넘겨 쓰이지 않는다")
    void concurrentRecordNeverOverConsumes() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            clean();
            Long first = entitlementWithUsesLeft(2, 2);
            Long second = entitlementWithUsesLeft(3, 3);

            List<Outcome<Boolean>> outcomes = Race.run(THREADS, i -> {
                limiter.record(UsageKind.CHAT, USER_ID, 1);
                return true;
            });

            assertThat(outcomes).as("%d번째 판", round).allMatch(Outcome::succeeded);
            assertThat(entitlementRepository.findById(first).orElseThrow().getUsedCount()).isEqualTo(2);
            assertThat(entitlementRepository.findById(second).orElseThrow().getUsedCount()).isEqualTo(3);
            assertThat(entitlementRepository.remainingOf(USER_ID, UsageKind.CHAT)).isZero();
        }
    }

    @Test
    @DisplayName("회수된 이용권은 동시에 몰려도 한 번도 차감되지 않는다")
    void revokedEntitlementIsNeverConsumed() throws Exception {
        Long id = entitlementWithUsesLeft(3, 3);
        int revoked = updateInTx(() -> entitlementRepository.revoke(id, LocalDateTime.now()));
        assertThat(revoked).isEqualTo(1);

        List<Outcome<Integer>> outcomes = Race.run(THREADS,
                i -> updateInTx(() -> entitlementRepository.consumeOne(id)));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes).allMatch(o -> o.value() == 0);
        assertThat(entitlementRepository.findById(id).orElseThrow().getUsedCount()).isZero();
        assertThat(entitlementRepository.findConsumableIds(USER_ID, UsageKind.CHAT)).isEmpty();
    }

    // 사용 횟수를 직접 쓰는 setter가 없어 실제 차감 쿼리로 미리 써 둔다. 순차 차감 경로의 확인을 겸한다.
    private Long entitlementWithUsesLeft(int total, int left) {
        Long id = entitlementRepository.save(Entitlement.builder()
                .userId(USER_ID).kind(UsageKind.CHAT).totalCount(total).build()).getId();
        for (int i = 0; i < total - left; i++) {
            assertThat(updateInTx(() -> entitlementRepository.consumeOne(id))).isEqualTo(1);
        }
        return id;
    }
}
