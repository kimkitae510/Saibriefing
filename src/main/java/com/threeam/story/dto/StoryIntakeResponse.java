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
import java.util.List;
import java.util.Map;

// submitted가 화면의 분기다: false면 첫 메시지 전에 폼을 띄우고, true면 안 띄운다.
// 폼을 안 거치고 만들어진 옛 사연도 false지만, 대화가 이미 있으면 화면이 안 띄운다 —
// 대화 도중에 설문이 튀어나오는 게 더 나쁘다.
// submitted=false여도 나머지 칸은 채워 보낸다(직전 사연에서 물려받은 미리 채움).
public record StoryIntakeResponse(
        boolean submitted,
        String callName,
        Integer userAge,
        Integer partnerAge,
        IntakeGender userGender,
        Integer datingMonths,
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
        List<PartnerAction> partnerActions,
        List<ContactPoint> contactPoints,
        Map<String, String> otherAnswers) {

    public static StoryIntakeResponse of(StoryIntake intake) {
        return new StoryIntakeResponse(true, intake.getCallName(),
                intake.getUserAge(), intake.getPartnerAge(),
                intake.getUserGender(), intake.getDatingMonths(), intake.getDaysSinceBreakup(),
                intake.getInitiator(), intake.getContactMode(), intake.getPriorReunion(),
                intake.getPartnerHasNew(), intake.getPreBreakupChange(),
                intake.getClingReaction(), intake.getRepeatBreakupPattern(),
                intake.getRepeatSeverity(), intake.getPriorReunionPath(),
                intake.getLastActionBeforeCut(),
                intake.getSelfEndReason(), intake.getNewRelationOverlap(),
                intake.partnerActionList(), intake.contactPointList(), intake.otherAnswerMap());
    }

    // 아직 안 낸 사연. 상대에 관한 값은 물려받지 않는다 — 사연이 다르면 상대가 다른 사람이다.
    // 부를 이름, 나이, 성별만 이어받는다(사연이 달라도 유저는 같은 사람이다).
    // 이름은 null 대신 빈 문자열로 내려보낸다 — 화면이 입력칸에 바로 꽂는 값이라
    // null이 섞이면 폼이 "값 없음"과 "안 물려받음"을 따로 다뤄야 한다.
    public static StoryIntakeResponse prefilled(String callName, Integer userAge,
                                                IntakeGender userGender) {
        return new StoryIntakeResponse(false, callName == null ? "" : callName,
                userAge, null, userGender, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(),
                Map.of());
    }
}
