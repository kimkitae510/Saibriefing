package com.threeam.story.dto;

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
import com.threeam.story.entity.StoryIntake;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

// 첫 대화 전에 받는 기본 정보. 전부 선택이다 — 하나라도 필수로 걸면 그 칸을 건너뛴 사람의
// 나머지 답이 검증에서 통째로 버려진다(이름을 필수로 걸었을 때 실제로 그랬다).
// 화면은 늘 전체를 보낸다(부분 수정을 받지 않는다. 엔티티 update 주석 참고).
// 상한은 오타 방어다: 나이 세 자리, 교제 100년 같은 값이 프로필로 새어 나가면 매칭이 엉킨다.
public record StoryIntakeRequest(
        @Size(max = 8, message = "이름은 8자까지 적을 수 있습니다.")
        String callName,

        @Min(value = 10, message = "나이를 다시 확인해 주세요.")
        @Max(value = 99, message = "나이를 다시 확인해 주세요.")
        Integer userAge,

        @Min(value = 10, message = "나이를 다시 확인해 주세요.")
        @Max(value = 99, message = "나이를 다시 확인해 주세요.")
        Integer partnerAge,

        IntakeGender userGender,

        @Min(value = 0, message = "교제 기간을 다시 확인해 주세요.")
        @Max(value = 720, message = "교제 기간을 다시 확인해 주세요.")
        Integer datingMonths,

        @Min(value = 0, message = "이별 후 기간을 다시 확인해 주세요.")
        @Max(value = 21900, message = "이별 후 기간을 다시 확인해 주세요.")
        Integer daysSinceBreakup,

        BreakupInitiator initiator,
        ContactMode contactMode,
        PriorReunion priorReunion,
        PartnerNewRelation partnerHasNew,
        PreBreakupChange preBreakupChange,
        ClingReaction clingReaction,
        RepeatBreakupPattern repeatBreakupPattern,
        RepeatSeverity repeatSeverity,
        PriorReunionPath priorReunionPath,
        LastActionBeforeCut lastActionBeforeCut,
        SelfEndReason selfEndReason,
        NewRelationOverlap newRelationOverlap,

        @Size(max = 6) List<PartnerAction> partnerActions,
        @Size(max = 6) List<ContactPoint> contactPoints,

        // "기타"를 고른 칸의 직접 입력. 키는 칸 이름(initiator, contactMode 등).
        // 서비스가 아는 칸만 남기고 길이를 자른다 — 여기서 @Size로 막으면 한 칸이 길다고
        // 문진 전체가 400으로 떨어진다.
        Map<String, String> otherAnswers) {

    public StoryIntake.Branches branches() {
        return new StoryIntake.Branches(clingReaction, repeatBreakupPattern, repeatSeverity,
                priorReunionPath, lastActionBeforeCut, selfEndReason, newRelationOverlap);
    }
}
