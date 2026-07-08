package com.threeam.story.repository;

import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // 커서 페이지네이션: id 역순(최신→과거). Slice는 내부적으로 size+1을 조회해 COUNT 없이 다음 페이지 유무를 판단한다.
    // 첫 페이지(커서 없음)이자, LLM 맥락(최근 N개) 조회용으로도 재사용한다(getContent()).
    Slice<Message> findByStoryIdOrderByIdDesc(Long storyId, Pageable pageable);

    // 위로 스크롤: 커서(마지막으로 로드한 가장 오래된 id)보다 과거만 가져온다.
    Slice<Message> findByStoryIdAndIdLessThanOrderByIdDesc(Long storyId, Long cursor, Pageable pageable);

    // 탐색 채팅 프롬프트와 판독 입력용: 이 사연의 대화 전체를 시간순으로. 창(window)을 두지 않는다 —
    // 대화는 시간 상한으로 짧게 유지되고, 잘라 보내면 상담자가 앞에서 들은 것을 다시 묻는다.
    List<Message> findByStoryIdOrderByIdAsc(Long storyId);

    // 폴링: 방금 보낸 메시지(after) 이후에 새로 생긴 메시지(주로 어시스턴트 답)를 시간순으로 가져온다.
    List<Message> findByStoryIdAndIdGreaterThanOrderByIdAsc(Long storyId, Long after);

    // 폴링용: 아직 아무것도 안 붙었는지만 본다(끊긴 턴 판정 전에 목록을 통째로 읽지 않기 위해).
    boolean existsByStoryIdAndIdGreaterThan(Long storyId, Long afterId);

    // 재분석 가드용: 마지막 분석 이후 새로 나눈 대화가 있는지.
    boolean existsByStoryIdAndCreatedAtAfter(Long storyId, LocalDateTime createdAt);

    // 사실 추출 게이팅용: 워터마크 이후 아직 안 훑은 메시지가 몇 개인지(임계 미만이면 LLM 호출 자체를 건너뛴다).
    long countByStoryIdAndIdGreaterThan(Long storyId, Long id);

    // 칩 추천 게이팅용: 지금까지 붙은 상담자 답변 수(2번째 답변부터 칩을 만든다).
    // 대화 창 안만 세면 긴 사연에서 창 밖으로 밀려난 답변을 놓치므로 전체를 센다.
    int countByStoryIdAndRole(Long storyId, MessageRole role);

    // 사실 추출용: 워터마크 이후 미추출 구간만 시간순으로. 밀린 양이 많을 때를 대비해 한 번에 가져올 개수를 제한한다.
    Slice<Message> findByStoryIdAndIdGreaterThanOrderByIdAsc(Long storyId, Long id, Pageable pageable);

    // 목록 미리보기용: 사연별 마지막 메시지를 한 방 쿼리로(IN + GROUP BY MAX — 사연 수만큼 도는 N+1 회피).
    @Query("select m from Message m where m.id in "
            + "(select max(m2.id) from Message m2 where m2.story.id in :storyIds group by m2.story.id)")
    List<Message> findLatestPerStory(@Param("storyIds") Collection<Long> storyIds);

    void deleteByStoryId(Long storyId);
}
