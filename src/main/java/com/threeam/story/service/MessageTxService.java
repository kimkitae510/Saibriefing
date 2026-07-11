package com.threeam.story.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatGoalProperties;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.ChatPersonaProperties;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.entity.ChatMeta;
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
        return buildPrompt(storyId, goals);
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
        Message answer = Message.assistant(story, stripMarkdown(ChatMeta.strip(reply)));
        messageRepository.save(answer);
        story.touch();
        return MessageResponse.from(answer);
    }

    private List<ChatMessage> buildPrompt(Long storyId, GoalJudge.GoalState goals) {
        List<ChatMessage> prompt = new ArrayList<>();
        // 페르소나가 프롬프트의 맨 앞이자 유일한 고정 지시 블록이다. 캐싱은 앞에서부터 똑같은
        // 만큼만 먹으므로 고정분은 여기 두고, 매번 바뀌는 것(문진, 목표, 대화)은 뒤에 둔다.
        prompt.add(ChatMessage.system(personaProperties.getPersona()));
        // 폼으로 받은 기본 정보. 이야기가 시작되기 전의 바탕이라 대화보다 앞이다.
        // 머리말을 붙인다 — 라벨 없는 줄 몇 개는 대화 속 "오늘" 같은 표현에 밀려 시점을 잃는다(실측:
        // 경과 1개월을 받고도 "오늘 일어난 일"로 답함). 판독 packet과 같은 머리말이다.
        storyIntakeRepository.findByStoryId(storyId)
                .map(StoryIntakeService::describe)
                .filter(block -> block != null && !block.isBlank())
                .ifPresent(block -> prompt.add(ChatMessage.system(
                        "[문진으로 확인된 것 — 대화보다 먼저 받은 답이라 시점과 사실의 기준이다]\n" + block)));
        // 유저가 분석 화면에서 직접 적어준 사실만 싣는다. 추출된 사실은 안 싣는다 —
        // 대화 전체가 그대로 실리므로 그 요약본을 또 주면 같은 말이 두 번 읽힌다.
        List<StoryFact> userFacts = storyFactRepository.findByStoryIdOrderByIdAsc(storyId).stream()
                .filter(fact -> fact.getSource() == FactSource.USER)
                .toList();
        if (!userFacts.isEmpty()) {
            StringBuilder block = new StringBuilder("유저가 분석 화면에서 직접 적어준 사실:");
            for (StoryFact fact : userFacts) {
                block.append("\n- ").append(fact.getFact());
            }
            prompt.add(ChatMessage.system(block.toString()));
        }
        String goalBlock = goalBlock(goals);
        if (goalBlock != null) {
            prompt.add(ChatMessage.system(goalBlock));
        }
        // 대화 전체를 시간순으로, 역할 그대로. 창을 두지 않는다 — 대화는 시간 상한으로 짧게
        // 유지되고, 잘라 보내면 상담자가 앞에서 들은 것을 다시 묻는다.
        List<Message> transcript = messageRepository.findByStoryIdOrderByIdAsc(storyId);
        for (Message message : transcript) {
            prompt.add(message.getRole() == MessageRole.USER
                    ? ChatMessage.user(message.getContent())
                    : ChatMessage.assistant(message.getContent()));
        }
        // 대화가 없는 방 — 상담자가 먼저 말을 거는 첫 턴. 지시를 user 턴으로 싣는다(속성 주석 참고).
        if (transcript.isEmpty()) {
            prompt.add(ChatMessage.user("(유저가 보낸 말이 아니라 시스템 지시다. 이 지시 자체에 답하지 말고"
                    + " 첫 말을 건네라.)\n" + personaProperties.getOpening()));
        }
        // 출력 직전 점검은 반드시 대화 뒤, 프롬프트의 맨 끝이다 — 앞에 두면 페르소나 중간의
        // 규칙과 같은 자리가 되어 묻힌다. 여기가 마지막으로 읽히는 지시라는 게 이 블록의 전부다.
        String finalCheck = personaProperties.getFinalCheck();
        if (finalCheck != null && !finalCheck.isBlank()) {
            prompt.add(ChatMessage.system(finalCheck));
        }
        return prompt;
    }

    // 목표 현황 블록. 코드는 데이터(채워진 것과 근거, 남은 것과 묻는 꼴)만 만들고, 그걸 어떻게
    // 쓸지는 로컬 yml(goal-guide, closing)이 말한다. 목표가 꺼져 있으면 블록 자체가 없다.
    private String goalBlock(GoalJudge.GoalState goals) {
        if (goals == null || goals.total() == 0) {
            return null;
        }
        StringBuilder block = new StringBuilder("[탐색 목표]");
        if (goals.filled().isEmpty()) {
            block.append("\n채워진 것: 아직 없음");
        } else {
            block.append("\n채워진 것(근거는 유저가 한 말):");
            for (GoalJudge.FilledGoal filled : goals.filled()) {
                block.append("\n- ").append(filled.name()).append(" — ").append(filled.evidence());
            }
        }
        if (goals.remaining().isEmpty()) {
            block.append("\n남은 것: 없음");
        } else {
            block.append("\n남은 것(위가 우선):");
            for (ChatGoalProperties.Goal goal : goals.remaining()) {
                block.append("\n- ").append(goal.getName());
                if (goal.getAsk() != null && !goal.getAsk().isBlank()) {
                    block.append(" — ").append(goal.getAsk().strip());
                }
            }
        }
        String guide = goals.closing() ? personaProperties.getClosing() : personaProperties.getGoalGuide();
        if (guide != null && !guide.isBlank()) {
            block.append("\n\n").append(guide.strip());
        }
        return block.toString();
    }
}
