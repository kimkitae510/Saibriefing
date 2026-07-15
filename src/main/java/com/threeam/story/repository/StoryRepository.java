package com.threeam.story.repository;

import com.threeam.story.entity.Story;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoryRepository extends JpaRepository<Story, Long> {

    // 소프트 딜리트된 사연은 목록, 소유권 조회에서 제외한다(deletedAt IS NULL만).
    List<Story> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId);

    // 소유권까지 한 번에 건다. 없거나 남의 것이거나 삭제된 것이면 빈 Optional → 404로 통일(존재 여부 노출 방지).
    Optional<Story> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // 분석 공유의 공개 조회용 — 열람자가 소유자가 아니므로 소유권 없이 생존 여부만 본다.
    Optional<Story> findByIdAndDeletedAtIsNull(Long id);

    // 분석 작업 접수의 직렬화 지점 — 같은 사연의 "진행 중 확인 → 새 작업 삽입"이 겹치지 않게 사연 행을 잠근다.
    // 작업 행에 FOR UPDATE를 걸면 행이 아직 없을 때 삽입 경합을 못 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Story s where s.id = :id and s.deletedAt is null")
    Optional<Story> findByIdForUpdate(@Param("id") Long id);

    // INSUFFICIENT 재시도 가드 표시. ts=null이면 해제(성공 분석 시). 벌크 UPDATE라 엔티티 로드가 불필요하다.
    @Modifying(clearAutomatically = true)
    @Query("update Story s set s.lastInsufficientAt = :ts where s.id = :id")
    void updateLastInsufficientAt(@Param("id") Long id, @Param("ts") LocalDateTime ts);

    // 분석 실패 표시: 같은 재료(새 대화 없음)의 연속 실패는 카운트를 올리고,
    @Modifying(clearAutomatically = true)
    @Query("update Story s set s.assessFailStreak = s.assessFailStreak + 1, s.lastAssessFailedAt = :ts where s.id = :id")
    void incrementAssessFailStreak(@Param("id") Long id, @Param("ts") LocalDateTime ts);

    // 재료가 바뀐 뒤의 첫 실패는 1부터 다시 센다(새 대화마다 한 번의 재시도 여지를 되살린다).
    @Modifying(clearAutomatically = true)
    @Query("update Story s set s.assessFailStreak = 1, s.lastAssessFailedAt = :ts where s.id = :id")
    void restartAssessFailStreak(@Param("id") Long id, @Param("ts") LocalDateTime ts);

    @Modifying(clearAutomatically = true)
    @Query("update Story s set s.assessFailStreak = 0, s.lastAssessFailedAt = null where s.id = :id")
    void clearAssessFailStreak(@Param("id") Long id);
}
