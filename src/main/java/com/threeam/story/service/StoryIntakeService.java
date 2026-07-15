package com.threeam.story.service;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.story.dto.StoryIntakeRequest;
import com.threeam.story.dto.StoryIntakeResponse;
import com.threeam.story.entity.ContactPoint;
import com.threeam.story.entity.PartnerAction;
import com.threeam.story.entity.PriorReunionGap;
import com.threeam.story.entity.RelationState;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryIntake;
import com.threeam.story.repository.StoryIntakeRepository;
import com.threeam.story.repository.StoryRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 첫 대화 전에 받는 기본 정보의 읽기, 쓰기, 그리고 프롬프트용 조립을 맡는다.
@Service
@RequiredArgsConstructor
public class StoryIntakeService {

    private final StoryIntakeRepository intakeRepository;
    private final StoryRepository storyRepository;

    // "기타" 직접 입력을 받는 칸. 화면의 칸 이름과 같다. 모르는 키는 버린다.
    static final Set<String> OTHER_FIELDS = Set.of("priorReunion", "repeatBreakupPattern",
            "repeatSeverity", "priorReunionPath", "priorReunionGap",
            "initiator", "selfEndReason", "contactMode", "lastActionBeforeCut", "clingReaction",
            "preBreakupChange", "partnerHasNew", "newRelationOverlap", "partnerActions",
            "relationState", "pausePlan", "pauseContactRule");
    static final int OTHER_MAX_LENGTH = 200;

    @Transactional(readOnly = true)
    public StoryIntakeResponse get(Long userId, Long storyId) {
        requireOwned(userId, storyId);
        return intakeRepository.findByStoryId(storyId)
                .map(StoryIntakeResponse::of)
                .orElseGet(() -> prefill(userId, storyId));
    }

    @Transactional
    public StoryIntakeResponse save(Long userId, Long storyId, StoryIntakeRequest request) {
        requireOwned(userId, storyId);
        StoryIntake intake = intakeRepository.findByStoryId(storyId).orElse(null);
        if (intake == null) {
            intake = intakeRepository.save(StoryIntake.builder()
                    .storyId(storyId)
                    .userAge(request.userAge())
                    .partnerAge(request.partnerAge())
                    .userGender(request.userGender())
                    .datingMonths(request.datingMonths())
                    .daysSinceBreakup(request.daysSinceBreakup())
                    .initiator(request.initiator())
                    .contactMode(request.contactMode())
                    .priorReunion(request.priorReunion())
                    .partnerHasNew(request.partnerHasNew())
                    .preBreakupChange(request.preBreakupChange())
                    .branches(request.branches())
                    .partnerActions(exclusiveActions(request.partnerActions()))
                    .contactPoints(exclusive(request.contactPoints(), ContactPoint.NONE))
                    .otherAnswers(sanitizeOthers(request.otherAnswers()))
                    .relationState(request.relationState())
                    .build());
        } else {
            intake.update(request.userAge(), request.partnerAge(), request.userGender(),
                    request.datingMonths(), request.daysSinceBreakup(), request.initiator(),
                    request.contactMode(), request.priorReunion(), request.partnerHasNew(),
                    request.preBreakupChange(), request.branches(),
                    exclusiveActions(request.partnerActions()),
                    exclusive(request.contactPoints(), ContactPoint.NONE),
                    sanitizeOthers(request.otherAnswers()), request.relationState());
        }
        return StoryIntakeResponse.of(intake);
    }

    @Transactional(readOnly = true)
    public Optional<StoryIntake> find(Long storyId) {
        return intakeRepository.findByStoryId(storyId);
    }

    // 프롬프트에 싣는 블록. 지시 문구는 넣지 않는다 — 코드는 데이터만 조립하고,
    // 이 재료를 어떻게 쓰라는 말은 프롬프트 자산(persona, rubric)에 둔다.
    public static String describe(StoryIntake intake) {
        if (intake == null) {
            return null;
        }
        StringBuilder block = new StringBuilder();
        Map<String, String> others = intake.otherAnswerMap();
        line(block, "유저", person(intake.getUserAge(),
                intake.getUserGender() == null ? null : intake.getUserGender().caseVocabulary()));
        line(block, "상대", person(intake.getPartnerAge(), null));
        line(block, "교제 기간", duration(intake.getDatingMonths()));
        // 시간을 갖는 중이면 같은 칸이 다른 질문의 답이다(경과, 먼저 원한 쪽, 붙잡음, 상대 행동).
        // 화면이 물은 대로 이름을 붙여야 모델이 "이별 후"를 없는 이별로 읽지 않는다.
        boolean onBreak = intake.getRelationState() == RelationState.ON_BREAK;
        line(block, "관계 상태", choice(intake.getRelationState(),
                intake.getRelationState() == null ? null : intake.getRelationState().label(),
                others, "relationState"));
        // 입력값은 첫 입력 시점의 것이라 그대로 실으면 두 달 뒤에도 "12일 전"이다.
        // 경과일은 그때 이후 흐른 날을 더해 현재 시점으로 보정한다.
        line(block, onBreak ? "시간을 갖기 시작한 뒤 경과"
                : intake.getRelationState() == RelationState.UNCLEAR ? "관계가 이렇게 된 뒤 경과"
                : "이별 후 경과", elapsedSinceBreakup(intake));
        line(block, "시간을 갖기로 정한 기간", choice(intake.getPausePlan(),
                intake.getPausePlan() == null ? null : intake.getPausePlan().label(),
                others, "pausePlan"));
        line(block, "시간을 갖는 동안 연락 규칙", choice(intake.getPauseContactRule(),
                intake.getPauseContactRule() == null ? null : intake.getPauseContactRule().label(),
                others, "pauseContactRule"));
        line(block, onBreak ? "먼저 시간을 갖자고 한 쪽" : "먼저 이별을 원한 쪽", choice(intake.getInitiator(),
                intake.getInitiator() == null ? null : intake.getInitiator().label(), others, "initiator"));
        line(block, "이 상대와 재회 경험", choice(intake.getPriorReunion(),
                intake.getPriorReunion() == null ? null : intake.getPriorReunion().label(),
                others, "priorReunion"));
        line(block, "전에 헤어졌을 때와 이번 이별의 이유", choice(intake.getRepeatBreakupPattern(),
                intake.getRepeatBreakupPattern() == null ? null : intake.getRepeatBreakupPattern().label(),
                others, "repeatBreakupPattern"));
        line(block, "이번 이별의 무게(지난번과 비교)", choice(intake.getRepeatSeverity(),
                intake.getRepeatSeverity() == null ? null : intake.getRepeatSeverity().label(),
                others, "repeatSeverity"));
        line(block, "지난번에 다시 만나게 된 경로", choice(intake.getPriorReunionPath(),
                intake.getPriorReunionPath() == null ? null : intake.getPriorReunionPath().label(),
                others, "priorReunionPath"));
        line(block, "지난번 재회까지 걸린 기간", choice(intake.getPriorReunionGap(),
                intake.getPriorReunionGap() == null ? null : intake.getPriorReunionGap().label(),
                others, "priorReunionGap"));
        // 지금 경과가 지난번 구간의 상한을 넘었는지는 두 숫자의 비교라 코드가 한다.
        // 모델에 맡기면 두 줄을 따로 읽고 지나간다. 판단이 아니라 사실 한 줄이다.
        line(block, "지난번 재회 기간과 비교", gapComparison(intake));
        line(block, "이별 직전 관계 변화", choice(intake.getPreBreakupChange(),
                intake.getPreBreakupChange() == null ? null : intake.getPreBreakupChange().label(),
                others, "preBreakupChange"));
        line(block, onBreak ? "유저가 먼저 시간을 갖자고 한 이유" : "유저가 먼저 헤어지자고 한 이유",
                choice(intake.getSelfEndReason(),
                intake.getSelfEndReason() == null ? null : intake.getSelfEndReason().label(),
                others, "selfEndReason"));
        line(block, onBreak ? "시간을 갖자고 한 뒤 유저가 붙잡았는지와 상대 반응"
                : "헤어진 뒤 유저가 붙잡았는지와 상대 반응", choice(intake.getClingReaction(),
                intake.getClingReaction() == null ? null : intake.getClingReaction().label(),
                others, "clingReaction"));
        line(block, "차단이나 읽씹 직전 유저의 마지막 행동", choice(intake.getLastActionBeforeCut(),
                intake.getLastActionBeforeCut() == null ? null : intake.getLastActionBeforeCut().label(),
                others, "lastActionBeforeCut"));
        line(block, "앞으로 마주칠 접점", labels(intake.contactPointList().stream()
                .map(ContactPoint::label).toList()));
        // 아래 셋은 시간이 지나면 바뀌는 값이다. 첫 입력 시점임을 밝혀야 이후 대화에서
        // 드러난 최신 상황(사실 원장)과 어긋날 때 원장이 이긴다는 게 읽힌다.
        line(block, "연락 상황(첫 입력 시점)", choice(intake.getContactMode(),
                intake.getContactMode() == null ? null : intake.getContactMode().label(),
                others, "contactMode"));
        line(block, "상대에게 새 사람(첫 입력 시점)", choice(intake.getPartnerHasNew(),
                intake.getPartnerHasNew() == null ? null : intake.getPartnerHasNew().label(),
                others, "partnerHasNew"));
        line(block, "상대의 새 사람이 생긴 시점", choice(intake.getNewRelationOverlap(),
                intake.getNewRelationOverlap() == null ? null : intake.getNewRelationOverlap().label(),
                others, "newRelationOverlap"));
        line(block, onBreak ? "시간을 갖기 시작한 뒤 상대가 먼저 한 행동(첫 입력 시점)"
                        : "이별 후 상대가 먼저 한 행동(첫 입력 시점)",
                labels(intake.partnerActionList().stream()
                        .map(action -> action == PartnerAction.OTHER
                                ? otherText(others, "partnerActions", action.label())
                                : action.label())
                        .toList()));
        if (block.isEmpty()) {
            return null;
        }
        return "유저가 직접 입력한 기본 정보:" + block;
    }

    // 지금 경과가 지난번 재회까지 걸린 구간의 상한을 넘었으면 그 사실을. 안 넘었으면 아직
    // 그 안이라는 것을. 상한이 없는 값(더 오래, 모름, 기타)이나 경과 미상이면 말하지 않는다.
    static String gapComparison(StoryIntake intake) {
        PriorReunionGap gap = intake.getPriorReunionGap();
        Integer days = elapsedDays(intake);
        if (gap == null || gap.maxDays() == null || days == null) {
            return null;
        }
        return days > gap.maxDays()
                ? "지금 경과가 지난번 재회까지 걸린 기간을 이미 넘었음"
                : "지금 경과는 아직 지난번 재회까지 걸린 기간 안";
    }

    // 첫 입력 시점의 경과일에 그 뒤로 흐른 날을 더한다.
    public static Integer elapsedDays(StoryIntake intake) {
        if (intake == null || intake.getDaysSinceBreakup() == null) {
            return null;
        }
        // 방금 만들어 아직 flush 전이면 createdAt이 비어 있다 — 보정할 시간이 없다는 뜻이다.
        if (intake.getCreatedAt() == null) {
            return intake.getDaysSinceBreakup();
        }
        long since = ChronoUnit.DAYS.between(intake.getCreatedAt().toLocalDate(), LocalDate.now());
        return intake.getDaysSinceBreakup() + (int) Math.max(0, since);
    }

    // 프로필은 개월 단위다. 한 달이 안 됐으면 0 — 버림이라 "3주째"가 1개월로 부풀지 않는다.
    public static Integer elapsedMonths(StoryIntake intake) {
        Integer days = elapsedDays(intake);
        return days == null ? null : days / 30;
    }

    // 입력 시점이 오래된 사연은 나이도 그만큼 올려서 본다.
    public static String currentAgeGroup(StoryIntake intake) {
        return intake == null ? null : StoryIntake.ageGroupOf(agedUp(intake));
    }

    private static String elapsedSinceBreakup(StoryIntake intake) {
        Integer days = elapsedDays(intake);
        if (days == null) {
            return null;
        }
        if (days < 30) {
            return days + "일";
        }
        return duration(days / 30);
    }

    private static String person(Integer age, String gender) {
        if (age == null && gender == null) {
            return null;
        }
        StringJoiner joiner = new StringJoiner(" ");
        if (age != null) {
            joiner.add(age + "세");
        }
        if (gender != null) {
            joiner.add(gender);
        }
        return joiner.toString();
    }

    private static String duration(Integer months) {
        if (months == null) {
            return null;
        }
        if (months < 12) {
            return months + "개월";
        }
        int years = months / 12;
        int rest = months % 12;
        return rest == 0 ? years + "년" : years + "년 " + rest + "개월";
    }

    // 보기 대신 "기타"를 골랐으면 직접 쓴 글을 싣는다. 글이 없으면 "기타"만 남는다.
    private static String choice(Enum<?> value, String label, Map<String, String> others, String field) {
        if (value == null) {
            return null;
        }
        return "OTHER".equals(value.name()) ? otherText(others, field, label) : label;
    }

    private static String otherText(Map<String, String> others, String field, String fallback) {
        String text = others.get(field);
        return text == null || text.isBlank() ? fallback : "기타(직접 입력: " + text + ")";
    }

    // 아는 칸만, 빈 글은 버리고, 길면 자른다. 화면이 보낸 키를 그대로 믿으면 프롬프트에
    // 아무 키나 실린다.
    static Map<String, String> sanitizeOthers(Map<String, String> raw) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (raw == null) {
            return clean;
        }
        raw.forEach((field, text) -> {
            if (!OTHER_FIELDS.contains(field) || text == null || text.isBlank()) {
                return;
            }
            String trimmed = text.trim();
            clean.put(field, trimmed.length() > OTHER_MAX_LENGTH
                    ? trimmed.substring(0, OTHER_MAX_LENGTH) : trimmed);
        });
        return clean;
    }

    // "아무것도 없었다"가 있으면 그것만, 아니면 "잘 모르겠다"가 있으면 그것만 남긴다.
    private static List<PartnerAction> exclusiveActions(List<PartnerAction> values) {
        List<PartnerAction> once = exclusive(values, PartnerAction.NOTHING);
        return once.contains(PartnerAction.NOTHING) ? once : exclusive(once, PartnerAction.UNKNOWN);
    }

    private static String labels(List<String> values) {
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private static void line(StringBuilder block, String label, String value) {
        if (value != null && !value.isBlank()) {
            block.append("\n- ").append(label).append(": ").append(value);
        }
    }

    // "아무것도 없었다", "없다" 같은 배타 선택은 다른 값과 함께 오면 그것만 남긴다.
    // 화면에서도 배타로 막지만, 클라이언트가 보낸 값을 그대로 믿으면 프롬프트에
    // "먼저 연락해 왔다, 아무것도 없었다"가 나란히 실린다.
    private static <E extends Enum<E>> List<E> exclusive(List<E> values, E exclusiveValue) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.contains(exclusiveValue)) {
            return List.of(exclusiveValue);
        }
        return new ArrayList<>(values);
    }

    // 두 번째 사연부터 부를 이름, 내 나이, 성별을 물려받는다. 상대에 관한 값은 안 물려받는다 —
    // 사연이 다르면 상대가 다른 사람이다.
    private StoryIntakeResponse prefill(Long userId, Long storyId) {
        List<Long> otherIds = storyRepository
                .findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(userId).stream()
                .map(Story::getId)
                .filter(id -> !id.equals(storyId))
                .toList();
        if (otherIds.isEmpty()) {
            return StoryIntakeResponse.prefilled(null, null);
        }
        return intakeRepository.findByStoryIdInOrderByIdDesc(otherIds).stream()
                .findFirst()
                .map(previous -> StoryIntakeResponse.prefilled(
                        // 나이는 지난번 입력 이후 흐른 해만큼 올려준다 — 재작년에 적은 26세를
                        // 그대로 채워두면 유저가 고치지 않는 한 틀린 나이가 프로필로 간다.
                        agedUp(previous), previous.getUserGender()))
                .orElseGet(() -> StoryIntakeResponse.prefilled(null, null));
    }

    static Integer agedUp(StoryIntake previous) {
        if (previous.getUserAge() == null || previous.getCreatedAt() == null) {
            return previous.getUserAge();
        }
        int years = (int) ChronoUnit.YEARS.between(previous.getCreatedAt().toLocalDate(), LocalDate.now());
        return previous.getUserAge() + Math.max(0, years);
    }

    private void requireOwned(Long userId, Long storyId) {
        storyRepository.findByIdAndUserIdAndDeletedAtIsNull(storyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
    }
}
