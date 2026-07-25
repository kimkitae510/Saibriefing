package com.threeam.story.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.story.entity.FactSource;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 사실 원장의 유일한 쓰기 창구. 분석(AssessmentTxService)과 채팅 추출이 함께 쓴다.
// 원장은 append-only: 지우지 않고, 정정도 새 줄로 잇는다(프롬프트의 정정 기입 규칙).
@Slf4j
@Service
@RequiredArgsConstructor
public class StoryFactService {

    // 원장 크기 관측 기준. 상한이 아니다 — 넘어도 지우지 않고 로그만 남긴다.
    // 사건은 자연 포화되므로(한 이별 이야기의 사실은 유한) 여기 닿는 사연이 실제로 관측되면
    // 그때 병합(압축) 정책을 설계한다. 오래된 사실부터 지우는 방식은 가장 근본적인 사실을
    // 먼저 잃는 모순이 있어 폐기했다.
    private static final int WATCH_THRESHOLD = 100;

    private final StoryFactRepository storyFactRepository;
    private final StoryRepository storyRepository;

    // 추출 워터마크 전진. 추출이 성공했을 때만 부른다 — 실패하면 워터마크가 그대로라 다음 회차가 같은 구간을 다시 집는다.
    @Transactional
    public void markExtractedUpTo(Long storyId, Long lastMessageId) {
        storyRepository.findById(storyId).ifPresent(story -> story.markExtractedUpTo(lastMessageId));
    }

    // 동일 문장은 건너뛴다(프롬프트의 중복 금지 지시가 1차, 여기가 2차 방어). 지우는 일은 없다.
    // sourceAssessmentId는 분석 경로에서만 채워진다(채팅 추출은 null).
    @Transactional
    public void appendFacts(Long storyId, Long sourceAssessmentId, List<String> newFacts) {
        if (newFacts == null || newFacts.isEmpty()) {
            return;
        }
        List<StoryFact> existing = storyFactRepository.findByStoryIdOrderByIdAsc(storyId);
        Set<String> known = new HashSet<>();
        existing.forEach(fact -> known.add(normalize(fact.getFact())));

        List<StoryFact> toSave = new ArrayList<>();
        for (String fact : newFacts) {
            if (known.add(normalize(fact))) {
                toSave.add(StoryFact.of(storyId, fact, sourceAssessmentId));
            }
        }
        storyFactRepository.saveAll(toSave);

        int total = existing.size() + toSave.size();
        if (total > WATCH_THRESHOLD) {
            log.warn("사실 원장 관측 기준({}) 초과: storyId={}, 현재 {}건 — 병합 정책 검토 신호",
                    WATCH_THRESHOLD, storyId, total);
        }
    }

    // 유저가 분석 화면에서 직접 적어준 사실. 중복 제거 없이 그대로 남긴다 — 같은 문장을 또 적는 건
    // 유저의 선택이고(분석 재료는 temperature 0이라 같은 재료면 같은 결과, 어뷰징 이득이 없다),
    // 추출기를 태우지 않아 LLM 비용도 없다. id를 돌려주는 이유: 화면이 취소/수정을 걸 수 있게.
    @Transactional
    public Long appendUserFact(Long userId, Long storyId, String fact) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        return storyFactRepository.save(StoryFact.userProvided(storyId, fact.trim())).getId();
    }

    // 유저가 직접 적은 줄의 취소. append-only 원칙은 대화에서 추출된 사실의 것이다 — 방금 손으로
    // 적은 줄을 지우는 건 사실의 번복이 아니라 입력 취소라서, USER 출처에 한해서만 지운다.
    @Transactional
    public void deleteUserFact(Long userId, Long storyId, Long factId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        StoryFact fact = storyFactRepository.findById(factId)
                .filter(f -> f.getStoryId().equals(storyId) && f.getSource() == FactSource.USER)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_FACT_NOT_FOUND));
        storyFactRepository.delete(fact);
    }

    // 번복(정정) 기록은 중복 제거 없이 매번 새 줄로 남긴다 — 같은 문장이라도 각각 별개의 사건이다.
    // appendFacts를 타면 두 번째 번복부터 건너뛰어져 정정이 옛 위치에 머물고, 그 뒤에 쌓인
    // "사귀는 중" 류 사실들에게 시간순으로 밀려 정정이 영구히 무력화된다.
    @Transactional
    public void appendCorrection(Long storyId, String fact) {
        storyFactRepository.save(StoryFact.of(storyId, fact, null));
    }

    private String normalize(String fact) {
        return fact.replaceAll("\\s+", " ").trim();
    }
}
