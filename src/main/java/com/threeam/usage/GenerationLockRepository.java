package com.threeam.usage;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GenerationLockRepository extends JpaRepository<GenerationLock, String> {

    // 락 획득은 insertIfAbsent → 0이면 takeOverIfExpired 순서로 한다. 둘 다 0이면 아직 유효한 락이다.
    // 예전엔 ON DUPLICATE KEY UPDATE 한 문장의 "값이 그대로면 0행"에 기댔는데, Connector/J 기본값
    // (useAffectedRows=false)이 CLIENT_FOUND_ROWS를 켜서 그 경우도 1이 돌아와 락이 한 번도 막지 못했다.
    // 아래 두 문장은 그 플래그가 있든 없든 반환값의 뜻이 같다.
    // 날짜는 DB 타임존에 안 묶이게 앱(KST)에서 계산해 넘긴다.
    @Modifying
    @Query(value = "INSERT IGNORE INTO generation_lock (lock_key, locked_until) VALUES (:key, :until)",
            nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("until") LocalDateTime until);

    // 만료된 락만 새 시각으로 뺏는다. 동시에 두 요청이 와도 행 락 뒤에서 조건을 다시 읽어 하나만 1을 받는다.
    @Modifying
    @Query(value = """
            UPDATE generation_lock SET locked_until = :until
            WHERE lock_key = :key AND locked_until < :now
            """, nativeQuery = true)
    int takeOverIfExpired(@Param("key") String key, @Param("now") LocalDateTime now,
                          @Param("until") LocalDateTime until);

    // 지금 이 유저의 생성이 실제로 돌고 있는지. 만료된 락은 없는 것으로 본다.
    // "답이 아직 안 붙었다"가 생성 중이어서인지, 재시작으로 사라진 턴이어서인지를 이 값이 가른다.
    boolean existsByLockKeyAndLockedUntilAfter(String lockKey, LocalDateTime now);

    // 정상 종료 시 즉시 반납. 실패로 못 부르면 locked_until 만료로 자동 해제된다(좀비 락 방지).
    @Modifying
    @Query(value = "DELETE FROM generation_lock WHERE lock_key = :key", nativeQuery = true)
    void release(@Param("key") String key);
}
