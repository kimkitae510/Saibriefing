package com.threeam.assessment.repository;

import com.threeam.assessment.entity.AssessmentJob;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentJobRepository extends JpaRepository<AssessmentJob, Long> {

    Optional<AssessmentJob> findFirstByStoryIdAndStatusInOrderByIdDesc(Long storyId,
                                                                        Collection<AssessmentJob.Status> statuses);

    Optional<AssessmentJob> findFirstByStoryIdAndStatusOrderByIdDesc(Long storyId, AssessmentJob.Status status);

    Optional<AssessmentJob> findByIdAndStoryId(Long id, Long storyId);

    // 진행 중인 작업 전부 — 재시작 복구용. 단일 인스턴스라 기동 시점의 진행 중 작업은 전부 죽은 프로세스의 것이다.
    List<AssessmentJob> findByStatusIn(Collection<AssessmentJob.Status> statuses);

    // 갱신이 끊긴 채 진행 중인 작업 — 서버가 죽어 콜백이 사라진 판. 접수와 폴링 때 걸러 FAILED로 돌린다.
    List<AssessmentJob> findByStatusInAndUpdatedAtBefore(Collection<AssessmentJob.Status> statuses,
                                                         LocalDateTime before);
}
