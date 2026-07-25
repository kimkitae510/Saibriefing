package com.threeam.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.support.MysqlIntegrationTest;
import com.threeam.support.Race;
import com.threeam.support.Race.Outcome;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GenerationLockIntegrationTest extends MysqlIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long USER_ID = 7L;

    @Autowired
    private DbUsageLimiter limiter;

    @Autowired
    private GenerationLockRepository generationLockRepository;

    @BeforeEach
    void clean() {
        jdbc().update("DELETE FROM generation_lock");
    }

    @Test
    @DisplayName("드라이버 기본값에선 값이 그대로인 ON DUPLICATE KEY UPDATE도 1을 돌려준다")
    void upsertReportsFoundRowsUnderDriverDefaults() {
        // 옛 락이 한 번도 막지 못한 원인을 고정해 둔다. 이 값이 0으로 바뀌면 누군가 드라이버 플래그를
        // 바꾼 것이고, 그러면 아래 테스트들은 운영과 다른 조건을 검증하게 된다.
        LocalDateTime until = LocalDateTime.now(KST).plusMinutes(5);
        jdbc().update("INSERT INTO generation_lock (lock_key, locked_until) VALUES (?, ?)", "premise", until);

        int affected = jdbc().update("""
                INSERT INTO generation_lock (lock_key, locked_until) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE locked_until = locked_until
                """, "premise", until);

        assertThat(affected).isEqualTo(1);
    }

    @Test
    @DisplayName("유효한 락이 있으면 두 번째 획득은 GENERATION_IN_PROGRESS로 거절된다")
    void rejectsSecondAcquireWhileLockIsValid() {
        limiter.acquireInFlight(UsageKind.CHAT, USER_ID);

        assertThatThrownBy(() -> limiter.acquireInFlight(UsageKind.CHAT, USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GENERATION_IN_PROGRESS));
        assertThat(limiter.isGenerating(UsageKind.CHAT, USER_ID)).isTrue();
    }

    @Test
    @DisplayName("락은 유저와 종류 단위라 다른 유저나 다른 종류는 막지 않는다")
    void lockIsScopedByUserAndKind() {
        limiter.acquireInFlight(UsageKind.CHAT, USER_ID);

        assertThatCode(() -> limiter.acquireInFlight(UsageKind.CHAT, USER_ID + 1)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquireInFlight(UsageKind.ASSESSMENT, USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("반납하면 생성 중이 아니게 되고 바로 다시 획득할 수 있다")
    void releaseThenAcquire() {
        limiter.acquireInFlight(UsageKind.CHAT, USER_ID);
        limiter.releaseInFlight(UsageKind.CHAT, USER_ID);

        assertThat(limiter.isGenerating(UsageKind.CHAT, USER_ID)).isFalse();
        assertThatCode(() -> limiter.acquireInFlight(UsageKind.CHAT, USER_ID)).doesNotThrowAnyException();
        assertThat(limiter.isGenerating(UsageKind.CHAT, USER_ID)).isTrue();
    }

    @Test
    @DisplayName("빈 키에 동시에 획득하면 정확히 하나만 이긴다")
    void concurrentAcquireOnFreeKeyHasOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            clean();

            List<Outcome<Boolean>> outcomes = Race.run(THREADS, i -> {
                limiter.acquireInFlight(UsageKind.CHAT, USER_ID);
                return true;
            });

            assertOneWinnerRestRejected(outcomes, round);
            assertThat(generationLockRepository.count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("만료된 락에 동시에 획득하면 정확히 하나만 뺏어 간다")
    void concurrentAcquireOnExpiredLockHasOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            clean();
            limiter.acquireInFlight(UsageKind.CHAT, USER_ID);
            // 키 형식에 기대지 않으려고 방금 만든 락 전체를 과거로 돌려 만료시킨다.
            jdbc().update("UPDATE generation_lock SET locked_until = ?", LocalDateTime.now(KST).minusMinutes(5));
            assertThat(limiter.isGenerating(UsageKind.CHAT, USER_ID)).isFalse();

            List<Outcome<Boolean>> outcomes = Race.run(THREADS, i -> {
                limiter.acquireInFlight(UsageKind.CHAT, USER_ID);
                return true;
            });

            assertOneWinnerRestRejected(outcomes, round);
            assertThat(limiter.isGenerating(UsageKind.CHAT, USER_ID)).isTrue();
        }
    }

    // 승자는 하나, 진 쪽은 전부 GENERATION_IN_PROGRESS여야 한다. 두 문장을 한 트랜잭션에 묶으면
    // INSERT IGNORE가 남긴 공유 락을 쥔 채 UPDATE의 배타 락을 기다리게 돼 InnoDB가 교착으로 끊고,
    // 진 쪽이 429 대신 500을 받는다. 교착 예외가 섞여 나오면 그 묶음이 되살아난 것이다.
    private void assertOneWinnerRestRejected(List<Outcome<Boolean>> outcomes, int round) {
        long winners = outcomes.stream().filter(Outcome::succeeded).count();
        assertThat(winners).as("%d번째 판의 승자 수", round).isEqualTo(1);

        outcomes.stream().filter(o -> !o.succeeded()).forEach(o ->
                assertThat(o.error()).as("%d번째 판 %d번 스레드의 실패", round, o.index())
                        .isInstanceOfSatisfying(BusinessException.class,
                                b -> assertThat(b.getErrorCode())
                                        .isEqualTo(ErrorCode.GENERATION_IN_PROGRESS)));
    }
}
