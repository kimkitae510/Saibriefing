package com.threeam.story.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.global.exception.custom.RetryAfterException;
import com.threeam.story.dto.MessagePageResponse;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.dto.MessageRetryResponse;
import com.threeam.story.dto.MessageSendRequest;
import com.threeam.story.dto.StoryCreateRequest;
import com.threeam.story.dto.StoryResponse;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.usage.ChatRetryGuard;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoryService {

    // 조회 한 번에 내려줄 메시지 수 상한. 클라가 큰 size를 넘겨도 여기서 자른다.
    private static final int MAX_PAGE_SIZE = 100;

    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;
    private final MessageTxService messageTxService;
    private final StoryFactExtractor factExtractor;
    // 답변을 고치지는 않고, 글자로 판정되는 규칙 위반만 세어 남긴다(ReplyLinter 주석 참고).
    private final ReplyLinter replyLinter;
    private final ChatLlm chatLlm;
    private final GoalJudge goalJudge;
    private final UsageLimiter usageLimiter;
    private final ChatRetryGuard chatRetryGuard;
    // 답변 저장, 원장 적재를 HttpClient 스레드가 아니라 우리 풀에서 돌린다(LlmCallbackConfig 참고).
    private final Executor llmCallbackExecutor;

    @Transactional
    public StoryResponse create(Long userId, StoryCreateRequest request) {
        String title = (request.getTitle() == null || request.getTitle().isBlank())
                ? Story.DEFAULT_TITLE
                : request.getTitle().trim();

        Story story = storyRepository.save(Story.builder().userId(userId).title(title).build());

        return StoryResponse.from(story);
    }

    public List<StoryResponse> getStories(Long userId) {
        List<Story> stories = storyRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(userId);
        if (stories.isEmpty()) {
            return List.of();
        }
        // 카톡식 목록 미리보기 — 사연별 마지막 메시지를 한 방 쿼리로 가져와 붙인다(N+1 회피).
        Map<Long, String> previews = messageRepository
                .findLatestPerStory(stories.stream().map(Story::getId).toList()).stream()
                .collect(Collectors.toMap(m -> m.getStory().getId(), m -> preview(m.getContent())));
        return stories.stream()
                .map(story -> StoryResponse.from(story, previews.get(story.getId())))
                .toList();
    }

    // 목록 한 줄용 — 개행은 공백으로 펴고 길면 자른다(전문은 방 안에서 보이므로 잘림은 무해).
    private static final int PREVIEW_LENGTH = 60;

    private String preview(String content) {
        String oneLine = content.replace('\n', ' ').strip();
        return oneLine.length() > PREVIEW_LENGTH ? oneLine.substring(0, PREVIEW_LENGTH) : oneLine;
    }

    // 폴링 방식: 유저 메시지를 저장하고 즉시 반환한다. 어시스턴트 답은 백그라운드에서 생성, 저장되고,
    // 클라이언트는 GET .../messages/since?after=<유저메시지id>로 폴링해 답이 붙는지 확인한다.
    // 트랜잭션 밖(NOT_SUPPORTED)에서 오케스트레이션 — 느린 LLM 호출이 DB 커넥션을 잡지 않게.
    // 대화 회수는 세지 않는다 — 탐색 채팅은 리포트의 입력이라 턴마다 팔 물건이 아니고, 회수로 막으면
    // 목표가 차기 전에 대화가 끊긴다. 남는 방어는 생성 락(연타)과 연속 실패 쿨다운(무한 무료 호출)뿐이다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MessageResponse sendMessage(Long userId, Long storyId, MessageSendRequest request) {
        // 이 유저가 이미 대화 답변을 생성 중이면 접수를 거부한다(연타, 중복요청, 동시 발사 차단).
        usageLimiter.acquireInFlight(UsageKind.CHAT, userId);
        try {
            int retryAfterSeconds = chatRetryGuard.blockedSeconds(userId);
            if (retryAfterSeconds > 0) {
                // 유저 메시지도 저장하지 않는다 — 답이 붙지 않을 말풍선만 남기고 폴링이 헛돈다.
                throw new RetryAfterException(ErrorCode.CHAT_RETRY_COOLDOWN, retryAfterSeconds);
            }

            MessageTxService.PreparedSend prepared = messageTxService.appendUserMessage(
                    userId, storyId, request.getContent());

            generateInBackground(userId, storyId);

            return prepared.userMessage();
        } catch (RuntimeException e) {
            usageLimiter.releaseInFlight(UsageKind.CHAT, userId);
            throw e;
        }
    }

    // 대화가 없는 방에서 상담자가 먼저 말을 건다. 문진을 읽고 첫 질문을 만드는 LLM 턴이라 전송과
    // 같은 락과 쿨다운을 탄다. 메시지가 이미 있으면 아무것도 안 한다(새로고침, 두 번 호출에 안전).
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void openConversation(Long userId, Long storyId) {
        if (messageTxService.hasAnyMessage(userId, storyId)) {
            return;
        }
        usageLimiter.acquireInFlight(UsageKind.CHAT, userId);
        try {
            int retryAfterSeconds = chatRetryGuard.blockedSeconds(userId);
            if (retryAfterSeconds > 0) {
                throw new RetryAfterException(ErrorCode.CHAT_RETRY_COOLDOWN, retryAfterSeconds);
            }
            generateInBackground(userId, storyId);
        } catch (RuntimeException e) {
            usageLimiter.releaseInFlight(UsageKind.CHAT, userId);
            throw e;
        }
    }

    // 답을 못 받은 턴(폴백 말풍선)을 유저가 다시 시도한다. 같은 말을 다시 타이핑시키지 않으려는 것이라
    // 유저 메시지는 그대로 두고 답만 새로 만든다. 생성 락, 연속 실패 가드는 전송과 똑같이 태운다 —
    // 재시도도 실제 호출 비용이라 여기가 빠지면 가드를 우회하는 문이 된다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MessageRetryResponse retryLastReply(Long userId, Long storyId) {
        usageLimiter.acquireInFlight(UsageKind.CHAT, userId);
        try {
            int retryAfterSeconds = chatRetryGuard.blockedSeconds(userId);
            if (retryAfterSeconds > 0) {
                throw new RetryAfterException(ErrorCode.CHAT_RETRY_COOLDOWN, retryAfterSeconds);
            }

            // 폴백 삭제는 검사를 다 통과한 뒤다 — 거절당한 유저에게서 재시도 버튼을 뺏지 않는다.
            MessageTxService.PreparedRetry prepared = messageTxService.prepareRetry(userId, storyId);

            generateInBackground(userId, storyId);

            return new MessageRetryResponse(prepared.pollAfterId());
        } catch (RuntimeException e) {
            usageLimiter.releaseInFlight(UsageKind.CHAT, userId);
            throw e;
        }
    }

    // fire-and-forget: 응답을 기다리지 않는다. 목표 판정 → 프롬프트 조립 → 답변 순으로 돌고,
    // 완료되면 어시스턴트 메시지로 저장, 실패하면 폴백 저장.
    // 판정이 답변보다 먼저인 이유: 방금 들어온 말이 무엇을 채웠는지 알아야 같은 것을 다시 안 묻는다.
    // handle로 LLM 단계 예외와 저장 단계 예외를 분리한다(저장 실패를 'LLM 실패'로 오인 기록하지 않게).
    private void generateInBackground(Long userId, Long storyId) {
        CompletableFuture.completedFuture(null)
                .thenComposeAsync(ignored -> {
                    List<Message> transcript = messageTxService.transcript(storyId);
                    long userTurns = transcript.stream()
                            .filter(m -> m.getRole() == MessageRole.USER).count();
                    return goalJudge.judge(storyId, transcript, userTurns)
                            .thenCompose(goals -> chatLlm.reply(messageTxService.promptFor(storyId, goals)));
                }, llmCallbackExecutor)
                .handleAsync((reply, ex) -> {
                    if (ex != null) {
                        log.error("LLM 응답 생성 실패 storyId={} userId={}", storyId, userId, ex);
                        persistFallbackQuietly(storyId);
                        markChatFailedQuietly(userId);
                    } else {
                        persistReplyQuietly(userId, storyId, reply);
                    }
                    return null;
                }, llmCallbackExecutor)
                .whenComplete((ignored, ex) -> {
                    // handle에서 예외를 삼키므로 여기 ex는 보통 null이지만, 만일을 대비해 흔적을 남긴다(무로그 방지).
                    if (ex != null) {
                        log.error("메시지 처리 파이프라인 예상외 실패 storyId={} userId={}", storyId, userId, ex);
                    }
                    usageLimiter.releaseInFlight(UsageKind.CHAT, userId);
                });
    }

    // LLM 성공 후: 답변 저장 → 사실 추출. 저장 단계 실패는 'LLM 실패'와 구분해 명확히 남긴다.
    private void persistReplyQuietly(Long userId, Long storyId, String reply) {
        try {
            messageTxService.appendAssistantReply(storyId, reply);
        } catch (RuntimeException e) {
            // LLM은 성공했으나 답을 못 남긴 상태 — 폴링이 끝나지 않는 CS 원인이 되므로 반드시 추적 가능해야 한다.
            log.error("LLM 응답은 받았으나 답변 저장 실패 storyId={} userId={}", storyId, userId, e);
            // 유저에게 닿은 게 없으니 가드에는 실패로 센다 — 호출 비용은 이미 나갔다.
            markChatFailedQuietly(userId);
            return;
        }
        clearChatFailedQuietly(userId);      // 답이 붙었으니 연속이 끊겼다.
        replyLinter.inspect(storyId, reply); // 규칙 위반 계측. 답변은 그대로 나간다.
        factExtractor.extractAsync(storyId); // 원장 갱신. 실패해도 채팅에 영향 없음(내부에서 삼킴).
    }

    // LLM 실패 시 폴백 저장. 이마저 실패하면 답도 폴백도 없이 조용히 사라지므로 반드시 로그를 남긴다.
    // 문구는 Message가 들고 있다 — 화면의 재시도 버튼과 서버의 재시도 검사가 같은 기준을 봐야 한다.
    private void persistFallbackQuietly(Long storyId) {
        try {
            messageTxService.appendAssistantReply(storyId, Message.FALLBACK_CONTENT);
        } catch (RuntimeException e) {
            log.error("폴백 메시지 저장까지 실패 storyId={} — 유저 폴링이 종료되지 않는다", storyId, e);
        }
    }

    // 가드 기록 실패가 답변 저장이나 락 해제를 막지 않게 격리한다 — 가드는 비용 방어 장치라,
    // 한 번 못 세는 것보다 채팅 흐름이 끊기는 쪽이 나쁘다.
    private void markChatFailedQuietly(Long userId) {
        try {
            chatRetryGuard.markFailed(userId);
        } catch (RuntimeException e) {
            log.error("채팅 실패 카운트 기록 실패 userId={}", userId, e);
        }
    }

    private void clearChatFailedQuietly(Long userId) {
        try {
            chatRetryGuard.clear(userId);
        } catch (RuntimeException e) {
            log.error("채팅 실패 카운트 해제 실패 userId={}", userId, e);
        }
    }

    // 답도 폴백도 없이 유저 메시지만 남은 턴을 폴백으로 닫는다.
    // 서버가 재시작되면 진행 중이던 LLM 호출은 흔적 없이 사라지는데(fire-and-forget이라 되살릴
    // 것도 없다), 그 자리는 화면에서 영영 끝나지 않는 "..."로 남는다. 여기서 닫아야 폴링이 끝나고
    // 재시도 버튼이 나온다. 판정 근거는 생성 락이다 — 콜백은 성공이든 실패든 저장을 마친 뒤에
    // 락을 풀므로, 락이 없는데 답이 없으면 그 턴은 되살아날 길이 없다.
    // 연속 실패로는 세지 않는다: 우리 재시작이지 LLM이 반복해서 실패한 게 아니다.
    private void healDanglingTurn(Long userId, Long storyId) {
        if (usageLimiter.isGenerating(UsageKind.CHAT, userId)) {
            return;
        }
        List<Message> last = messageRepository
                .findByStoryIdOrderByIdDesc(storyId, PageRequest.of(0, 1)).getContent();
        if (last.isEmpty() || last.get(0).getRole() != MessageRole.USER) {
            return;
        }
        log.warn("답 없이 끊긴 턴을 폴백으로 닫음 storyId={} userId={} messageId={}",
                storyId, userId, last.get(0).getId());
        messageTxService.appendAssistantReply(storyId, Message.FALLBACK_CONTENT);
    }

    // 폴링: 방금 보낸 메시지(afterId) 이후 새로 생긴 메시지(주로 어시스턴트 답)를 시간순으로 반환.
    @Transactional
    public List<MessageResponse> getMessagesSince(Long userId, Long storyId, Long afterId) {
        Story story = findOwned(storyId, userId);
        // 아직 아무것도 안 붙었으면, 만드는 중인지 끊긴 턴인지 가른다(끊겼으면 폴백을 채워 폴링을 끝낸다).
        if (!messageRepository.existsByStoryIdAndIdGreaterThan(storyId, afterId)) {
            healDanglingTurn(userId, storyId);
        }
        List<MessageResponse> fresh = withGoals(storyId, messageRepository
                .findByStoryIdAndIdGreaterThanOrderByIdAsc(storyId, afterId)
                .stream().map(MessageResponse::from).toList());
        // 답을 화면에서 받아봤으니 읽음 처리 — 목록 안읽음 배지의 기준 시각.
        if (!fresh.isEmpty()) {
            story.markRead();
        }
        return fresh;
    }

    @Transactional
    public MessagePageResponse getMessages(Long userId, Long storyId, Long cursor, int size) {
        Story story = findOwned(storyId, userId);
        // 방에 들어와 최신 페이지를 본 시점(cursor 없음)이 곧 읽음이다. 과거 페이징은 해당 없음.
        if (cursor == null) {
            story.markRead();
            // 조회 전에 닫는다 — 이 페이지에 폴백이 실려야 화면이 "..." 대신 재시도 버튼을 그린다.
            healDanglingTurn(userId, storyId);
        }

        int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest page = PageRequest.of(0, limit);
        Slice<Message> slice = (cursor == null)
                ? messageRepository.findByStoryIdOrderByIdDesc(storyId, page)
                : messageRepository.findByStoryIdAndIdLessThanOrderByIdDesc(storyId, cursor, page);

        // 조회는 최신→과거(id desc)로 오지만, 화면 표시용으로 과거→현재로 뒤집는다.
        List<Message> content = slice.getContent();
        List<Message> ordered = new ArrayList<>();
        for (int i = content.size() - 1; i >= 0; i--) {
            ordered.add(content.get(i));
        }
        List<MessageResponse> messages = withGoals(storyId,
                ordered.stream().map(MessageResponse::from).toList());
        // 다음 커서 = 이번 배치에서 가장 오래된(가장 작은) id. 클라는 이보다 과거를 이어서 요청한다.
        Long nextCursor = content.isEmpty() ? null : content.get(content.size() - 1).getId();

        return new MessagePageResponse(messages, nextCursor, slice.hasNext());
    }

    // 상담자 답에 목표 진행(채워진 수 / 전체)을 붙인다. 지금 상태 하나를 모든 답에 같은 값으로 —
    // 화면이 보는 건 마지막 답뿐이고, 답마다 그때의 값을 남기려면 컬럼이 하나 더 필요한데 그럴 값이 없다.
    private List<MessageResponse> withGoals(Long storyId, List<MessageResponse> messages) {
        if (messages.stream().noneMatch(m -> m.getRole() != MessageRole.USER)) {
            return messages;
        }
        GoalJudge.GoalState goals = goalJudge.current(storyId, 0);
        if (goals.total() == 0) {
            return messages;
        }
        for (MessageResponse message : messages) {
            if (message.getRole() != MessageRole.USER) {
                message.withGoals(goals.filled().size(), goals.total());
            }
        }
        return messages;
    }

    // 소프트 딜리트: 대화, 기억, 분석은 남길 기록이라 물리 삭제하지 않고 사연에 삭제 시각만 찍는다.
    @Transactional
    public void deleteStory(Long userId, Long storyId) {
        Story story = findOwned(storyId, userId);
        story.softDelete();
    }

    private Story findOwned(Long storyId, Long userId) {
        return storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
    }
}
