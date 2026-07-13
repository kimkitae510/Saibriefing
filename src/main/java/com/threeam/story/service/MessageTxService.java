package com.threeam.story.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatGoalProperties;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.ChatPersonaProperties;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.entity.ChatMeta;
import com.threeam.story.entity.ReplyThought;
import com.threeam.story.entity.FactSource;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryIntakeRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 메시지 전송의 DB 단계를 "짧은 트랜잭션"으로 분리한다.
// 느린 LLM 호출은 이 트랜잭션 밖(StoryService)에서 일어나므로 커넥션을 점유하지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageTxService {

    // 마크다운 강조(**), 제목(#) 기호. 결론 강조용으로 굵기를 허용해봤다가 걷었다(굵기 없이
    // 문장 분리로 충분했다) — 프롬프트만 빼면 모델이 흘린 **가 무작위로 렌더링되므로 저장 전에
    // 걷는다. 기록에 남으면 다음 턴 프롬프트에 실려 모델이 같은 형식을 이어가는 문제도 그대로다.
    private static final Pattern MARKDOWN_MARKS =
            Pattern.compile("\\*\\*|^#{1,6}\\s+", Pattern.MULTILINE);

    static String stripMarkdown(String reply) {
        return reply == null ? null : MARKDOWN_MARKS.matcher(reply).replaceAll("");
    }

    // 페르소나 실문구는 저장소 밖(persona.yml, gitignore)에서 주입된다. 코드에는 자리표시 기본값만 있다.
    private final ChatPersonaProperties personaProperties;
    private final ChatGoalProperties goalProperties;
    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final StoryFactRepository storyFactRepository;
    private final StoryIntakeRepository storyIntakeRepository;

    // tx1: 소유권 확인 + 유저 메시지 저장. 짧게 끝난다.
    // 저장한 유저 메시지(즉시 응답용)와 그 id(폴링 기준)를 돌려준다. 프롬프트는 여기서 안 만든다 —
    // 백그라운드 생성 단계가 목표 판정을 마친 뒤 DB에서 조립한다(재시도와 같은 경로).
    @Transactional
    public PreparedSend appendUserMessage(Long userId, Long storyId, String content) {
        Story story = storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        Message userMessage = messageRepository.save(Message.user(story, content));
        // 제목이 기본값이면 첫 메시지로 바꿔준다 — 목록이 "새 대화"만 줄지어 구분이 안 가는 문제.
        if (Story.DEFAULT_TITLE.equals(story.getTitle())) {
            story.rename(titleFrom(content));
        }
        return new PreparedSend(MessageResponse.from(userMessage), userMessage.getId());
    }

    // 백그라운드에서 부른다. 대화 전체를 시간순으로 — 목표 판정과 프롬프트 조립이 같은 것을 읽는다.
    @Transactional(readOnly = true)
    public List<Message> transcript(Long storyId) {
        return messageRepository.findByStoryIdOrderByIdAsc(storyId);
    }

    // 목표 판정이 끝난 뒤 백그라운드에서 부른다. 조회만 하지만 지연 로딩과 원장 조회가 있어 트랜잭션 안이다.
    @Transactional(readOnly = true)
    public List<ChatMessage> promptFor(Long storyId, GoalJudge.GoalState goals) {
        List<ChatMessage> prompt = buildPrompt(storyId, goals);
        dumpPrompt(storyId, prompt);
        return prompt;
    }

    // 실제로 보낸 프롬프트를 통째로 남긴다. "프롬프트가 제대로 들어갔나"를 토큰 수로 추리하지 않으려고.
    // 판독의 last-reading-packet.txt와 같은 자리, 매 턴 덮어쓴다. 실패해도 대화는 멈추지 않는다.
    private void dumpPrompt(Long storyId, List<ChatMessage> prompt) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("storyId=").append(storyId).append(" at ").append(java.time.LocalDateTime.now()).append('\n');
            for (ChatMessage message : prompt) {
                sb.append("\n===== ").append(message.role()).append(" =====\n").append(message.content()).append('\n');
            }
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("logs"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("logs", "last-chat-prompt.txt"), sb.toString());
        } catch (Exception e) {
            log.warn("채팅 프롬프트 덤프 실패: {}", e.toString());
        }
    }

    private String titleFrom(String content) {
        String oneLine = content.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 20 ? oneLine : oneLine.substring(0, 20) + "…";
    }

    // 즉시 반환할 유저 메시지 + 그 행의 id(폴링과 프롬프트 조립이 이 id로 이어진다).
    public record PreparedSend(MessageResponse userMessage, Long userMessageId) {}

    // tx1'(재시도): 폴백 말풍선을 걷어낸다. 같은 유저 메시지로 답만 다시 만든다.
    // 유저에게 다시 타이핑을 시키지 않으려는 것이므로 유저 메시지는 새로 저장하지 않는다 —
    // 새로 저장하면 같은 말이 두 벌 남고, 사실 추출도 그 중복을 훑는다.
    // 폴백은 지운다: 유저 발화가 아니라 우리가 대신 낸 안내라 대화 기록으로 남길 값이 없고,
    // 남겨두면 재시도가 성공해도 실패 말풍선이 답 위에 그대로 붙어 있다.
    // 지우는 것은 쿨다운 검사를 통과한 뒤다 — 먼저 지우면 거절당한 유저가 폴백 말풍선과
    // 재시도 버튼까지 잃고 같은 말을 다시 타이핑해야 한다.
    @Transactional
    public PreparedRetry prepareRetry(Long userId, Long storyId) {
        // 검사와 삭제 사이에 답이 붙거나 다른 요청이 먼저 지웠을 수 있어 여기서 한 번 더 본다.
        RetriableTurn turn = retriableTurn(userId, storyId);
        messageRepository.delete(turn.fallback());
        // 백그라운드 조립이 방금 지운 폴백을 다시 읽지 않게 먼저 밀어낸다.
        messageRepository.flush();
        Message userMessage = turn.userMessage();
        // 첫 말(상담자가 먼저 거는 턴)이 실패한 판은 앞에 유저 메시지가 없다 — 폴링 기준은 0.
        return userMessage == null
                ? new PreparedRetry(0L, null)
                : new PreparedRetry(userMessage.getId(), userMessage.getContent());
    }

    // 마지막이 폴백이고 그 앞이 유저 메시지일 때, 또는 폴백이 방의 유일한 메시지일 때(첫 말 실패)만
    // 재시도할 것이 있다. (재시도를 두 번 눌렀거나 그새 정상 답이 붙었으면 여기서 걸린다)
    private RetriableTurn retriableTurn(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        List<Message> recent = messageRepository
                .findByStoryIdOrderByIdDesc(storyId, PageRequest.of(0, 2))
                .getContent();
        if (recent.isEmpty() || !recent.get(0).isFallback()) {
            throw new BusinessException(ErrorCode.CHAT_RETRY_NOT_APPLICABLE);
        }
        if (recent.size() == 1) {
            return new RetriableTurn(null, recent.get(0));
        }
        if (recent.get(1).getRole() != MessageRole.USER) {
            throw new BusinessException(ErrorCode.CHAT_RETRY_NOT_APPLICABLE);
        }
        return new RetriableTurn(recent.get(1), recent.get(0));
    }

    private record RetriableTurn(Message userMessage, Message fallback) {}

    // 폴링 기준 id(되살릴 답이 붙을 자리)와 원문. 첫 말 재시도는 0과 null.
    public record PreparedRetry(Long pollAfterId, String userContent) {}

    // 첫 말 대본이 있으면 그대로 저장한다(LLM 없음). 대본이 비어 있으면 false — 호출부가 LLM으로 만든다.
    @Transactional
    public boolean appendScriptedOpening(Long storyId) {
        String script = personaProperties.getOpening();
        if (script == null || script.isBlank()) {
            return false;
        }
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        messageRepository.save(Message.assistant(story, script.strip()));
        story.touch();
        return true;
    }

    // 방에 메시지가 하나라도 있는지 — 첫 말을 만들지 말지의 기준.
    @Transactional(readOnly = true)
    public boolean hasAnyMessage(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        return !messageRepository.findByStoryIdOrderByIdDesc(storyId, PageRequest.of(0, 1))
                .getContent().isEmpty();
    }

    // tx2: LLM 응답을 어시스턴트 메시지로 저장 + 사연 활동시각 갱신.
    @Transactional
    public MessageResponse appendAssistantReply(Long storyId, String reply) {
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        // 옛 판의 내부 블록이 흘러나오면 읽어서 옮기고 본문에서는 뗀다(ChatMeta 주석).
        story.updateReunionDirection(ChatMeta.direction(reply));
        // 답 앞의 메모는 따로 떼어 둔다 — 화면에는 [답] 뒤만 나간다(ReplyThought 주석).
        ReplyThought.Split split = ReplyThought.split(ChatMeta.strip(reply));
        Message answer = Message.assistant(story, stripMarkdown(split.answer()), split.thought());
        messageRepository.save(answer);
        story.touch();
        return MessageResponse.from(answer);
    }

    private List<ChatMessage> buildPrompt(Long storyId, GoalJudge.GoalState goals) {
        List<ChatMessage> prompt = new ArrayList<>();
        // 페르소나가 프롬프트의 맨 앞이자 유일한 고정 지시 블록이다. 캐싱은 앞에서부터 똑같은
        // 만큼만 먹으므로 고정분은 여기 두고, 매번 바뀌는 것(문진, 목표, 대화)은 뒤에 둔다.
        prompt.add(ChatMessage.system(personaProperties.getPersona()));
        // 판독과 같은 기준을 상담자도 든다. 고정분이라 페르소나 바로 뒤(캐시 구간).
        String knowledge = loadKnowledge();
        if (knowledge != null) {
            prompt.add(ChatMessage.system(knowledge));
        }
        // 답의 모양은 규칙으로 못 가르치고 본 것으로 배운다. 예시는 상담자가 이미 한 턴으로 싣는다 —
        // 시스템 글로 넣으면 설명서로 읽고 틀만 베꼈다(실측). 고정분이라 페르소나 뒤(캐시 구간).
        List<ChatPersonaProperties.Example> examples = personaProperties.getExamples();
        if (examples != null) {
            boolean any = false;
            for (ChatPersonaProperties.Example example : examples) {
                if (!example.filled()) {
                    continue;
                }
                String intro = personaProperties.getExampleIntro();
                if (!any && intro != null && !intro.isBlank()) {
                    prompt.add(ChatMessage.system(intro.strip()));
                }
                prompt.add(ChatMessage.user(example.getUser().strip()));
                prompt.add(ChatMessage.assistant(example.getAssistant().strip()));
                // 왜 이 답인지를 답 바로 뒤에 붙인다. 모양만 보면 어미와 문형을 베끼고, 이유가 있어야 다른 사연에 옮긴다.
                String why = example.getWhy();
                if (why != null && !why.isBlank()) {
                    prompt.add(ChatMessage.system(why.strip()));
                }
                any = true;
            }
            String note = personaProperties.getExampleNote();
            if (any && note != null && !note.isBlank()) {
                prompt.add(ChatMessage.system(note.strip()));
            }
        }
        // 폼으로 받은 기본 정보. 이야기가 시작되기 전의 바탕이라 대화보다 앞이다. 구획 이름 없이
        // 줄만 싣는다 — 모델을 틀로 통제할수록 답이 틀을 닮아간다.
        storyIntakeRepository.findByStoryId(storyId)
                .map(StoryIntakeService::describe)
                .filter(block -> block != null && !block.isBlank())
                .ifPresent(block -> prompt.add(ChatMessage.system(block)));
        // 유저가 분석 화면에서 직접 적어준 사실만 싣는다. 추출된 사실은 안 싣는다 —
        // 대화 전체가 그대로 실리므로 그 요약본을 또 주면 같은 말이 두 번 읽힌다.
        List<String> userFacts = storyFactRepository.findByStoryIdOrderByIdAsc(storyId).stream()
                .filter(fact -> fact.getSource() == FactSource.USER)
                .map(StoryFact::getFact)
                .toList();
        if (!userFacts.isEmpty()) {
            prompt.add(ChatMessage.system(String.join("\n", userFacts)));
        }
        List<Message> transcript = messageRepository.findByStoryIdOrderByIdAsc(storyId);
        // 목표는 유저가 한 번이라도 말한 뒤부터 싣는다. 이야기가 하나도 없는 첫 턴에 물을 목록만 주면
        // 상담자가 첫 말부터 목록을 집어 묻고, 사연자는 하고 싶던 이야기를 꺼낼 자리를 잃는다(실측).
        long userTurns = transcript.stream().filter(m -> m.getRole() == MessageRole.USER).count();
        String goalBlock = userTurns > 0 ? goalBlock(goals, userTurns) : null;
        if (goalBlock != null) {
            prompt.add(ChatMessage.system(goalBlock));
        }
        // 대화 전체를 시간순으로, 역할 그대로. 창을 두지 않는다 — 대화는 시간 상한으로 짧게
        // 유지되고, 잘라 보내면 상담자가 앞에서 들은 것을 다시 묻는다.
        // 유저가 말하기 전의 상담자 메시지(첫 말 대본)는 뺀다. 화면엔 나가지만 모델이 그 문체와
        // "질문이 이어진다"는 선언을 자기 앞 턴으로 읽고 따라간다(실측) — 대본은 모델의 말이 아니다.
        boolean userStarted = false;
        for (Message message : transcript) {
            if (message.getRole() == MessageRole.USER) {
                userStarted = true;
            } else if (!userStarted) {
                continue;
            }
            prompt.add(message.getRole() == MessageRole.USER
                    ? ChatMessage.user(message.getContent())
                    : ChatMessage.assistant(message.getContent()));
        }
        // 출력 직전 점검은 반드시 대화 뒤, 프롬프트의 맨 끝이다 — 앞에 두면 페르소나 중간의
        // 규칙과 같은 자리가 되어 묻힌다. 여기가 마지막으로 읽히는 지시라는 게 이 블록의 전부다.
        String finalCheck = personaProperties.getFinalCheck();
        if (finalCheck != null && !finalCheck.isBlank()) {
            prompt.add(ChatMessage.system(finalCheck));
        }
        return prompt;
    }

    private String cappedClosing(long userTurns) {
        int cap = goalProperties == null ? 0 : goalProperties.getMaxUserTurns();
        if (cap <= 0 || userTurns < cap) {
            return null;
        }
        String closing = personaProperties.getClosing();
        return closing == null || closing.isBlank() ? null : closing.strip();
    }

    // knowledge-file이 비면 null. 파일을 못 읽어도 대화는 멈추지 않는다 — 경고만 남기고 기준 없이 간다.
    private String loadKnowledge() {
        String file = personaProperties.getKnowledgeFile();
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(file));
            return text.isBlank() ? null : text.strip();
        } catch (Exception e) {
            log.warn("채팅 기준 파일을 읽지 못해 기준 없이 진행: {} — {}", file, e.toString());
            return null;
        }
    }

    // 아직 안 나온 목표를 전부 싣는다. 무엇을 물을지는 모델이 대화를 보고 고른다 — 코드가 하나를 찍어 주면
    // 그걸 그대로 베껴 묻고(실측), 돌려 주면 이미 답한 축이 다시 온다(실측). 각 줄은 "이름: 뭐가 나와야 하는지"이고
    // 묻는 문장은 안 준다. yml의 goal-guide가 맨 앞, 다 찼거나 상한에 닿았으면 closing만 나간다.
    private String goalBlock(GoalJudge.GoalState goals, long userTurns) {
        if (goals == null || goals.total() == 0) {
            return cappedClosing(userTurns);
        }
        if (goals.closing()) {
            String closing = personaProperties.getClosing();
            return closing == null || closing.isBlank() ? null : closing.strip();
        }
        StringBuilder block = new StringBuilder();
        String guide = personaProperties.getGoalGuide();
        if (guide != null && !guide.isBlank()) {
            block.append(guide.strip());
        }
        for (ChatGoalProperties.Goal goal : goals.remaining()) {
            if (block.length() > 0) {
                block.append("\n");
            }
            block.append(hintLine(goal));
        }
        return block.toString();
    }

    private static String hintLine(ChatGoalProperties.Goal goal) {
        if (goal.getAsk() != null && !goal.getAsk().isBlank()) {
            return goal.getAsk().strip();
        }
        if (goal.getFilledWhen() != null && !goal.getFilledWhen().isBlank()) {
            return goal.getName() + ": " + goal.getFilledWhen().strip();
        }
        return goal.getName();
    }
}
