package com.threeam.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.FactExtractionProperties;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmJson;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryMemoryRepository;
import com.threeam.story.repository.StoryRepository;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

// 채팅에서 사실을 추출해 원장에 쌓고, 감정 흐름 요약도 함께 갱신한다.
// 답변 생성과 별도 호출로 분리한 이유: 답변(자유 텍스트)이 본체인 호출에 JSON을 강제하면
// 파싱 실패가 답변을 인질로 잡고, 페르소나 지시와 추출 규칙이 경쟁해 톤이 흔들린다.
// 요약을 여기 얹은 이유: 이미 대화를 읽는 호출이라 추가 비용이 없고,
// 사실과 요약 모두 "실패해도 유저가 몰라야 하는" 백그라운드 작업이라 결이 같다.
// fire-and-forget으로 돌리고, 실패는 로그만 남긴다. 유저 쿼터도 차감하지 않는다(내부 비용).
//
// 호출 빈도: 매 턴이 아니라 미추출 메시지가 임계(EXTRACT_THRESHOLD)만큼 쌓였을 때만 돈다.
// 매 턴 돌던 시절엔 채팅 1턴당 LLM 호출이 2번이었는데, 정작 대화 창(20개)에서 밀려나는 건
// 두 턴에 하나꼴이라 대부분의 호출이 같은 구간을 다시 읽고 있었다. 워터마크로 미추출 구간만
// 넘기면 호출 수와 입력 크기가 같이 줄고, 창보다 촘촘하게 걷어내므로 유실도 없다.
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryFactExtractor {

    // 미추출 메시지가 이만큼 쌓이면 추출한다(유저+어시스턴트 합산이라 20이면 10턴).
    // 대화 창 20개보다 작게 잡아야 창 밖으로 밀려나기 전에 걷어낸다.
    private static final int EXTRACT_THRESHOLD = 20;

    // 한 번에 넘길 미추출 메시지 상한. 오래 쉬었다 돌아온 사연이나 추출이 연속 실패한 뒤엔
    // 밀린 양이 클 수 있는데, 그걸 통째로 실으면 이 호출만 비용이 튄다. 남은 건 다음 회차가 집는다.
    private static final int EXTRACT_BATCH_LIMIT = 40;

    // 프롬프트에 싣는 기존 원장 개수. 중복 판정에 쓰이는 참고 자료라 전량일 필요가 없다 —
    // 전량 주입은 원장이 커질수록 입력이 선형으로 늘어 이 호출을 가장 비싼 호출로 만들었다.
    private static final int FACT_INJECT_LIMIT = 30;

    private static final DateTimeFormatter FACT_DATE = DateTimeFormatter.ofPattern("M/d");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final FactExtractionProperties extractionProperties;
    private final MessageRepository messageRepository;
    private final StoryRepository storyRepository;
    private final StoryFactRepository storyFactRepository;
    private final StoryMemoryRepository storyMemoryRepository;
    private final StoryFactService storyFactService;
    private final StoryMemoryService storyMemoryService;
    // 원장 쓰기를 HttpClient 스레드가 아니라 우리 풀에서(LlmCallbackConfig 참고).
    private final Executor llmCallbackExecutor;

    // 답변 저장이 끝난 뒤 매 턴 호출된다. 실제로 LLM을 부를지는 여기서 가른다.
    // 어떤 실패도 밖으로 던지지 않는다 — 채팅 흐름을 오염시키지 않기 위해.
    public void extractAsync(Long storyId) {
        try {
            Long watermark = storyRepository.findById(storyId)
                    .map(Story::getLastExtractedMessageId)
                    .orElse(null);
            // id는 양수라 0을 "처음부터"의 센티널로 쓴다.
            long from = watermark == null ? 0L : watermark;

            long pending = messageRepository.countByStoryIdAndIdGreaterThan(storyId, from);
            if (pending < EXTRACT_THRESHOLD) {
                return;
            }

            List<Message> batch = messageRepository
                    .findByStoryIdAndIdGreaterThanOrderByIdAsc(storyId, from,
                            PageRequest.of(0, EXTRACT_BATCH_LIMIT))
                    .getContent();
            if (batch.isEmpty()) {
                return;
            }
            // 워터마크는 이 배치의 마지막 id까지만 전진한다. 성공 콜백에서만 쓴다.
            Long batchEnd = batch.get(batch.size() - 1).getId();

            llmClient.generateJson(buildPrompt(storyId, batch))
                    .thenAcceptAsync(json -> {
                        Extraction extraction = parse(json);
                        storyFactService.appendFacts(storyId, null, extraction.newFacts());
                        storyMemoryService.upsert(storyId, extraction.summary());
                        storyFactService.markExtractedUpTo(storyId, batchEnd);
                    }, llmCallbackExecutor)
                    .exceptionally(ex -> {
                        // 워터마크를 안 옮겼으므로 이 구간은 다음 회차가 다시 집는다.
                        log.warn("채팅 사실 추출 실패 storyId={} 미추출 유지 from={}", storyId, from, ex);
                        return null;
                    });
        } catch (RuntimeException e) {
            log.warn("채팅 사실 추출 준비 실패 storyId={}", storyId, e);
        }
    }

    private record Extraction(List<String> newFacts, String summary) {}

    private List<ChatMessage> buildPrompt(Long storyId, List<Message> batch) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(extractionProperties.getPrompt()));

        // 최근 N개만 최신순으로 가져와 시간순으로 뒤집는다(비용 상한).
        List<StoryFact> facts = storyFactRepository.findByStoryIdOrderByIdDesc(
                storyId, PageRequest.of(0, FACT_INJECT_LIMIT));
        if (!facts.isEmpty()) {
            StringBuilder block = new StringBuilder("이미 기록된 사실(괄호는 기록일):");
            for (int i = facts.size() - 1; i >= 0; i--) {
                StoryFact fact = facts.get(i);
                block.append("\n- (").append(FACT_DATE.format(fact.getCreatedAt())).append(") ")
                        .append(fact.getFact());
            }
            prompt.add(ChatMessage.system(block.toString()));
        }

        // 직전 요약을 실어 감정 흐름이 이어지게 한다(요약은 흐름의 연속이지 매번 새 출발이 아니다).
        storyMemoryRepository.findByStoryId(storyId)
                .map(memory -> memory.getSummary())
                .filter(summary -> !summary.isBlank())
                .ifPresent(summary -> prompt.add(ChatMessage.system("지금까지 요약: " + summary)));

        for (Message message : batch) {
            prompt.add(message.getRole() == MessageRole.USER
                    ? ChatMessage.user(message.getContent())
                    : ChatMessage.assistant(message.getContent()));
        }
        return prompt;
    }

    // 파싱 실패는 호출부의 exceptionally가 받는다(로그만 남기고 무시).
    private Extraction parse(String json) {
        try {
            // 코드펜스, 잡설이 붙은 응답을 한 번 다듬어 살린다.
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));
            List<String> newFacts = new ArrayList<>();
            for (JsonNode node : root.path("newFacts")) {
                String fact = node.asText("").trim();
                if (fact.isBlank() || newFacts.size() >= StoryFact.MAX_PER_EXTRACT) {
                    continue;
                }
                newFacts.add(fact.length() > StoryFact.MAX_LENGTH
                        ? fact.substring(0, StoryFact.MAX_LENGTH)
                        : fact);
            }
            return new Extraction(newFacts, root.path("summary").asText(""));
        } catch (Exception e) {
            // json에는 사연에서 추출한 사실, 감정 요약이 들어 있어 개인정보다 — 예외 메시지에 원문을 싣지 않는다.
            throw new IllegalStateException(
                    "채팅 사실 추출 JSON 파싱 실패 (본문 길이 " + (json == null ? 0 : json.length()) + "자)", e);
        }
    }
}
