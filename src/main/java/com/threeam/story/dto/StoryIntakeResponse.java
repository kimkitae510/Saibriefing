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
import com.threeam.story.entity.PauseContactRule;
import com.threeam.story.entity.PausePlan;
import com.threeam.story.entity.PreBreakupChange;
import com.threeam.story.entity.RelationState;
import com.threeam.story.entity.PriorReunion;
import com.threeam.story.entity.PriorReunionGap;
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
        PriorReunionGap priorReunionGap,
        LastActionBeforeCut lastActionBeforeCut,
        SelfEndReason selfEndReason,
        NewRelationOverlap newRelationOverlap,
        List<PartnerAction> partnerActions,
        List<ContactPoint> contactPoints,
        Map<String, String> otherAnswers,
        RelationState relationState,
        PausePlan pausePlan,
        PauseContactRule pauseContactRule) {

    public static StoryIntakeResponse of(StoryIntake intake) {
        return new StoryIntakeResponse(true,
                intake.getUserAge(), intake.getPartnerAge(),
                intake.getUserGender(), intake.getDatingMonths(), intake.getDaysSinceBreakup(),
                intake.getInitiator(), intake.getContactMode(), intake.getPriorReunion(),
                intake.getPartnerHasNew(), intake.getPreBreakupChange(),
                intake.getClingReaction(), intake.getRepeatBreakupPattern(),
                intake.getRepeatSeverity(), intake.getPriorReunionPath(),
                intake.getPriorReunionGap(), intake.getLastActionBeforeCut(),
                intake.getSelfEndReason(), intake.getNewRelationOverlap(),
                intake.partnerActionList(), intake.contactPointList(), intake.otherAnswerMap(),
                intake.getRelationState(), intake.getPausePlan(), intake.getPauseContactRule());
    }

    // 아직 안 낸 사연. 상대에 관한 값은 물려받지 않는다 — 사연이 다르면 상대가 다른 사람이다.
    // 나이, 성별만 이어받는다(사연이 달라도 유저는 같은 사람이다).
    public static StoryIntakeResponse prefilled(Integer userAge, IntakeGender userGender) {
        return new StoryIntakeResponse(false,
                userAge, null, userGender, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(),
                Map.of(), null, null, null);
    }
}
