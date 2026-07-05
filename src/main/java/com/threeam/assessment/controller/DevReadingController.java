package com.threeam.assessment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ReadingProperties;
import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentReading;
import com.threeam.assessment.repository.AssessmentReadingRepository;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.assessment.service.AssessmentTxService;
import com.threeam.assessment.service.ReadingLlm;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.StoryRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

// 개발용 — 저장된 판독의 원석(분석 원문)만 가지고 분류 호출(luna)을 다시 돌린다. 분석(sol)을
// 다시 사지 않고 card-guide를 고쳐 가며 라벨, 이름, 책갈피만 볼 수 있다(2026-09-08).
// llm.reading.dev-endpoints=true(로컬 reading.yml)일 때만 뜨고, 루프백에서 온 요청만 받는다.
@Slf4j
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.reading.dev-endpoints", havingValue = "true")
public class DevReadingController {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    private final AssessmentRepository assessmentRepository;
    private final AssessmentReadingRepository readingRepository;
    private final StoryRepository storyRepository;
    private final AssessmentTxService txService;
    private final ReadingLlm readingLlm;
    private final ReadingProperties readingProperties;
    private final ObjectMapper objectMapper;

    // reload=true면 ./reading.yml에서 지시문을 다시 읽는다 — 재시작 없이 card-guide를 고쳐 본다.
    @PostMapping(value = "/reclassify/{assessmentId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public CompletableFuture<String> reclassify(@PathVariable Long assessmentId,
                                                @RequestParam(defaultValue = "true") boolean reload,
                                                HttpServletRequest request) {
        if (!LOOPBACK.contains(request.getRemoteAddr())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (reload) {
            reloadGuides();
        }
        Assessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSESSMENT_NOT_FOUND));
        AssessmentReading reading = readingRepository.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSESSMENT_NOT_FOUND));
        Story story = storyRepository.findByIdAndDeletedAtIsNull(assessment.getStoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        String raw;
        try {
            raw = objectMapper.readValue(reading.getBody(), ReadingDraft.class).synthesis();
        } catch (IOException e) {
            throw new IllegalStateException("판독 본문 파싱 실패", e);
        }
        AssessmentContext context = txService.loadContext(story.getUserId(), story.getId());
        return readingLlm.reclassify(raw, context.intakeBlock(), context.todayLine(),
                context.conversation().stream().map(ChatMessage::content).toList());
    }

    // sol을 다시 부른다 — reclassify는 저장된 본문을 재파싱만 해서, 분류 호출이 꺼진 뒤로는 헌법을 바꿔도
    // 같은 글이 되돌아온다(2026-09-15 실측). 저장하지 않고 본문만 돌려준다.
    @PostMapping(value = "/reread/{assessmentId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public CompletableFuture<String> reread(@PathVariable Long assessmentId,
                                            @RequestParam(defaultValue = "true") boolean reload,
                                            HttpServletRequest request) {
        if (!LOOPBACK.contains(request.getRemoteAddr())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (reload) {
            reloadGuides();
        }
        Assessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSESSMENT_NOT_FOUND));
        Story story = storyRepository.findByIdAndDeletedAtIsNull(assessment.getStoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        AssessmentContext context = txService.loadContext(story.getUserId(), story.getId());
        return readingLlm.readDirect(context.intakeBlock(), context.todayLine(),
                        context.conversation().stream().map(ChatMessage::content).toList())
                .thenApply(direct -> direct.draft() == null ? "" : direct.draft().synthesis());
    }

    @SuppressWarnings("unchecked")
    private void reloadGuides() {
        try (FileInputStream in = new FileInputStream("reading.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> reading = (Map<String, Object>)
                    ((Map<String, Object>) root.get("llm")).get("reading");
            readingProperties.setCardGuide(str(reading.get("card-guide")));
            readingProperties.setAnalysisGuide(str(reading.get("analysis-guide")));
            readingProperties.setKnowledge(str(reading.get("knowledge")));
            readingProperties.setKnowledgeFile(str(reading.get("knowledge-file")));
            if (reading.containsKey("analysis-ask")) {
                readingProperties.setAnalysisAsk(str(reading.get("analysis-ask")));
            }
            readingProperties.setGradeGuide(str(reading.get("grade-guide")));
            Object bySol = reading.get("card-by-sol");
            readingProperties.setCardBySol(Boolean.TRUE.equals(bySol));
            readingProperties.setCardModel(str(reading.get("card-model")));
            readingProperties.setGradeModel(str(reading.get("grade-model")));
            readingProperties.setAnalysisModel(str(reading.get("analysis-model")));
            readingProperties.setEditGuide(str(reading.get("edit-guide")));
            readingProperties.setEditModel(str(reading.get("edit-model")));
            Object decision = reading.get("decision-call");
            readingProperties.setDecisionCall(decision == null || Boolean.TRUE.equals(decision));
            log.info("dev: reading.yml 다시 읽음 — card-guide {}자, card-by-sol={}",
                    readingProperties.getCardGuide().length(), readingProperties.isCardBySol());
        } catch (IOException | RuntimeException e) {
            log.warn("dev: reading.yml 다시 읽기 실패 — 기존 지시문 유지: {}", e.toString());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

}
