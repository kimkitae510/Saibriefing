package com.threeam.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.llm.ChatGoalProperties;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmJson;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.StoryGoal;
import com.threeam.story.repository.StoryGoalRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 탐색 채팅의 목표가 어디까지 찼는지를 가른다. 답변 호출과 따로 두는 이유는 판독 쪽 실측과 같다 —
// 산문이 본체인 호출에 구조화 출력을 섞으면 사고가 조각나고, 파싱 실패가 답변을 인질로 잡는다.
// 매 턴 답변 전에 돈다: 방금 들어온 말이 무엇을 채웠는지 알아야 상담자가 같은 것을 다시 안 묻는다.
// 실패는 답변을 막지 않는다 — 지금까지 저장된 상태로 답변이 나가고, 놓친 판정은 다음 턴이 다시 본다.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoalJudge {

    private final ChatGoalProperties goalProperties;
    private final ChatLlm chatLlm;
    private final StoryGoalRepository storyGoalRepository;
    private final ObjectMapper objectMapper;

    public record FilledGoal(String key, String name, String evidence) {}

    // capped: 목표가 덜 찼어도 턴 상한에 닿아 마무리로 넘기는 판.
    public record GoalState(List<FilledGoal> filled, List<ChatGoalProperties.Goal> remaining,
                            boolean capped) {

        public static final GoalState DISABLED = new GoalState(List.of(), List.of(), false);

        public int total() {
            return filled.size() + remaining.size();
        }

        public boolean complete() {
            return total() > 0 && remaining.isEmpty();
        }

        // 상담자가 마무리로 넘길 판인지 — 다 찼거나 상한에 닿았거나.
        public boolean closing() {
            return complete() || capped;
        }
    }

    // 저장된 판정만으로 상태를 만든다(LLM 없음). 화면 표시와 판정 실패 시의 폴백.
    public GoalState current(Long storyId, long userTurns) {
        if (!goalProperties.enabled()) {
            return GoalState.DISABLED;
        }
        return stateOf(storyId, storyGoalRepository.findByStoryIdOrderByIdAsc(storyId), userTurns);
    }

    // 대화 전체를 읽혀 새로 채워진 목표를 저장하고, 갱신된 상태를 돌려준다. 어떤 실패도 밖으로 던지지 않는다.
    public CompletableFuture<GoalState> judge(Long storyId, List<Message> transcript, long userTurns) {
        if (!goalProperties.enabled()) {
            return CompletableFuture.completedFuture(GoalState.DISABLED);
        }
        List<StoryGoal> saved = storyGoalRepository.findByStoryIdOrderByIdAsc(storyId);
        GoalState before = stateOf(storyId, saved, userTurns);
        // 유저 발화가 없으면(첫 말 턴) 채워질 것도 없다 — 호출을 아낀다.
        if (before.remaining().isEmpty() || userTurns == 0) {
            return CompletableFuture.completedFuture(before);
        }
        try {
            return chatLlm.judge(buildPrompt(before.remaining(), transcript), SCHEMA)
                    .thenApply(json -> {
                        List<StoryGoal> added = persistNewlyFilled(storyId, before.remaining(), json);
                        List<StoryGoal> all = new ArrayList<>(saved);
                        all.addAll(added);
                        return stateOf(storyId, all, userTurns);
                    })
                    .exceptionally(ex -> {
                        log.warn("목표 판정 실패 storyId={} — 저장된 상태로 진행", storyId, ex);
                        return before;
                    });
        } catch (RuntimeException e) {
            log.warn("목표 판정 준비 실패 storyId={}", storyId, e);
            return CompletableFuture.completedFuture(before);
        }
    }

    private GoalState stateOf(Long storyId, List<StoryGoal> saved, long userTurns) {
        Map<String, String> evidenceByKey = new LinkedHashMap<>();
        for (StoryGoal goal : saved) {
            evidenceByKey.put(goal.getGoalKey(), goal.getEvidence());
        }
        List<FilledGoal> filled = new ArrayList<>();
        List<ChatGoalProperties.Goal> remaining = new ArrayList<>();
        // 순서는 정의 파일 순 — 저장 순서가 아니라 물어야 할 우선순위가 프롬프트에 그대로 실린다.
        for (ChatGoalProperties.Goal goal : goalProperties.getItems()) {
            String evidence = evidenceByKey.get(goal.getKey());
            if (evidence != null) {
                filled.add(new FilledGoal(goal.getKey(), goal.getName(), evidence));
            } else {
                remaining.add(goal);
            }
        }
        int cap = goalProperties.getMaxUserTurns();
        boolean capped = cap > 0 && userTurns >= cap && !remaining.isEmpty();
        return new GoalState(filled, remaining, capped);
    }

    private List<ChatMessage> buildPrompt(List<ChatGoalProperties.Goal> remaining, List<Message> transcript) {
        StringBuilder system = new StringBuilder(goalProperties.getJudge().strip());
        system.append("\n\n[아직 안 채워진 목표 — key: 이름. 채워진 것으로 보는 기준]");
        for (ChatGoalProperties.Goal goal : remaining) {
            system.append("\n- ").append(goal.getKey()).append(": ").append(goal.getName());
            if (goal.getFilledWhen() != null && !goal.getFilledWhen().isBlank()) {
                system.append(". ").append(goal.getFilledWhen().strip());
            }
        }
        StringBuilder user = new StringBuilder("[대화 — 시간순. (사연자)가 유저, (상담자)가 우리 쪽이다]");
        for (Message message : transcript) {
            user.append("\n\n").append(message.getRole() == MessageRole.USER ? "(사연자) " : "(상담자) ")
                    .append(message.getContent());
        }
        return List.of(ChatMessage.system(system.toString()), ChatMessage.user(user.toString()));
    }

    // 응답에서 남은 목표에 해당하고 근거가 있는 것만 저장한다. 근거 없는 채워짐은 받지 않는다 —
    // 성급한 판정이 상담자의 질문을 일찍 끊는 것을 막는 유일한 장치라서다.
    private List<StoryGoal> persistNewlyFilled(Long storyId, List<ChatGoalProperties.Goal> remaining, String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(LlmJson.salvage(json));
        } catch (Exception e) {
            // 본문은 사연이 들어 있는 개인정보라 길이만 남긴다.
            log.warn("목표 판정 JSON 파싱 실패 storyId={} (본문 길이 {}자)", storyId,
                    json == null ? 0 : json.length());
            return List.of();
        }
        List<StoryGoal> added = new ArrayList<>();
        for (JsonNode node : root.path("filled")) {
            String key = node.path("key").asText("").strip();
            String evidence = node.path("evidence").asText("").strip();
            boolean known = remaining.stream().anyMatch(g -> key.equals(g.getKey()));
            boolean duplicate = added.stream().anyMatch(g -> key.equals(g.getGoalKey()));
            if (!known || duplicate || evidence.isBlank() || key.length() > StoryGoal.KEY_MAX_LENGTH) {
                continue;
            }
            try {
                added.add(storyGoalRepository.save(StoryGoal.filled(storyId, key, evidence)));
            } catch (RuntimeException e) {
                // 재시도 턴이 겹쳐 같은 키가 두 번 들어오면 유니크에 걸린다 — 이미 채워진 것이니 넘긴다.
                log.warn("목표 저장 실패 storyId={} key={}", storyId, key, e);
            }
        }
        return added;
    }

    // Google 형식 스키마 — 남은 목표 중 이번 대화로 채워진 것과 그 근거 발화.
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "filled", Map.of(
                            "type", "ARRAY",
                            "items", Map.of(
                                    "type", "OBJECT",
                                    "properties", Map.of(
                                            "key", Map.of("type", "STRING"),
                                            "evidence", Map.of("type", "STRING")),
                                    "required", List.of("key", "evidence")))),
            "required", List.of("filled"));
}
