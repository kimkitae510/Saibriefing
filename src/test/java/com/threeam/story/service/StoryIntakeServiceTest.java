package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.threeam.global.exception.custom.BusinessException;
import com.threeam.story.dto.StoryIntakeRequest;
import com.threeam.story.dto.StoryIntakeResponse;
import com.threeam.story.entity.BreakupInitiator;
import com.threeam.story.entity.ClingReaction;
import com.threeam.story.entity.ContactMode;
import com.threeam.story.entity.ContactPoint;
import com.threeam.story.entity.IntakeGender;
import com.threeam.story.entity.LastActionBeforeCut;
import com.threeam.story.entity.NewRelationOverlap;
import com.threeam.story.entity.PartnerAction;
import com.threeam.story.entity.PartnerNewRelation;
import com.threeam.story.entity.PreBreakupChange;
import com.threeam.story.entity.PriorReunion;
import com.threeam.story.entity.PriorReunionPath;
import com.threeam.story.entity.RepeatBreakupPattern;
import com.threeam.story.entity.RepeatSeverity;
import com.threeam.story.entity.SelfEndReason;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryIntake;
import com.threeam.story.repository.StoryIntakeRepository;
import com.threeam.story.repository.StoryRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoryIntakeServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long STORY_ID = 10L;

    @Mock
    private StoryIntakeRepository intakeRepository;

    @Mock
    private StoryRepository storyRepository;

    @InjectMocks
    private StoryIntakeService service;

    @Test
    @DisplayName("남의 사연이면 404로 막는다")
    void rejectsOtherUsersStory() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID, STORY_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("아직 안 낸 사연은 submitted=false, 직전 사연의 나이와 성별만 물려받는다")
    void prefillsFromPreviousStory() {
        ownedStory();
        given(intakeRepository.findByStoryId(STORY_ID)).willReturn(Optional.empty());
        given(storyRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(USER_ID))
                .willReturn(List.of(story(STORY_ID), story(9L)));
        StoryIntake previous = StoryIntake.builder()
                .storyId(9L)
                .userAge(27)
                .userGender(IntakeGender.FEMALE)
                .partnerAge(30)
                .datingMonths(24)
                .build();
        createdAt(previous, LocalDateTime.now());
        given(intakeRepository.findByStoryIdInOrderByIdDesc(List.of(9L)))
                .willReturn(List.of(previous));

        StoryIntakeResponse response = service.get(USER_ID, STORY_ID);

        assertThat(response.submitted()).isFalse();
        assertThat(response.userAge()).isEqualTo(27);
        assertThat(response.userGender()).isEqualTo(IntakeGender.FEMALE);
        // 상대는 다른 사람이다 — 물려받지 않는다
        assertThat(response.partnerAge()).isNull();
        assertThat(response.datingMonths()).isNull();
    }

    @Test
    @DisplayName("배타 선택은 다른 값과 함께 와도 그것만 남는다")
    void collapsesExclusiveChoices() {
        ownedStory();
        given(intakeRepository.findByStoryId(STORY_ID)).willReturn(Optional.empty());
        given(intakeRepository.save(any(StoryIntake.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        StoryIntakeResponse response = service.save(USER_ID, STORY_ID, request(
                List.of(PartnerAction.REACHED_OUT, PartnerAction.NOTHING),
                List.of(ContactPoint.NONE, ContactPoint.SCHOOL_OR_WORK)));

        assertThat(response.partnerActions()).containsExactly(PartnerAction.NOTHING);
        assertThat(response.contactPoints()).containsExactly(ContactPoint.NONE);
    }

    @Test
    @DisplayName("나이 숫자를 사례 어휘의 나이대로 접는다")
    void bucketsAgeGroup() {
        assertThat(StoryIntake.ageGroupOf(21)).isEqualTo("20대 초반");
        assertThat(StoryIntake.ageGroupOf(25)).isEqualTo("20대 중반");
        assertThat(StoryIntake.ageGroupOf(29)).isEqualTo("20대 후반");
        // 사례에 없는 구간은 값을 만들지 않는다
        assertThat(StoryIntake.ageGroupOf(61)).isNull();
        assertThat(StoryIntake.ageGroupOf(null)).isNull();
    }

    @Test
    @DisplayName("이별 경과는 입력 시점이 아니라 오늘 기준으로 보정된다")
    void correctsElapsedDaysToToday() {
        StoryIntake intake = StoryIntake.builder().storyId(STORY_ID).daysSinceBreakup(12).build();
        createdAt(intake, LocalDateTime.now().minusDays(40));

        assertThat(StoryIntakeService.elapsedDays(intake)).isEqualTo(52);
        assertThat(StoryIntakeService.elapsedMonths(intake)).isEqualTo(1);
        assertThat(StoryIntakeService.describe(intake)).contains("이별 후 경과: 1개월");
    }

    @Test
    @DisplayName("이름을 비우고 낸 문진도 저장되고, 빈 이름은 null로 접힌다")
    void savesWithoutCallName() {
        ownedStory();
        given(intakeRepository.findByStoryId(STORY_ID)).willReturn(Optional.empty());
        given(intakeRepository.save(any(StoryIntake.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        StoryIntakeResponse response = service.save(USER_ID, STORY_ID, new StoryIntakeRequest(
                "  ", 28, null, null, null, 12, null, null, PriorReunion.ONCE, null, null,
                null, null, null, null, null, null, null, List.of(), List.of(), null));

        assertThat(response.submitted()).isTrue();
        assertThat(response.callName()).isNull();
        assertThat(response.priorReunion()).isEqualTo(PriorReunion.ONCE);
    }

    @Test
    @DisplayName("이름을 건너뛰면 블록은 내담자로 부른다")
    void fallsBackToDefaultCallName() {
        StoryIntake intake = StoryIntake.builder().storyId(STORY_ID).userAge(31).build();
        createdAt(intake, LocalDateTime.now());

        assertThat(StoryIntakeService.describe(intake))
                .startsWith("유저가 직접 입력한 기본 정보:\n- 유저를 부르는 이름: 내담자\n- 유저: 31세");
    }

    @Test
    @DisplayName("가지 답(붙잡은 반응, 차단 직전 행동, 먼저 끝낸 이유, 새 사람 시점)이 블록에 실린다")
    void describesBranchAnswers() {
        StoryIntake intake = StoryIntake.builder()
                .storyId(STORY_ID)
                .initiator(BreakupInitiator.SELF)
                .contactMode(ContactMode.BLOCKED)
                .partnerHasNew(PartnerNewRelation.CONFIRMED)
                .branches(new StoryIntake.Branches(ClingReaction.CLUNG_WAVERED,
                        RepeatBreakupPattern.SAME_ISSUE, RepeatSeverity.MORE_FINAL,
                        PriorReunionPath.PARTNER_RETURNED, LastActionBeforeCut.BEGGED,
                        SelfEndReason.IMPULSIVE, NewRelationOverlap.RIGHT_AFTER))
                .build();
        createdAt(intake, LocalDateTime.now());

        String block = StoryIntakeService.describe(intake);

        assertThat(block).contains("전에 헤어졌을 때와 이번 이별의 이유: 같은 문제로");
        assertThat(block).contains("이번 이별의 무게(지난번과 비교): 지난번보다 훨씬 단호했다");
        assertThat(block).contains("지난번에 다시 만나게 된 경로: 상대가 먼저 돌아왔다");
        assertThat(block).contains("유저가 먼저 헤어지자고 한 이유: 홧김에, 충동적으로");
        assertThat(block).contains("헤어진 뒤 유저가 붙잡았는지와 상대 반응: 매달리니 상대가 흔들렸다");
        assertThat(block).contains("차단이나 읽씹 직전 유저의 마지막 행동: 많이 매달렸다");
        assertThat(block).contains("상대의 새 사람이 생긴 시점: 헤어지자마자");
    }

    @Test
    @DisplayName("기타를 고른 칸은 직접 쓴 글이 블록에 실리고, 모르는 칸과 빈 글은 버린다")
    void describesOtherAnswers() {
        ownedStory();
        given(intakeRepository.findByStoryId(STORY_ID)).willReturn(Optional.empty());
        given(intakeRepository.save(any(StoryIntake.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        Map<String, String> others = new java.util.HashMap<>();
        others.put("initiator", "  서로 동시에 말했다  ");
        others.put("contactMode", "   ");
        others.put("hacked", "무시할 것");

        StoryIntakeResponse response = service.save(USER_ID, STORY_ID, new StoryIntakeRequest(
                null, null, null, null, null, null, BreakupInitiator.OTHER, ContactMode.OTHER,
                null, null, null, null, null, null, null, null, null, null,
                List.of(PartnerAction.REACHED_OUT, PartnerAction.UNKNOWN), List.of(), others));

        assertThat(response.otherAnswers()).containsOnlyKeys("initiator");
        // 잘 모르겠다는 배타 — 다른 행동과 같이 오면 그것만 남는다
        assertThat(response.partnerActions()).containsExactly(PartnerAction.UNKNOWN);

        StoryIntake saved = StoryIntake.builder().storyId(STORY_ID)
                .initiator(BreakupInitiator.OTHER).contactMode(ContactMode.OTHER)
                .otherAnswers(Map.of("initiator", "서로 동시에 말했다")).build();
        String block = StoryIntakeService.describe(saved);
        assertThat(block).contains("먼저 이별을 원한 쪽: 기타(직접 입력: 서로 동시에 말했다)");
        assertThat(block).contains("연락 상황(첫 입력 시점): 기타");
    }

    @Test
    @DisplayName("빈 폼은 프롬프트 블록을 만들지 않는다")
    void skipsEmptyBlock() {
        StoryIntake empty = StoryIntake.builder().storyId(STORY_ID).build();

        assertThat(StoryIntakeService.describe(empty)).isNull();
        assertThat(StoryIntakeService.describe(null)).isNull();
    }

    @Test
    @DisplayName("변하는 값은 첫 입력 시점임을 블록에 밝힌다")
    void marksVolatileValuesAsSnapshot() {
        StoryIntake intake = StoryIntake.builder()
                .storyId(STORY_ID)
                .userAge(28)
                .userGender(IntakeGender.MALE)
                .initiator(BreakupInitiator.PUSHED)
                .contactMode(ContactMode.I_INITIATE)
                .partnerHasNew(PartnerNewRelation.UNKNOWN)
                .priorReunion(PriorReunion.ONCE)
                .preBreakupChange(PreBreakupChange.GRADUAL_COOLING)
                .build();
        createdAt(intake, LocalDateTime.now());

        String block = StoryIntakeService.describe(intake);

        assertThat(block).contains("유저: 28세 남");
        assertThat(block).contains("먼저 이별을 원한 쪽: 말은 유저가 꺼냈지만 상대가 그렇게 만들었다");
        assertThat(block).contains("연락 상황(첫 입력 시점)");
        assertThat(block).contains("상대에게 새 사람(첫 입력 시점)");
        // 안 변하는 값에는 시점 꼬리표를 안 단다
        assertThat(block).contains("이 상대와 재회 경험: 헤어졌다 다시 만난 적 한 번");
    }

    private void ownedStory() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.of(story(STORY_ID)));
    }

    private static Story story(Long id) {
        Story story = Story.builder().userId(USER_ID).title("t").build();
        set(story, "id", id);
        return story;
    }

    private static StoryIntakeRequest request(List<PartnerAction> actions, List<ContactPoint> points) {
        return new StoryIntakeRequest("지호", 28, 27, IntakeGender.MALE, 24, 12,
                BreakupInitiator.PARTNER, ContactMode.NONE, PriorReunion.NONE,
                PartnerNewRelation.UNKNOWN, PreBreakupChange.SUDDEN,
                null, null, null, null, null, null, null, actions, points, null);
    }

    // @CreationTimestamp는 영속화 시점에 찍힌다 — 단위 테스트에는 그 시점이 없어 직접 넣는다.
    private static void createdAt(StoryIntake intake, LocalDateTime value) {
        set(intake, "createdAt", value);
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
